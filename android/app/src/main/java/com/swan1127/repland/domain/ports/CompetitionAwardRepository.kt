package com.swan1127.repland.domain.ports

import com.swan1127.repland.domain.competition.CompetitionAward
import kotlinx.coroutines.flow.Flow

interface CompetitionAwardRepository {
    fun observeAll(): Flow<List<CompetitionAward>>

    suspend fun get(awardId: String): CompetitionAward?

    suspend fun upsert(value: CompetitionAward)

    suspend fun remove(awardId: String)
}
