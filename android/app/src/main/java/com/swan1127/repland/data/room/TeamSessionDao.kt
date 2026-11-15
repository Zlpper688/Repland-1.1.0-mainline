package com.swan1127.repland.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * team 域 Room 落地（TEAM_COLLAB_DESIGN.md §13/§16，T3）。
 * 一队一行：payload 是 TeamStateCodec 整份快照 JSON（schema 1），本地 Room 是主副本，
 * 后端同步（表级增量拉取）落地后可按行比对 updatedAt。
 */
@Entity(tableName = "team_sessions")
data class TeamSessionEntity(
    @PrimaryKey val teamId: String,
    val teamName: String,
    val payload: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** 单行键值设置：当前激活队伍（§16 多队：一账号可建多队伍，基线隔离）。 */
@Entity(tableName = "team_settings")
data class TeamSettingsEntity(
    @PrimaryKey val id: String,
    val activeTeamId: String,
    val updatedAtEpochMillis: Long,
)

@Dao
interface TeamSessionDao {
    @Query("SELECT * FROM team_sessions ORDER BY updatedAtEpochMillis DESC")
    suspend fun getAll(): List<TeamSessionEntity>

    @Query("SELECT * FROM team_sessions WHERE teamId = :teamId")
    suspend fun get(teamId: String): TeamSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: TeamSessionEntity)

    @Query("DELETE FROM team_sessions WHERE teamId = :teamId")
    suspend fun delete(teamId: String)

    @Query("SELECT * FROM team_settings WHERE `id` = :id")
    suspend fun getSetting(id: String): TeamSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(value: TeamSettingsEntity)
}
