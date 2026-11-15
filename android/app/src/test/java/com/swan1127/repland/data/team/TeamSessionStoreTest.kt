package com.swan1127.repland.data.team

import com.swan1127.repland.data.room.TeamSessionDao
import com.swan1127.repland.data.room.TeamSessionEntity
import com.swan1127.repland.data.room.TeamSettingsEntity
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T3 持久化契约：整份快照存 Room（一队一行）+ 激活指针；T2 的 team-session.json 可无损导入。 */
class TeamSessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 内存版 DAO：仅覆盖 store 用到的五个操作，语义与 Room 一致（REPLACE = 整行覆盖）。 */
    private class FakeTeamSessionDao : TeamSessionDao {
        val sessions = mutableMapOf<String, TeamSessionEntity>()
        val settings = mutableMapOf<String, TeamSettingsEntity>()

        override suspend fun getAll(): List<TeamSessionEntity> =
            sessions.values.sortedByDescending { it.updatedAtEpochMillis }

        override suspend fun get(teamId: String): TeamSessionEntity? = sessions[teamId]

        override suspend fun upsert(value: TeamSessionEntity) {
            sessions[value.teamId] = value
        }

        override suspend fun delete(teamId: String) {
            sessions.remove(teamId)
        }

        override suspend fun getSetting(id: String): TeamSettingsEntity? = settings[id]

        override suspend fun putSetting(value: TeamSettingsEntity) {
            settings[value.id] = value
        }
    }

    private fun newTeam(id: String, name: String) = com.swan1127.repland.domain.team.TeamCollabState.create(
        teamId = id,
        teamName = name,
        competitionDeadline = LocalDate.of(2026, 12, 14),
        captain = com.swan1127.repland.domain.team.MemberCard("m-captain", "阿岚", roleTag = "队长"),
        now = { java.time.LocalDateTime.now() },
    )

    @Test
    fun `legacy json snapshot is imported on first load`() = runBlocking {
        val file = tmp.newFile("team-session.json")
        file.writeText(TeamStateCodec.toJson(TeamDemoSeed.build(LocalDate.of(2026, 10, 26))))
        val dao = FakeTeamSessionDao()
        val store = TeamSessionStore(dao, file)

        store.load()

        assertNotNull(store.snapshot.value)
        assertEquals("t-demo", store.snapshot.value!!.state.teamId)
        assertTrue(dao.sessions.containsKey("t-demo"))
        assertEquals("t-demo", dao.settings.getValue("active").activeTeamId)
        assertEquals(listOf("t-demo"), store.teams.value.map { it.teamId })
    }

    @Test
    fun `legacy import keeps room row when team already exists`() = runBlocking {
        val dao = FakeTeamSessionDao()
        val store = TeamSessionStore(dao, tmp.newFile("team-session.json"))
        store.save(newTeam("t-demo", "Room 里已有"))
        val roomPayload = dao.sessions.getValue("t-demo").payload

        // 覆盖 legacy 文件为同 teamId 的 demo 快照 → 同 teamId 以 Room 为准
        val file = tmp.newFile("team-session2.json")
        file.writeText(TeamStateCodec.toJson(TeamDemoSeed.build(LocalDate.of(2026, 10, 26))))
        val reloaded = TeamSessionStore(dao, file)
        reloaded.load()

        assertEquals("Room 里已有", reloaded.snapshot.value!!.state.teamName)
        assertEquals(roomPayload, dao.sessions.getValue("t-demo").payload)
    }

    @Test
    fun `save keeps multiple teams and active pointer follows latest save`() = runBlocking {
        val dao = FakeTeamSessionDao()
        val store = TeamSessionStore(dao, null)

        store.save(newTeam("t-a", "队伍A"))
        store.save(newTeam("t-b", "队伍B"))

        assertEquals("t-b", store.snapshot.value!!.state.teamId)
        // 同毫秒保存时并列（排序稳定性不保证严格倒序），只断言集合与激活指针
        assertEquals(setOf("t-a", "t-b"), store.teams.value.map { it.teamId }.toSet())

        assertTrue(store.switchTo("t-a"))
        assertEquals("t-a", store.snapshot.value!!.state.teamId)
        assertEquals("t-a", dao.settings.getValue("active").activeTeamId)
        assertEquals(2, store.teams.value.size)
    }

    @Test
    fun `switchTo unknown or corrupt team returns false and keeps current`() = runBlocking {
        val dao = FakeTeamSessionDao()
        val store = TeamSessionStore(dao, null)
        store.save(newTeam("t-a", "队伍A"))

        assertFalse(store.switchTo("t-missing"))
        assertEquals("t-a", store.snapshot.value!!.state.teamId)

        dao.sessions["t-b"] = dao.sessions.getValue("t-a").copy(teamId = "t-b", payload = "{broken json")
        assertFalse(store.switchTo("t-b"))
        assertEquals("t-a", store.snapshot.value!!.state.teamId)
    }

    @Test
    fun `clear removes active team and falls back to most recent`() = runBlocking {
        val dao = FakeTeamSessionDao()
        val store = TeamSessionStore(dao, null)
        store.save(newTeam("t-a", "队伍A"))
        store.save(newTeam("t-b", "队伍B"))

        store.clear()

        assertEquals("t-a", store.snapshot.value!!.state.teamId) // 回退到最近更新的剩余队伍
        assertNull(dao.sessions["t-b"])
        assertEquals(listOf("t-a"), store.teams.value.map { it.teamId })

        store.clear()
        assertNull(store.snapshot.value)
        assertTrue(store.teams.value.isEmpty())
    }
}
