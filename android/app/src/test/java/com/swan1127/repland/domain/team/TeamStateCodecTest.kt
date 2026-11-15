package com.swan1127.repland.domain.team

import com.swan1127.repland.data.team.TeamDemoSeed
import com.swan1127.repland.data.team.TeamStateCodec
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 整份快照 JSON 序列化 → 反序列化往返：字段、枚举、日期必须无损（T2 持久化契约）。 */
class TeamStateCodecTest {

    private val fixedNow: () -> LocalDateTime = { LocalDateTime.of(2026, 10, 28, 9, 0) }

    @Test
    fun `demo seed round trip preserves all collections`() {
        val original = TeamDemoSeed.build(LocalDate.of(2026, 10, 26))
        val json = TeamStateCodec.toJson(original)
        val restored = TeamStateCodec.fromJson(json, fixedNow)

        assertNotNull(restored)
        restored!!
        assertEquals(original.teamId, restored.teamId)
        assertEquals(original.teamName, restored.teamName)
        assertEquals(original.competitionDeadline, restored.competitionDeadline)
        assertEquals(original.members.map { it.memberId }, restored.members.map { it.memberId })
        assertEquals(original.members.map { it.permissions }, restored.members.map { it.permissions })
        assertEquals(original.members.map { it.weeklyAvailability }, restored.members.map { it.weeklyAvailability })
        assertEquals(original.phases, restored.phases)
        assertEquals(original.tasks, restored.tasks)
        assertEquals(original.blocks, restored.blocks)
        assertEquals(original.ledger, restored.ledger)
        assertEquals(original.objections, restored.objections)
        assertEquals(original.transfers, restored.transfers)
        assertEquals(original.notifications, restored.notifications)
        assertNotNull(restored.profileCard)
        assertEquals(original.profileCard?.competitionName, restored.profileCard?.competitionName)
        assertEquals(original.profileCard?.extras, restored.profileCard?.extras)
    }

    @Test
    fun `profile card slot groups survive round trip`() {
        val original = TeamDemoSeed.build(LocalDate.of(2026, 10, 26))
        val restored = TeamStateCodec.fromJson(TeamStateCodec.toJson(original), fixedNow)!!
        val originalFields = original.profileCard?.fields
        val restoredFields = restored.profileCard?.fields
        assertEquals(originalFields?.keys, restoredFields?.keys)
        originalFields?.forEach { (group, fields) -> assertEquals(fields, restoredFields?.get(group)) }
    }

    @Test
    fun `live state with runtime activity round trips`() {
        val team = TeamCollabState.create(
            teamId = "t-rt", teamName = "往返测试队", competitionDeadline = LocalDate.of(2026, 12, 1),
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        team.addMember(MemberCard("m-2", "小柯", roleTag = "技术"))
        team.setBaseline(
            "m-1",
            listOf(Phase("p1", "前期", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 5))),
            listOf(
                TeamTask("k1", "调研", TaskTier.MID_TERM, "p1", estimatedHours = 8.0, createdBy = "m-1"),
                TeamTask("k2", "提交材料", TaskTier.MID_TERM, "p1", estimatedHours = 2.0, hardDeadline = LocalDate.of(2026, 11, 30), deadlineHour = 18, createdBy = "m-1"),
            ),
        )
        team.blocks += PersonalBlock(
            "b1", "m-2", LocalDate.of(2026, 10, 27), 18 * 60, 20 * 60,
            "t-rt", "k1", BlockVisibility.TEAM_VISIBLE, "调研",
        )
        team.blocks += PersonalBlock("b2", "m-2", LocalDate.of(2026, 10, 27), 21 * 60, 22 * 60, null, null, BlockVisibility.PRIVATE_BUSY, "私事")
        team.transfer("m-1", "k1", "m-2")
        team.objections += Objection("obj-1", "k1", "m-2", "那周我有别的队内事务", LocalDateTime.of(2026, 10, 27, 12, 0))
        team.ledger += LedgerEntry("le-1", LedgerKind.TRANSFER, "k1", "m-1", LocalDateTime.of(2026, 10, 27, 12, 0), "转派 k1 → 小柯")
        team.recordFinance("m-1", 12_500, "打印材料")

        val restored = TeamStateCodec.fromJson(TeamStateCodec.toJson(team), fixedNow)!!
        assertEquals(team.tasks, restored.tasks)
        assertEquals(team.blocks, restored.blocks)
        assertEquals(team.transfers, restored.transfers)
        assertEquals(team.objections, restored.objections)
        assertEquals(team.ledger, restored.ledger)
        assertTrue(restored.ledger.any { it.kind == LedgerKind.FINANCE && it.amountCents == 12_500L })
    }

