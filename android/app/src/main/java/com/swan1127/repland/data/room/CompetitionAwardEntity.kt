package com.swan1127.repland.data.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.swan1127.repland.domain.competition.AwardGrade
import com.swan1127.repland.domain.competition.AwardLevel
import com.swan1127.repland.domain.competition.CompetitionAward
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "competition_awards")
data class CompetitionAwardEntity(
    @PrimaryKey val awardId: String,
    val instanceKey: String,
    val title: String,
    val catalogueKey: String,
    val level: String,
    val grade: String,
    val awardYear: Int,
    val isTeam: Boolean,
    val teamRank: Int,
    val teammates: String,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Dao
interface CompetitionAwardDao {
    @Query("SELECT * FROM competition_awards ORDER BY awardYear DESC, updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<CompetitionAwardEntity>>

    @Query("SELECT * FROM competition_awards WHERE awardId = :awardId")
    suspend fun get(awardId: String): CompetitionAwardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: CompetitionAwardEntity)

    @Query("DELETE FROM competition_awards WHERE awardId = :awardId")
    suspend fun remove(awardId: String)
}

class RoomCompetitionAwardRepository(private val dao: CompetitionAwardDao) :
    com.swan1127.repland.domain.ports.CompetitionAwardRepository {

    override fun observeAll(): Flow<List<CompetitionAward>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(awardId: String): CompetitionAward? = dao.get(awardId)?.toDomain()

    override suspend fun upsert(value: CompetitionAward) = dao.upsert(value.toEntity())

    override suspend fun remove(awardId: String) = dao.remove(awardId)

    private fun CompetitionAwardEntity.toDomain() = CompetitionAward(
        awardId = awardId,
        instanceKey = instanceKey,
        title = title,
        catalogueKey = catalogueKey,
        level = runCatching { AwardLevel.valueOf(level) }.getOrDefault(AwardLevel.NATIONAL),
        grade = runCatching { AwardGrade.valueOf(grade) }.getOrDefault(AwardGrade.OTHER),
        awardYear = awardYear,
        isTeam = isTeam,
        teamRank = teamRank,
        teammates = teammates,
        note = note,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun CompetitionAward.toEntity() = CompetitionAwardEntity(
        awardId = awardId,
        instanceKey = instanceKey,
        title = title,
        catalogueKey = catalogueKey,
        level = level.name,
        grade = grade.name,
        awardYear = awardYear,
        isTeam = isTeam,
        teamRank = teamRank,
        teammates = teammates,
        note = note,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
