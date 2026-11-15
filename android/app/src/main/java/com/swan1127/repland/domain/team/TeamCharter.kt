package com.swan1127.repland.domain.team

/**
 * 队伍大局与队规：队长对全局的把控落成一个持久对象。
 * 保守默认（LOCKED）恒在、不可关；队长可开关一批策略（TOGGLE），也能补自己的规矩（CUSTOM）。
 * 注入全队提示词顶部，队长与队员都看得到约束自己的那部分。
 */
enum class CharterRuleKind { LOCKED, TOGGLE, CUSTOM }

data class CharterRule(
    val id: String,
    val text: String,
    val kind: CharterRuleKind,
    val enabled: Boolean = true,
)

data class TeamCharter(
    val goal: String = "",
    val rules: List<CharterRule> = defaultRules(),
) {
    val activeRules: List<CharterRule>
        get() = rules.filter { it.kind == CharterRuleKind.LOCKED || it.enabled }

    val toggleRules: List<CharterRule>
        get() = rules.filter { it.kind == CharterRuleKind.TOGGLE }

    val customRules: List<CharterRule>
        get() = rules.filter { it.kind == CharterRuleKind.CUSTOM }

    fun withGoal(text: String): TeamCharter = copy(goal = text.trim())

    fun withToggle(id: String, enabled: Boolean): TeamCharter =
        copy(rules = rules.map { if (it.id == id && it.kind == CharterRuleKind.TOGGLE) it.copy(enabled = enabled) else it })

    fun addCustom(id: String, text: String): TeamCharter {
        val trimmed = text.trim()
        if (trimmed.isBlank() || rules.any { it.id == id }) return this
        return copy(rules = rules + CharterRule(id, trimmed, CharterRuleKind.CUSTOM))
    }

    fun removeCustom(id: String): TeamCharter =
        copy(rules = rules.filterNot { it.id == id && it.kind == CharterRuleKind.CUSTOM })

    companion object {
        fun defaultRules(): List<CharterRule> = listOf(
            CharterRule("lock-confirm", "写入先草案、后确认；不替用户直接执行，也不谎报已完成。", CharterRuleKind.LOCKED),
            CharterRule("lock-busy", "排期避开成员真实课表与已确认安排；私有时间只当「在忙」，不猜内容。", CharterRuleKind.LOCKED),
            CharterRule("lock-consent", "转派、取消、改截止、改基线必须当事人或队长确认；MUST 任务不可取消。", CharterRuleKind.LOCKED),
            CharterRule("lock-ledger", "台账只客观留证，不打分、不排名、不评价个人。", CharterRuleKind.LOCKED),
            CharterRule("lock-fact", "现状数字以工具现查为准，不编造成员、任务或来源。", CharterRuleKind.LOCKED),
            CharterRule("toggle-objection", "异议优先：有未裁决异议时，先处理异议再排相关任务。", CharterRuleKind.TOGGLE),
            CharterRule("toggle-lowconf", "低置信度提醒：课表未导入成员的安排单独标红，确认前重点核对。", CharterRuleKind.TOGGLE),
            CharterRule("toggle-headroom", "负载留余量：每周排期不超过成员登记空闲的八成。", CharterRuleKind.TOGGLE),
        )
    }
}