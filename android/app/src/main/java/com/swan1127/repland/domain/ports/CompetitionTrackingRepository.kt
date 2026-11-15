package com.swan1127.repland.domain.ports

import com.swan1127.repland.domain.competition.CompetitionTracking
import kotlinx.coroutines.flow.Flow

interface CompetitionTrackingRepository {
    fun observeAll(): Flow<List<CompetitionTracking>>

    suspend fun get(instanceKey: String): CompetitionTracking?

    suspend fun upsert(value: CompetitionTracking)

    suspend fun remove(instanceKey: String)
}
