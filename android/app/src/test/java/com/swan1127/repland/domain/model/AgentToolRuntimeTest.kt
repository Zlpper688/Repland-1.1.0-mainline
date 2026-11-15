package com.swan1127.repland.domain.model

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRuntimeTest {
    private val day = LocalDate.of(2026, 10, 23)

    private class ScriptedChat(private val turns: List<AgentTurn>) : AgentTurnModel {
        var index = 0
        val received = mutableListOf<List<AgentChatMessage>>()

        override suspend fun complete(messages: List<AgentChatMessage>): AgentTurn {
            received += messages
            // 脚本耗尽后返回无工具的终局回复，模拟模型结束回合而不是循环重放
            return turns.getOrNull(index++) ?: AgentTurn.Reply(text = "（脚本结束）")
        }
    }

    private class FakeToolbox : AgentToolbox {
        val reads = mutableListOf<Pair<String, JSONObject>>()
        val writes = mutableListOf<AgentAction>()
        var writeResult: AgentActionResult = AgentActionResult(true, "已完成")

        override fun specs(): List<AgentToolSpec> = listOf(
            AgentToolSpec(
                name = "query_schedule", label = "读今日安排", description = "读取今日占用",
                kind = AgentToolKind.READ,
            ),
            AgentToolSpec(
                name = AgentConversationEngine.SPECIALIST_TOOL, label = "咨询成员", description = "向组内成员提问",
                params = listOf(
                    AgentToolParam("member", "成员 id", required = true),
                    AgentToolParam("question", "问题", required = true),
                ),
                kind = AgentToolKind.READ,
            ),
            AgentToolSpec(
                name = "create_task", label = "创建任务", description = "创建一个任务",
                params = listOf(
                    AgentToolParam("name", "名称", required = true),
                    AgentToolParam("durationMinutes", "时长分钟", required = true),
                ),
                kind = AgentToolKind.WRITE,
            ),
            AgentToolSpec(
                name = "place_task", label = "放入时段", description = "放置",
                kind = AgentToolKind.WRITE,
                validate = { args -> if (args.opt("startMinute") == null) "缺开始时间" else null },
            ),
            AgentToolSpec(
                name = "plan_tasks", label = "排期草案", description = "同一槽位的方案",
                kind = AgentToolKind.WRITE, draftSlot = "plan",
            ),
            AgentToolSpec(
                name = "schedule_draft", label = "重排草案", description = "同一槽位的方案",
                kind = AgentToolKind.WRITE, draftSlot = "plan",
            ),
            AgentToolSpec(
                name = "complete_task", label = "完成任务", description = "把任务标记为已完成",
                kind = AgentToolKind.WRITE,
                summarize = { "完成「复习」" },
            ),
            AgentToolSpec(
                name = "apply_plan", label = "确认排期", description = "由 place_task 派生，模型不可直接调用",
                kind = AgentToolKind.WRITE, hidden = true,
            ),
            AgentToolSpec(
                name = "confirm_draft", label = "按最新状态执行草案", description = "控制",
                kind = AgentToolKind.CONTROL,
            ),
            AgentToolSpec(
                name = "cancel_draft", label = "取消待确认草案", description = "控制",
                kind = AgentToolKind.CONTROL,
            ),
            AgentToolSpec(
                name = "undo_last", label = "撤销上一批写入", description = "控制",
                kind = AgentToolKind.CONTROL,
            ),
        )

        override suspend fun runRead(name: String, arguments: JSONObject): AgentToolResult {
            reads += name to arguments
            return AgentToolResult(true, "3 项占用")
        }

        override suspend fun runWrite(action: AgentAction): AgentActionResult {
            writes += action
            return writeResult
        }
    }

    private fun engine(chat: AgentTurnModel, toolbox: AgentToolbox = FakeToolbox()) =
        AgentConversationEngine(chat = chat, toolbox = toolbox, turnTimeoutMillis = 5_000)

    @Test fun readToolResultIsFedBackAndFinalSayEmitted() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "我先看看今天", toolCall = AgentToolCall("query_schedule", "{}")),
                AgentTurn.Reply(text = "下午两点有空"),
            ),
        )
        val toolbox = FakeToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat, toolbox).runTurn(
            session = AgentSessionState(),
            utterance = "今天还有空吗",
            context = AgentTurnContext(day, emptyList()),
            onEvent = { events += it },
        )

        assertEquals(1, toolbox.reads.size)
        assertEquals(listOf("user", "assistant", "user", "assistant"), session.transcript.map { it.role })
        assertTrue(session.transcript[2].content.contains("[工具结果] query_schedule：3 项占用"))
        assertEquals(
            listOf<AgentConversationEvent>(
                AgentConversationEvent.TextOutput("我先看看今天"),
                AgentConversationEvent.ToolStarted("读今日安排"),
                AgentConversationEvent.ToolInvoked("query_schedule", "读今日安排", "无参数", ToolOutcome.DONE, "3 项占用"),
                AgentConversationEvent.TextOutput("下午两点有空"),
            ),
            events,
        )
        assertTrue(session.pendingActions.isEmpty())
    }

    @Test fun writeToolOnlyQueuesAndNeverExecutes() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(
                    text = "我来创建",
                    toolCall = AgentToolCall("create_task", """{"name":"复习高数","durationMinutes":60}"""),
                ),
                AgentTurn.Reply(text = "已列入待确认清单"),
            ),
        )
        val toolbox = FakeToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat, toolbox).runTurn(
            AgentSessionState(), "安排复习高数一小时", AgentTurnContext(day, emptyList()),
        ) { events += it }

        assertTrue(toolbox.writes.isEmpty())
        val queued = events.filterIsInstance<AgentConversationEvent.ActionQueued>().single()
        assertEquals("create_task", queued.action.toolName)
        assertTrue(queued.action.summary.contains("复习高数"))
        assertEquals(listOf(queued.action), session.pendingActions)
        assertTrue(
            session.transcript.any { it.role == "user" && it.content.contains("还没有执行") },
        )
    }

    @Test fun resumeAfterActionSettlesSilentlyAndQueuesFollowUps() = runTest {
        val chat = ScriptedChat(emptyList())
        val toolbox = FakeToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val action = AgentAction("a1", "create_task", "创建任务", "创建任务：复习高数", "{}")
        val followUp = AgentAction("a2", "place_task", "放入时段", "放入时段：14:00", """{"startMinute":840}""")
        toolbox.writeResult = AgentActionResult(true, "已创建任务", followUpActions = listOf(followUp))
        val session = engine(chat, toolbox).resumeAfterAction(
            session = AgentSessionState(
                transcript = listOf(AgentChatMessage("user", "安排复习高数"), AgentChatMessage("assistant", "{}")),
                pendingActions = listOf(action),
            ),
            action = action,
            result = toolbox.writeResult,
            context = AgentTurnContext(day, emptyList()),
            onEvent = { events += it },
        )

        // 静默结算：不烧模型调用，逻辑调整行就是给用户的记录。
        assertEquals(0, chat.index)
        assertEquals(listOf(followUp), session.pendingActions)
        assertEquals(listOf<AgentConversationEvent>(AgentConversationEvent.ActionQueued(followUp)), events)
        assertTrue(
            session.transcript.any { it.role == "user" && it.content.contains("[用户已确认执行]") },
        )
        // 派生动作的行排在确认行之后：模型最后读到的必须是「它还没执行」，否则它会替用户宣布排好了。
        val lastUserLine = session.transcript.last { it.role == "user" }.content
        assertTrue(lastUserLine.contains("[已加入待确认队列] place_task"))
        assertTrue(lastUserLine.contains("不要当作已经完成"))
    }

    @Test fun conflictReviewBlocksQueueingWhenGuardObjects() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = null, toolCall = AgentToolCall("place_task", """{"taskId":"t1","startMinute":840}""")),
                AgentTurn.Reply(text = "冲突\n14:00 和「数学课」撞了"),
                AgentTurn.Reply(text = "那我换个说法"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "把任务放到 14:00", AgentTurnContext(day, emptyList()),
        ) { events += it }

        val review = events.filterIsInstance<AgentConversationEvent.ToolInvoked>().single()
        assertEquals("小盾评审", review.label)
        assertEquals(ToolOutcome.FAILED, review.outcome)
        assertTrue(session.pendingActions.isEmpty())
        assertTrue(session.transcript.any { it.content.contains("小盾评审没通过") })
    }

    @Test fun conflictReviewPassesToQueueWhenGuardApproves() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = null, toolCall = AgentToolCall("place_task", """{"taskId":"t1","startMinute":840}""")),
                AgentTurn.Reply(text = "通过"),
                AgentTurn.Reply(text = "已放入待确认"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "把任务放到 14:00", AgentTurnContext(day, emptyList()),
        ) { events += it }

        val review = events.filterIsInstance<AgentConversationEvent.ToolInvoked>().first()
        assertEquals("小盾评审", review.label)
        assertEquals(ToolOutcome.DONE, review.outcome)
        assertEquals(1, session.pendingActions.size)
        assertEquals(
            listOf<AgentConversationEvent>(
                AgentConversationEvent.ToolStarted("放入时段"),
                AgentConversationEvent.ToolInvoked(
                    "conflict_review", "小盾评审", "放入时段", ToolOutcome.DONE, "通过",
                ),
                AgentConversationEvent.ActionQueued(session.pendingActions.first()),
                AgentConversationEvent.TextOutput("已放入待确认"),
            ),
            events,
        )
    }

    @Test fun followUpsAreReadmittedBeforeQueueing() = runTest {
        val chat = ScriptedChat(emptyList())
        val toolbox = FakeToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val action = AgentAction("a1", "create_task", "创建任务", "创建任务：复习高数", "{}")
        val stale = AgentAction("a2", "place_task", "放入时段", "放入时段：14:00", "{}")
        val fresh = AgentAction("a3", "place_task", "放入时段", "放入时段：15:00", """{"startMinute":900}""")
        toolbox.writeResult = AgentActionResult(true, "已创建", followUpActions = listOf(stale, fresh))
        val session = engine(chat, toolbox).resumeAfterAction(
            session = AgentSessionState(pendingActions = listOf(action)),
            action = action,
            result = toolbox.writeResult,
            context = AgentTurnContext(day, emptyList()),
            onEvent = { events += it },
        )

        // 静默结算 + 排队前重新准入：失效的落点被拦下，仍然有效的照常入队。
        assertEquals(0, chat.index)
        assertEquals(listOf(fresh), session.pendingActions)
        val failed = events.filterIsInstance<AgentConversationEvent.ToolInvoked>().single()
        assertEquals(ToolOutcome.FAILED, failed.outcome)
        assertTrue(failed.resultSummary.contains("缺开始时间"))
    }

    @Test fun unknownToolFailsVisiblyAndKeepsLoopGoing() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = null, toolCall = AgentToolCall("no_such_tool", "{}")),
                AgentTurn.Reply(text = "好的，我换一种说法"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(AgentSessionState(), "随便说", AgentTurnContext(day, emptyList())) {
            events += it
        }

        val failed = events.filterIsInstance<AgentConversationEvent.ToolInvoked>().single()
        assertEquals(ToolOutcome.FAILED, failed.outcome)
        assertTrue(session.transcript[2].content.contains("没有这个工具"))
    }

    @Test fun specialistConsultRendersAsMemberSpeechAndToolResult() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = null, toolCall = AgentToolCall("ask_specialist", """{"member":"time","question":"晚上有空吗"}""")),
                AgentTurn.Reply(text = "小刻说晚上有整块时间"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(AgentSessionState(), "问问小刻", AgentTurnContext(day, emptyList())) {
            events += it
        }

        val said = events.filterIsInstance<AgentConversationEvent.SpecialistSaid>().single()
        assertEquals("time", said.agentId)
        assertTrue(session.transcript[2].content.contains("小刻："))
    }

    @Test fun theSameMemoryReachesThePlannerAndTheConsultedMember() = runTest {
        val context = AgentTurnContext(
            date = day,
            occupiedEntries = emptyList(),
            memoryNotes = (1..14).map { "草案·$it" to "习惯第 $it 条" },
            memoryProfile = MemoryReference.of(
                listOf(
                    MemoryEntry("t1", "泰勒展开余项", fading()),
                    MemoryEntry("t2", "高数第一章", solid()),
                ),
                day,
            ),
            memoryEpisodes = listOf("10月20日 接受了草案：「背单词」"),
        )
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = null, toolCall = AgentToolCall("ask_specialist", """{"member":"time","question":"他晚上一般学多久"}""")),
                AgentTurn.Reply(text = "小刻说他晚上学得短"),
            ),
        )

        engine(chat).runTurn(AgentSessionState(), "问问小刻", context) { }

        val planner = chat.received.first().first().content
        val specialist = chat.received[1].first().content
        listOf(planner, specialist).forEach { prompt ->
            assertTrue(prompt, prompt.contains("泰勒展开余项"))
            assertTrue(prompt, prompt.contains("习惯第 14 条"))
            assertTrue(prompt, prompt.contains("接受了草案：「背单词」"))
            // 14 条习惯只带最近 10 条：旧版本取的是最早那 8 条，用户最近说的话反而进不了提示词。
            assertFalse(prompt, prompt.contains("习惯第 1 条"))
        }
    }

    @Test fun systemPromptOverrideAndDomainBriefingKeepEnvelopeAndHideDerivedTools() = runTest {
        val chat = ScriptedChat(listOf(AgentTurn.Reply(text = "好")))
        val engine = AgentConversationEngine(
            chat = chat,
            toolbox = FakeToolbox(),
            turnTimeoutMillis = 5_000,
            systemPromptOverride = { "团队助手提示：以当前身份行事。" },
        )
        engine.runTurn(
            AgentSessionState(),
            "这周怎么排",
            AgentTurnContext(day, emptyList(), domainBriefing = "【队伍】演示队 · 比赛截止 2026-12-20"),
        ) { }

        val system = chat.received.first().first().content
        assertTrue(system, system.contains("团队助手提示：以当前身份行事。"))
        assertTrue(system, system.contains("单行 JSON"))
        assertTrue(system, system.contains("【队伍】演示队"))
        assertTrue(system, system.contains("今天日期：2026-10-23"))
        assertTrue(system, system.contains("create_task"))
        assertFalse("派生动作不该进提示词目录", system.contains("apply_plan"))
    }

    @Test fun personaDocLeadsTheBriefingAndBlankIsAbsent() {
        val doc = "我是大二学生，早上状态最好，想把高数放在上午。"
        val withDoc = AgentTurnContext(
            date = day, occupiedEntries = emptyList(),
            memoryNotes = listOf("草案·高数" to "习惯放在上午"),
            personaDoc = doc,
        ).memoryBriefing()
        assertTrue(withDoc, withDoc.contains("用户自己写的自述"))
        assertTrue(withDoc, withDoc.contains(doc))
        // 用户亲口写的排在推测信号前面：同一个记忆口径里，自述优先于习惯/画像。
        assertTrue(withDoc, withDoc.indexOf(doc) < withDoc.indexOf("习惯放在上午"))

        val blank = AgentTurnContext(
            date = day, occupiedEntries = emptyList(),
            memoryNotes = listOf("草案·高数" to "习惯放在上午"),
            personaDoc = "   ",
        ).memoryBriefing()
        assertFalse(blank, blank.contains("用户自己写的自述"))
    }

    @Test fun personaDocReachesThePlannerAndTheConsultedMember() = runTest {
        val doc = "我在准备蓝桥杯，周末能多投入一点。"
        val context = AgentTurnContext(
            date = day, occupiedEntries = emptyList(),
            memoryNotes = listOf("草案·刷题" to "习惯晚上刷题"),
            personaDoc = doc,
        )
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = null, toolCall = AgentToolCall("ask_specialist", """{"member":"time","question":"周末能排多久"}""")),
                AgentTurn.Reply(text = "小刻说周末可以多排"),
            ),
        )
        engine(chat).runTurn(AgentSessionState(), "问问小刻", context) { }
        val planner = chat.received.first().first().content
        val specialist = chat.received[1].first().content
        listOf(planner, specialist).forEach { prompt ->
            assertTrue(prompt, prompt.contains(doc))
            assertTrue(prompt, prompt.contains("用户自己写的自述"))
        }
    }

    @Test fun confirmControlNeverWritesInsideTheEngine() = runTest {
        val pending = AgentAction("a1", "create_task", "创建任务", "复习高数", """{"name":"复习高数","durationMinutes":60}""")
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "好", toolCall = AgentToolCall("confirm_draft", "{}")),
            ),
        )
        val toolbox = FakeToolbox()
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat, toolbox).runTurn(
            AgentSessionState(pendingActions = listOf(pending)),
            "就按这个执行",
            AgentTurnContext(day, emptyList()),
        ) { events += it }

        assertTrue(toolbox.writes.isEmpty())
        assertEquals(1, session.pendingActions.size)
        assertTrue(events.any { it == AgentConversationEvent.ControlRequested(AgentControl.CONFIRM_DRAFT) })
        assertTrue(session.transcript.last().content.contains("[控制]"))
    }

    @Test fun confirmControlWithoutDraftIsRejectedAndWrittenBack() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "好", toolCall = AgentToolCall("confirm_draft", "{}")),
                AgentTurn.Reply(text = "现在没有待确认的方案"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "确认", AgentTurnContext(day, emptyList()),
        ) { events += it }

        assertFalse(events.any { it is AgentConversationEvent.ControlRequested })
        assertTrue(events.any {
            it is AgentConversationEvent.ToolInvoked && it.name == "confirm_draft" && it.outcome == ToolOutcome.FAILED
        })
        assertTrue(session.transcript.any { it.content.contains("[控制未执行]") })
    }

    @Test fun newDraftInSameSlotReplacesTheOldOne() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "先排第一版", toolCall = AgentToolCall("plan_tasks", """{"plan":"A"}""")),
                AgentTurn.Reply(text = "第一版好了"),
                AgentTurn.Reply(text = "改第二版", toolCall = AgentToolCall("plan_tasks", """{"plan":"B"}""")),
                AgentTurn.Reply(text = "第二版好了"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val first = engine(chat).runTurn(
            AgentSessionState(), "排一版", AgentTurnContext(day, emptyList()),
        ) { events += it }
        assertEquals(listOf("A"), first.pendingActions.map { JSONObject(it.argumentsJson).optString("plan") })

        val second = engine(chat).runTurn(
            first, "换个排法", AgentTurnContext(day, emptyList()),
        ) { events += it }
        assertEquals(listOf("B"), second.pendingActions.map { JSONObject(it.argumentsJson).optString("plan") })
        val replaced = events.filterIsInstance<AgentConversationEvent.DraftReplaced>().last()
        assertEquals(listOf("A"), replaced.previous.map { JSONObject(it.argumentsJson).optString("plan") })
        assertTrue(second.transcript.any { it.content.contains("[草案已更新]") })
    }

    @Test fun statusWriteWithoutUserWordingBecomesAQuestion() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(
                    text = "吃饭已执行完成。",
                    toolCall = AgentToolCall("complete_task", """{"taskId":"t1"}"""),
                ),
                AgentTurn.Reply(text = "（脚本结束）"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "吃饭，吃一个消失", AgentTurnContext(day, emptyList()),
        ) { events += it }

        // 一句听不清的话不许变成完成草案，也不许先对用户说"已完成"。
        assertFalse(events.any { it == AgentConversationEvent.TextOutput("吃饭已执行完成。") })
        assertTrue(events.none { it is AgentConversationEvent.ActionQueued })
        assertTrue(session.pendingActions.isEmpty())
        val ask = events.filterIsInstance<AgentConversationEvent.TextOutput>().single().text
        assertTrue(ask, ask.contains("记为完成"))
        assertTrue(session.transcript.any { it.role == "assistant" && it.content.contains("记为完成") })
        val failed = events.filterIsInstance<AgentConversationEvent.ToolInvoked>().single()
        assertEquals(ToolOutcome.FAILED, failed.outcome)
        assertTrue(failed.resultSummary.contains("没说要完成"))
    }

    @Test fun statusWriteWithTheUsersOwnWordingStillQueues() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "记一下", toolCall = AgentToolCall("complete_task", """{"taskId":"t1"}""")),
                AgentTurn.Reply(text = "已加入待确认"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "吃饭吃完了", AgentTurnContext(day, emptyList()),
        ) { events += it }

        assertEquals(1, session.pendingActions.size)
        assertEquals("complete_task", session.pendingActions.single().toolName)
        assertTrue(events.any { it is AgentConversationEvent.ActionQueued })
    }

    @Test fun futurePlanIsNotTreatedAsAFinishedThing() = runTest {
        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "好", toolCall = AgentToolCall("complete_task", """{"taskId":"t1"}""")),
                AgentTurn.Reply(text = "（脚本结束）"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "明天写完作业", AgentTurnContext(day, emptyList()),
        ) { events += it }

        assertTrue(session.pendingActions.isEmpty())
        val ask = events.filterIsInstance<AgentConversationEvent.TextOutput>().single().text
        assertTrue(ask, ask.contains("没听准"))
    }

    @Test fun timeOnlyMessageAsksWhichTaskWithoutCallingTheModel() = runTest {
        val chat = ScriptedChat(emptyList())
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(
            AgentSessionState(), "帮我排在今天中午一点", AgentTurnContext(day, emptyList()),
        ) { events += it }

        assertEquals(0, chat.index)
        assertEquals(
            listOf<AgentConversationEvent>(AgentConversationEvent.TextOutput("好的，13:00 开始。要排哪个任务？")),
            events,
        )
        assertTrue(session.pendingActions.isEmpty())
        assertEquals(2, session.transcript.size)
        assertTrue(session.transcript.last().content.contains("哪个任务"))
    }

    @Test fun oneWordAnswerInheritsTheClockFromThePreviousQuestion() = runTest {
        val clarifyChat = ScriptedChat(emptyList())
        val askEngine = engine(clarifyChat)
        val asked = askEngine.runTurn(
            AgentSessionState(), "帮我排在今天中午一点", AgentTurnContext(day, emptyList()),
        ) { }

        val chat = ScriptedChat(
            listOf(
                AgentTurn.Reply(text = "排上", toolCall = AgentToolCall("plan_tasks", """{"items":[]}""")),
                AgentTurn.Reply(text = "已加入待确认"),
            ),
        )
        val events = mutableListOf<AgentConversationEvent>()
        val session = engine(chat).runTurn(asked, "吃饭", AgentTurnContext(day, emptyList())) { events += it }

        // 模型看到的是"吃饭 13:00"：时间来自上一轮它自己问的那句话。
        val lastUser = chat.received.first().last { it.role == "user" }
        assertEquals("吃饭 13:00", lastUser.content)
        assertEquals(1, session.pendingActions.size)
    }

    private fun fading() = MemoryState(
        capturedOn = day.minusDays(20), lastEventOn = day.minusDays(9), stabilityDays = 2.0,
        difficulty = 6.0, difficultySeed = 6.0, importance = 5, reviews = 3, lapses = 0, exposures = 3,
    )

    private fun solid() = MemoryState(
        capturedOn = day.minusDays(30), lastEventOn = day.minusDays(2), stabilityDays = 40.0,
        difficulty = 4.0, difficultySeed = 4.0, importance = 3, reviews = 5, lapses = 0, exposures = 5,
    )
}