    @Test
    fun `unknown enum name is dropped instead of crashing`() {
        val team = TeamDemoSeed.build(LocalDate.of(2026, 10, 26))
        assertEquals(4, team.blocks.size) // 2 个团队可见块 + 2 个私有忙块
        val poisoned = TeamStateCodec.toJson(team).replace("\"TEAM_VISIBLE\"", "\"GHOST_VISIBILITY\"")
        val restored = TeamStateCodec.fromJson(poisoned, fixedNow)
        assertNotNull(restored)
        // 未知可见性回退默认 TEAM_VISIBLE（不丢块、不崩溃）；私有忙块原样保留
        assertEquals(4, restored!!.blocks.size)
        assertEquals(2, restored.blocks.count { it.visibility == BlockVisibility.PRIVATE_BUSY })
        assertEquals(2, restored.blocks.count { it.visibility == BlockVisibility.TEAM_VISIBLE })
        assertEquals(5, restored.members.size)
        assertEquals(6, restored.tasks.size)
    }

    @Test
    fun `stage metadata round trips and legacy archives fall back to custom`() {
        val team = TeamCollabState.create(
            teamId = "t-stage", teamName = "环节队", competitionDeadline = null,
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        team.setBaseline(
            "m-1",
            CompetitionStages.instantiate(CompetitionStages.mathModeling, LocalDate.of(2026, 11, 9)),
            emptyList(),
        )
        val json = TeamStateCodec.toJson(team)
        val restored = TeamStateCodec.fromJson(json, fixedNow)!!
        assertEquals(team.phases, restored.phases)
        assertTrue(restored.phases.any { it.kind == StageKind.WRITE && it.isGate && it.deliverables.isNotEmpty() })
        assertTrue("关键时点（含硬时点）必须随阶段一起存下", restored.phases.any { phase -> phase.moments.any { it.hard } })

        // 旧存档没有环节字段：读成自定义阶段，不丢阶段也不崩
        val root = org.json.JSONObject(json)
        val storedPhases = root.getJSONArray("phases")
        for (i in 0 until storedPhases.length()) {
            storedPhases.getJSONObject(i).apply { remove("kind"); remove("isGate") }
        }
        val restoredLegacy = TeamStateCodec.fromJson(root.toString(), fixedNow)!!
        assertEquals(team.phases.size, restoredLegacy.phases.size)
        assertTrue(restoredLegacy.phases.all { it.kind == StageKind.CUSTOM && !it.isGate })
    }

    @Test
    fun `notification card metadata survives round trip and old archives without it`() {
        val team = TeamCollabState.create(
            teamId = "t-card", teamName = "卡片队", competitionDeadline = null,
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        team.notifications += TeamNotification(
            "ntf-1", NotificationChannel.PUSH_AND_FEISHU, "队长", "排期草案待确认：1 段安排",
            LocalDateTime.of(2026, 10, 28, 9, 0),
            card = NotificationCardRef(CARD_DRAFT_CONFIRM, "d-123", 3),
        )
        team.notifications += TeamNotification(
            "ntf-2", NotificationChannel.APP_ONLY, "全员", "纯文本通知",
            LocalDateTime.of(2026, 10, 28, 9, 1),
        )
        val restored = TeamStateCodec.fromJson(TeamStateCodec.toJson(team), fixedNow)!!
        assertEquals(team.notifications, restored.notifications)
        assertEquals("d-123", restored.notifications[0].card?.refId)
        assertEquals(3, restored.notifications[0].card?.revision)
        assertEquals(null, restored.notifications[1].card)
    }
}
