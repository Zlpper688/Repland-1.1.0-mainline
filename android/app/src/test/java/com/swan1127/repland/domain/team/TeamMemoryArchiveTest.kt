package com.swan1127.repland.domain.team

import com.swan1127.repland.data.team.HumanProfileCodec
import com.swan1127.repland.data.team.TeamStateCodec
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5 记忆体系单测（TEAM_COLLAB_DESIGN.md §12/§16）：
 * 团队域权限门控 / 参赛归档与只读冻结 / 个人域沉淀与导出 / 编解码往返与旧档兼容。
 */
class TeamMemoryArchiveTest {

    private val now: () -> LocalDateTime = { LocalDateTime.of(2026, 10, 28, 9, 0) }

    private fun team(): TeamCollabState {
        val state = TeamCollabState.create(
            teamId = "t-mem",
            teamName = "记忆测试队",
            competitionDeadline = LocalDate.of(2026, 12, 20),
            captain = MemberCard("m-cap", "阿岚", roleTag = "队长"),
            now = now,
        )
        state.addMember(MemberCard("m-dev", "小柯", roleTag = "技术"))
        state.setBaseline(
            "m-cap",
            listOf(Phase("p1", "前期", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 15))),
            listOf(
                TeamTask("a", "调研", TaskTier.MID_TERM, "p1", estimatedHours = 4.0, createdBy = "m-cap"),
                TeamTask("b", "原型", TaskTier.MID_TERM, "p1", estimatedHours = 8.0, createdBy = "m-cap"),
            ),
        )
        return state
    }

    // ---------- 团队域：写入受「记忆库写入」门控，全队可读 ----------

    @Test
    fun `team memory write is gated by memory-write permission and readable by all`() {
        val state = team()
        val denied = runCatching { state.addTeamMemory("m-dev", TeamMemoryKind.MEETING, "例会定了下周提测") }
        assertTrue(denied.isFailure)
        assertTrue(denied.exceptionOrNull()?.message.orEmpty().contains("记忆库写入"))

        val writable = TeamCollabState.create(
            teamId = "t-mem2", teamName = "授权队", competitionDeadline = null,
            captain = MemberCard("m-cap", "阿岚", roleTag = "队长"), now = now,
        )
        writable.addMember(MemberCard("m-w", "小柯", roleTag = "技术", permissions = setOf(TeamPermission.MEMORY_WRITE)))
        val entry = writable.addTeamMemory("m-w", TeamMemoryKind.MEETING, "例会结论：下周提测")
        assertEquals(1, writable.memories.size)
        assertEquals(entry.content, writable.memories.single().content)
        // 全队可读：任何成员的读取路径都是同一份 memories
        assertEquals("例会结论：下周提测", writable.memories.single().content)
        assertTrue(writable.member("m-cap").hasPermission(TeamPermission.MEMORY_WRITE))
        writable.removeTeamMemory("m-w", entry.memoryId)
        assertTrue(writable.memories.isEmpty())
        // 无权限者连删除也不行
        val noRemove = TeamCollabState.create(
            teamId = "t-mem3", teamName = "第三队", competitionDeadline = null,
            captain = MemberCard("m-cap", "阿岚", roleTag = "队长"), now = now,
        )
        val kept = noRemove.addTeamMemory("m-cap", TeamMemoryKind.PROFILE, "本队主打数据可视化")
        noRemove.addMember(MemberCard("m-x", "小满", roleTag = "设计"))
        assertTrue(runCatching { noRemove.removeTeamMemory("m-x", kept.memoryId) }.isFailure)
    }

    // ---------- 参赛归档：权限门控 + 只读冻结 ----------

    @Test
    fun `archive requires dissolve-archive permission then freezes the team`() {
        val state = team()
        assertTrue(runCatching { state.archiveTeam("m-dev", "校赛一等奖") }.isFailure)

        // 先造一条可异议的转派（归档前）
        state.transfer("m-cap", "a", "m-dev")
        val entry = state.archiveTeam("m-cap", "校赛一等奖")
        assertTrue(state.archived)
        assertEquals("校赛一等奖", entry.result)
        assertNotNull(state.archivedAt)

        // 只读冻结：一切写仓库动作被拒，且错误信息指向归档只读
        fun deniedBecauseArchived(block: () -> Unit) {
            val outcome = runCatching(block)
            assertTrue(outcome.isFailure)
            assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("只读"))
        }
        deniedBecauseArchived { state.setBaseline("m-cap", emptyList(), emptyList()) }
        deniedBecauseArchived { state.transfer("m-cap", "b", "m-dev") }
        deniedBecauseArchived { state.cancelTask("m-cap", "b") }
        deniedBecauseArchived { state.addTeamMemory("m-cap", TeamMemoryKind.PROFILE, "x") }
        deniedBecauseArchived { state.recordFinance("m-cap", 1000, "报销") }
        deniedBecauseArchived { state.addExternalLink("m-cap", "b", "https://example.com") }
        deniedBecauseArchived { state.addMember(MemberCard("m-new", "新人")) }
        deniedBecauseArchived { state.adoptProfileCard(CompetitionProfileCard("新比赛", emptyMap())) }
        deniedBecauseArchived { state.updateTask(state.task("b").copy(status = TeamTaskStatus.DONE)) }
        deniedBecauseArchived { state.commitDraft("m-cap", TeamPlanDraft(emptyList())) }
        deniedBecauseArchived { state.raiseObjection("m-dev", "a", "不接") }
        deniedBecauseArchived { state.archiveTeam("m-cap", "再来一次") }
    }

    @Test
    fun `archive entry captures result stats ledger links and export text`() {
        val state = team()
        state.transfer("m-cap", "a", "m-dev")
        state.addExternalLink("m-cap", "b", "https://example.com/demo")
        state.updateTask(state.task("a").copy(status = TeamTaskStatus.DONE))
        val entry = state.archiveTeam("m-cap", "校赛一等奖")
        assertEquals("记忆测试队", entry.teamName)
        assertEquals(listOf("https://example.com/demo"), entry.workLinks)
        assertEquals(1, entry.stats["tasksDone"])
        assertEquals(2, entry.stats["tasks"])
        assertEquals(2, entry.stats["members"])
        val kinds = entry.finalLedger.map { it.kind }
        assertTrue(kinds.contains(LedgerKind.TRANSFER))
        assertTrue(kinds.contains(LedgerKind.EXTERNAL_LINK))
        val text = entry.exportText()
        assertTrue(text.contains("校赛一等奖"))
        assertTrue(text.contains("https://example.com/demo"))
        assertTrue(text.contains("最终台账"))
        assertTrue(text.contains("记忆测试队"))
    }

    // ---------- 个人域：跨队沉淀、去重、导出 ----------

    @Test
    fun `human profile deposits archives across teams and dedupes facts`() {
        val profile = HumanProfileMemory()
        val first = team().archiveTeam("m-cap", "校赛一等奖")
        val secondTeam = TeamCollabState.create(
            teamId = "t-other", teamName = "另一支队", competitionDeadline = null,
            captain = MemberCard("m-cap2", "老白", roleTag = "队长"), now = now,
        )
        val second = secondTeam.archiveTeam("m-cap2", "省赛三等奖")

        assertTrue(profile.depositArchive(first))
        assertTrue(profile.depositArchive(second))
        assertFalse(profile.depositArchive(first)) // 幂等
        assertEquals(2, profile.archives.size)

        assertNotNull(profile.addFact("擅长 Kotlin 与数据可视化", now(), first.teamId))
        assertNull(profile.addFact("擅长 Kotlin 与数据可视化", now(), second.teamId)) // 同文去重
        assertEquals(1, profile.facts.size)
        assertNotNull(profile.addFact("喜欢把周报写成清单", now()))
        assertTrue(profile.removeFact(profile.facts.first().factId))
        assertEquals(1, profile.facts.size)

        val text = profile.exportText()
        assertTrue(text.contains("记忆测试队"))
        assertTrue(text.contains("另一支队"))
        assertTrue(text.contains("校赛一等奖"))
        assertNotNull(profile.exportArchiveText(first.archiveId))
        assertNull(profile.exportArchiveText("missing"))
    }

    // ---------- 编解码往返与旧档兼容 ----------

    @Test
    fun `memories and archive flags round trip through team codec`() {
        val state = team()
        state.addMember(MemberCard("m-w", "小柯", permissions = setOf(TeamPermission.MEMORY_WRITE)))
        state.addTeamMemory("m-cap", TeamMemoryKind.MEETING, "结论：下周提测")
        state.addTeamMemory("m-w", TeamMemoryKind.PROFILE, "本队主打数据可视化")
        state.archiveTeam("m-cap", "校赛一等奖")

        val restored = TeamStateCodec.fromJson(TeamStateCodec.toJson(state), now)!!
        assertEquals(state.memories, restored.memories)
        assertTrue(restored.archived)
        assertEquals(state.archivedAt, restored.archivedAt)

        // 旧档（T4 payload 无 memories/archived 字段）兼容：不崩、默认可写
        val source = JSONObject(TeamStateCodec.toJson(team()))
        val legacyJson = JSONObject().apply {
            source.keys().forEach { key ->
                if (key != "memories" && key != "archived") put(key, source.get(key))
            }
        }.toString()
        val legacy = TeamStateCodec.fromJson(legacyJson, now)!!
        assertTrue(legacy.memories.isEmpty())
        assertFalse(legacy.archived)
    }

    @Test
    fun `human profile codec round trips facts and archives`() {
        val profile = HumanProfileMemory()
        val archive = team().archiveTeam("m-cap", "校赛一等奖")
        profile.depositArchive(archive)
        profile.addFact("擅长 Kotlin", now(), archive.teamId)
        profile.addFact("路演控场", now())

        val restored = HumanProfileCodec.fromJson(HumanProfileCodec.toJson(profile))!!
        assertEquals(profile.facts, restored.facts)
        assertEquals(profile.archives, restored.archives)
        assertEquals(archive.exportText(), restored.exportArchiveText(archive.archiveId))
    }
}
