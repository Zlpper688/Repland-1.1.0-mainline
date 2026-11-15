package com.swan1127.repland.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.swan1127.repland.domain.competition.CompetitionTracking
import com.swan1127.repland.domain.competition.CompetitionTrackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 一条追踪一行：截止日期是用户可改的本地覆盖，平台窗口刷新不会碰它。 */
@Entity(tableName = "competition_tracking")
data class CompetitionTrackingEntity(
    @PrimaryKey val instanceKey: String,
    val title: String,
    val level: String,
    val catalogueKey: String,
    val status: String,
    val deadlineEpochDay: Long?,
    val remindEnabled: Boolean,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Dao
interface CompetitionTrackingDao {
    @Query("SELECT * FROM competition_tracking ORDER BY updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<CompetitionTrackingEntity>>

    @Query("SELECT * FROM competition_tracking WHERE instanceKey = :instanceKey")
    suspend fun get(instanceKey: String): CompetitionTrackingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: CompetitionTrackingEntity)

    @Query("DELETE FROM competition_tracking WHERE instanceKey = :instanceKey")
    suspend fun remove(instanceKey: String)
}

class RoomCompetitionTrackingRepository(private val dao: CompetitionTrackingDao) :
    com.swan1127.repland.domain.ports.CompetitionTrackingRepository {

    override fun observeAll(): Flow<List<CompetitionTracking>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(instanceKey: String): CompetitionTracking? =
        dao.get(instanceKey)?.toDomain()

    override suspend fun upsert(value: CompetitionTracking) = dao.upsert(value.toEntity())

    override suspend fun remove(instanceKey: String) = dao.remove(instanceKey)

    private fun CompetitionTrackingEntity.toDomain() = CompetitionTracking(
        instanceKey = instanceKey,
        title = title,
        level = level,
        catalogueKey = catalogueKey,
        status = CompetitionTrackStatus.from(status),
        deadlineEpochDay = deadlineEpochDay,
        remindEnabled = remindEnabled,
        note = note,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun CompetitionTracking.toEntity() = CompetitionTrackingEntity(
        instanceKey = instanceKey,
        title = title,
        level = level,
        catalogueKey = catalogueKey,
        status = status.name,
        deadlineEpochDay = deadlineEpochDay,
        remindEnabled = remindEnabled,
        note = note,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
