package com.swan1127.repland.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic floor for the group chat.
 *
 * When the model cannot answer (no key, no network, timeout, rejected key), the sentence is
 * still read by [RuleIntentParser] and expressed as the same tool calls the model would emit.
 * Nothing is written here: every call goes through the existing admission checks and the
 * confirmation card. The model stays the ceiling; rules only make sure a plain sentence with
 * a date and a clock never turns into an error card.
 */
sealed interface AgentRuleOutcome {
    /** The sentence is genuinely ambiguous: ask before touching anything. */
    data class Ask(val text: String) : AgentRuleOutcome

    /** Queue these write calls for confirmation, in order. */
    data class Queue(val calls: List<AgentToolCall>) : AgentRuleOutcome
}

object AgentRulePlanner {
    fun plan(
        utterance: String,
        today: LocalDate,
        activeDate: LocalDate,
        tasks: List<RuleTask>,
        placementOf: (String) -> AgentRulePlacement? = { null },
    ): AgentRuleOutcome? {
        val text = utterance.trim()
        if (text.isEmpty()) return null
        val rule = RuleIntentParser.parse(text, today, tasks)
        if (rule.mustAsk) {
            // English wording is a model boundary, not a parse failure: leave it to the cloud
            // so the caller can report the real reason instead of pretending it understood.
            if (rule.reasons.contains("english-intent")) return null
            return AgentRuleOutcome.Ask(RuleIntentParser.clarifyReply(rule.reasons))
        }
        if (rule.intent == RuleIntentKind.QUERY) return null
        if (rule.review) return review(rule, today)
        return when (rule.action) {
            RuleAction.ROUTINE -> routine(rule)
            RuleAction.BUSY -> busy(rule, today)
            RuleAction.POSTPONE, RuleAction.SCHEDULE -> reschedule(rule, activeDate, tasks, placementOf)
            RuleAction.PROGRESS -> status(rule, "record_feedback", text)
            RuleAction.COMPLETE -> status(rule, "complete_task", text)
            RuleAction.CANCEL -> status(rule, "cancel_task", text)
            RuleAction.CREATE -> capture(rule, activeDate)
            null -> null
        }
    }

    /** 复盘协议：保留原时段，进度照记，额外时间另起一段。 */
    private fun review(rule: RulePlan, activeDate: LocalDate): AgentRuleOutcome? {
        val progress = rule.draftItems.firstOrNull { it.action == RuleAction.PROGRESS } ?: return null
        val extra = rule.draftItems.firstOrNull { it.action == RuleAction.CREATE }
        val target = rule.contextTaskId ?: rule.contextTaskName ?: progress.name
        if (target.isBlank()) return null
        val calls = mutableListOf<AgentToolCall>()
        val feedback = JSONObject()
            .put("taskId", target)
            .put("content", "没完成，需要额外安排一些时间")
        progress.progress?.let { feedback.put("progressPercent", it) }
        calls += AgentToolCall("record_feedback", feedback.toString())
        if (extra != null) {
            val item = JSONObject()
                .put("taskId", target)
                .put("durationMinutes", extra.durationMinutes ?: 60)
            extra.startMinute?.let { item.put("start", clock(it)) }
            calls += AgentToolCall(
                "plan_tasks",
                JSONObject()
                    .put("date", (extra.date ?: activeDate).toString())
                    .put("items", JSONArray().put(item))
                    .toString(),
            )
        }
        return AgentRuleOutcome.Queue(calls)
    }

    private fun routine(rule: RulePlan): AgentRuleOutcome? {
        val item = rule.draftItems.firstOrNull() ?: return null
        val weekdays = item.weekdays.orEmpty().distinct().sorted()
        if (weekdays.isEmpty()) return null
        val args = JSONObject()
            .put("name", item.name.ifBlank { "固定安排" })
            .put("weekdays", weekdays.joinToString(","))
            .put("kind", "OTHER")
        // 没点名钟点就交给工具按空档挑：离线也不替用户发明一个时间。
        if (item.startMinute != null) {
            args.put("start", clock(item.startMinute))
            val end = item.endMinute ?: item.startMinute + (item.durationMinutes ?: 60)
            if (end <= item.startMinute) return null
            args.put("end", clock(end.coerceAtMost(1_440)))
        } else {
            args.put("durationMinutes", (item.durationMinutes ?: 60).coerceIn(5, 720))
        }
        return AgentRuleOutcome.Queue(listOf(AgentToolCall("save_routine", args.toString())))
    }

