package com.swan1127.repland.domain.team

import com.swan1127.repland.data.team.TeamStateCodec
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 队伍大局与队规：保守底线恒在、策略可开关、规矩可自定义；队长专属写入；序列化无损且底线不可被存档抹掉。 */
class TeamCharterTest {

    private val fixedNow: () -> LocalDateTime = { LocalDateTime.of(2026, 11, 10, 9, 0) }

    @Test
    fun `default charter has five locked and three toggle rules all active`() {
        val charter = TeamCharter()
        assertEquals(5, charter.rules.count { it.kind == CharterRuleKind.LOCKED })
        assertEquals(3, charter.toggleRules.size)
        assertEquals(8, charter.activeRules.size)
        assertTrue(charter.customRules.isEmpty())
    }

    @Test
    fun `toggle can be switched off but locked rules cannot`() {
        val charter = TeamCharter()
        val off = charter.withToggle("toggle-headroom", false)
        assertFalse(off.rules.first { it.id == "toggle-headroom" }.enabled)
        assertEquals(7, off.activeRules.size)
        // 对 LOCKED  id 调用 withToggle 不产生任何效果
        val untouchable = charter.withToggle("lock-confirm", false)
        assertTrue(untouchable.rules.first { it.id == "lock-confirm" }.enabled)
        assertEquals(8, untouchable.activeRules.size)
    }

    @Test
    fun `custom rules add trim reject blank and duplicate id`() {
        val charter = TeamCharter()
            .addCustom("c1", "  每周五晚集中评审  ")
            .addCustom("c1", "重复 id 应被拒绝")
            .addCustom("c2", "   ")
        assertEquals(1, charter.customRules.size)
        assertEquals("每周五晚集中评审", charter.customRules.first().text)
        assertEquals(9, charter.activeRules.size)
    }

    @Test
    fun `remove custom cannot remove locked or toggle`() {
        val charter = TeamCharter().addCustom("c1", "外部引用必须标来源")
        assertEquals(0, charter.removeCustom("c1").customRules.size)
        // 试图删底线/策略：id 命中但 kind 不符，保留
        assertEquals(5, charter.removeCustom("lock-confirm").rules.count { it.kind == CharterRuleKind.LOCKED })
        assertEquals(3, charter.removeCustom("toggle-headroom").toggleRules.size)
    }

    @Test
    fun `goal is trimmed`() {
        assertEquals("冲进国赛", TeamCharter().withGoal("  冲进国赛  ").goal)
    }

    @Test
    fun `charter round trips through codec`() {
        val team = TeamCollabState.create(
            teamId = "t-ch", teamName = "大局队", competitionDeadline = null,
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        team.updateCharter(
            "m-1",
            TeamCharter().withGoal("校赛一等奖").withToggle("toggle-headroom", false).addCustom("c1", "每周五晚集中评审"),
        )
        val restored = TeamStateCodec.fromJson(TeamStateCodec.toJson(team), fixedNow)!!
        assertEquals("校赛一等奖", restored.charter.goal)
        assertFalse(restored.charter.rules.first { it.id == "toggle-headroom" }.enabled)
        assertEquals("每周五晚集中评审", restored.charter.customRules.first().text)
        assertEquals(team.charter.activeRules.size, restored.charter.activeRules.size)
    }

    @Test
    fun `locked baseline survives even if archive tries to erase it`() {
        val team = TeamCollabState.create(
            teamId = "t-ch2", teamName = "底线队", competitionDeadline = null,
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        val json = TeamStateCodec.toJson(team)
        // 模拟被篡改的存档：把 LOCKED 规则全部改成 CUSTOM（试图抹掉底线）
        val poisoned = json.replace("\"LOCKED\"", "\"CUSTOM\"")
        val restored = TeamStateCodec.fromJson(poisoned, fixedNow)!!
        assertEquals(5, restored.charter.rules.count { it.kind == CharterRuleKind.LOCKED })
        assertTrue(restored.charter.activeRules.any { it.id == "lock-confirm" })
    }

    @Test
    fun `old archive without charter falls back to defaults`() {
        // 旧存档只有必填字段、没有 charter 键：反序列化应回落到默认大局（8 条生效），不崩溃。
        val minimal = """{"schema":1,"teamId":"t-old","teamName":"老存档队"}"""
        val restored = TeamStateCodec.fromJson(minimal, fixedNow)
        assertNotNull(restored)
        assertEquals("", restored!!.charter.goal)
        assertEquals(8, restored.charter.activeRules.size)
    }

    @Test
    fun `only captain can update charter and members are notified`() {
        val team = TeamCollabState.create(
            teamId = "t-perm", teamName = "权限队", competitionDeadline = LocalDate.of(2026, 12, 1),
            captain = MemberCard("m-1", "阿岚", roleTag = "队长"),
        )
        team.addMember(MemberCard("m-2", "小柯", roleTag = "队员"))
        val before = team.notifications.size
        val rejected = runCatching { team.updateCharter("m-2", TeamCharter().withGoal("队员越权")) }
        assertTrue(rejected.isFailure)
        assertEquals("", team.charter.goal)
        team.updateCharter("m-1", TeamCharter().withGoal("队长定调"))
        assertEquals("队长定调", team.charter.goal)
        assertTrue(team.notifications.size > before)
        assertTrue(team.notifications.last().message.contains("队伍大局"))
    }
}