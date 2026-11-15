package com.swan1127.repland.domain.ports

import com.swan1127.repland.domain.model.ArrangementCandidate
import com.swan1127.repland.domain.model.AutonomousStatusCommand
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class AutonomyState(
    val enabled: Boolean = true,
    val summary: String? = null,
    val canUndo: Boolean = false,
)

/** One explicit utterance is one atomic operation, including its planning consequences. */
interface AutonomyRepository {
    fun observe(): Flow<AutonomyState>
    /** Emits on planning inputs, not on receipts, so an automatic repair cannot trigger itself forever. */
    fun observeInputRevision(): Flow<String>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun capture(candidates: List<ArrangementCandidate>, date: LocalDate, dateExplicit: Boolean,
                        requestId: String, expectedRevision: String? = null, originalText: String? = null,
                        trustProvided: Boolean = false): String
    suspend fun updateStatus(commands: List<AutonomousStatusCommand>, requestId: String,
                             contextTaskId: String? = null): String
    suspend fun arrange(date: LocalDate, requestId: String, startMinute: Int = 0, endMinute: Int = 1440): String
    /** Keeps the original block and records extra make-up time; never moves the task. */
    suspend fun review(name: String, taskId: String?, progressPercent: Int?, extra: List<ArrangementCandidate>,
                       date: LocalDate, dateExplicit: Boolean, requestId: String,
                       expectedRevision: String? = null, originalText: String? = null): String
    /** Moves an existing task to a spoken day; the sentence date outranks the old plan. */
    suspend fun reschedule(taskId: String, targetDate: LocalDate, startMinute: Int?, durationMinutes: Int?,
                           requestId: String, expectedRevision: String? = null): String
    /** A weekly fixed arrangement, not a one-day task. */
    suspend fun saveRoutine(name: String, weekdays: List<Int>, startMinute: Int, endMinute: Int,
                            requestId: String): String
    /** One day's "don't schedule anything here" window. */
    suspend fun saveBusy(name: String?, date: LocalDate, startMinute: Int, endMinute: Int, requestId: String): String
    suspend fun reconcile(): String?
    suspend fun undo(): String
}