    private fun busy(rule: RulePlan, today: LocalDate): AgentRuleOutcome? {
        val item = rule.draftItems.firstOrNull() ?: return null
        val start = item.startMinute ?: return null
        val end = item.endMinute ?: item.durationMinutes?.let { start + it } ?: return null
        if (end <= start) return null
        val title = item.name.ifBlank { "别排事" }
        val days = item.weekdays.orEmpty().distinct().sorted()
        val dates = if (days.isEmpty()) {
            listOf(item.date ?: today)
        } else {
            days.map { nextWeekday(today, it) }
        }
        val calls = dates.map { date ->
            AgentToolCall(
                "set_available_time",
                JSONObject()
                    .put("startMinute", clock(start))
                    .put("endMinute", clock(end.coerceAtMost(1_440)))
                    .put("free", false)
                    .put("title", title)
                    .apply { if (days.isNotEmpty()) put("repeatWeekly", true) }
                    .put("date", date.toString())
                    .toString(),
            )
        }
        return AgentRuleOutcome.Queue(calls)
    }

    private fun reschedule(
        rule: RulePlan,
        activeDate: LocalDate,
        tasks: List<RuleTask>,
        placementOf: (String) -> AgentRulePlacement?,
    ): AgentRuleOutcome? {
        val item = rule.draftItems.firstOrNull() ?: return null
        val name = rule.contextTaskName ?: item.name
        val targetId = rule.contextTaskId
            ?: tasks.firstOrNull { it.name == name }?.id
            ?: return null
        val placement = placementOf(targetId)
        // 「改到下午三点」只说钟点：任务排在哪天就改哪天的时刻，没排过才落到界面那天。
        val date = item.date ?: placement?.date ?: item.startMinute?.let { activeDate } ?: return null
        if (placement != null) {
            // 已经排进时间轴的任务要挪原段：再排一段就成了同一件事占两个时段。
            val start = item.startMinute ?: placement.startMinute
            val duration = item.durationMinutes ?: (placement.endMinute - placement.startMinute).coerceAtLeast(5)
            return AgentRuleOutcome.Queue(
                listOf(
                    AgentToolCall(
                        "move_placement",
                        JSONObject()
                            .put("segmentId", placement.segmentId)
                            .put("date", date.toString())
                            .put("startMinute", clock(start))
                            .put("endMinute", clock((start + duration).coerceAtMost(1_440)))
                            .toString(),
                    ),
                ),
            )
        }
        val entry = JSONObject()
            .put("taskId", targetId)
            .put("durationMinutes", item.durationMinutes ?: 60)
        item.startMinute?.let { entry.put("start", clock(it)) }
        return AgentRuleOutcome.Queue(
            listOf(
                AgentToolCall(
                    "plan_tasks",
                    JSONObject().put("date", date.toString()).put("items", JSONArray().put(entry)).toString(),
                ),
            ),
        )
    }

    private fun status(rule: RulePlan, tool: String, text: String): AgentRuleOutcome? {
        val item = rule.draftItems.firstOrNull() ?: return null
        val target = rule.contextTaskId ?: rule.contextTaskName ?: item.name
        if (target.isBlank()) return null
        val args = JSONObject().put("taskId", target)
        if (tool == "record_feedback") {
            args.put("content", text)
            item.progress?.let { args.put("progressPercent", it) }
        }
        return AgentRuleOutcome.Queue(listOf(AgentToolCall(tool, args.toString())))
    }

    private fun capture(rule: RulePlan, activeDate: LocalDate): AgentRuleOutcome? {
        val items = rule.draftItems.filter { it.action == RuleAction.CREATE }
        if (items.isEmpty()) return null
        val first = items.first()
        // 「周五前」是截止，不是时段：建任务时带上截止日，绝不占时间轴。
        if (first.dueDate != null && first.startMinute == null) {
            if (items.size > 1) return null
            return AgentRuleOutcome.Queue(
                listOf(
                    AgentToolCall(
                        "create_task",
                        JSONObject()
                            .put("name", first.name)
                            .put("durationMinutes", first.durationMinutes ?: 60)
                            .put("dueDate", first.dueDate.toString())
                            .toString(),
                    ),
                ),
            )
        }
        val dates = items.mapNotNull { it.date }.distinct()
        if (dates.size > 1) return null
        val date = dates.firstOrNull()
        val clocked = items.any { it.startMinute != null }
        if (clocked) {
            val entries = JSONArray()
            items.forEach { item ->
                entries.put(
                    JSONObject()
                        .put("name", item.name)
                        .put("durationMinutes", item.durationMinutes ?: 60)
                        .apply { item.startMinute?.let { put("start", clock(it)) } },
                )
            }
            return AgentRuleOutcome.Queue(
                listOf(
                    AgentToolCall(
                        "plan_tasks",
                        JSONObject().put("date", (date ?: activeDate).toString()).put("items", entries).toString(),
                    ),
                ),
            )
        }
        // 只说了哪天：记下计划日，不往时间轴塞一段。
        return AgentRuleOutcome.Queue(
            items.map { item ->
                val args = JSONObject()
                    .put("name", item.name)
                    .put("durationMinutes", item.durationMinutes ?: 60)
                date?.let { args.put("date", it.toString()) }
                AgentToolCall("create_task", args.toString())
            },
        )
    }

    private fun nextWeekday(today: LocalDate, weekday: Int): LocalDate =
        today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday.coerceIn(1, 7))))

    private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}
