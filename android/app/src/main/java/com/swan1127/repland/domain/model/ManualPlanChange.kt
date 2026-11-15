package com.swan1127.repland.domain.model

import java.time.LocalDate

/** A single user-requested operation. Consent is bound to a freshly read planning revision. */
sealed interface ManualPlanChange {
    data class Place(val taskId: String, val date: LocalDate, val start: Int, val end: Int, val track: String,
        /** Tasks scheduled after the drop point shift later to make room; the day must absorb them. */
        val delayFollowing: Boolean = false,
        /** 放置即带上的提醒开关与提前量；提前量为空用全局默认。 */
        val reminderEnabled: Boolean = false,
        val leadMinutes: Int? = null) : ManualPlanChange
    data class Move(val segmentId: String, val start: Int, val end: Int, val track: String,
        val delayFollowing: Boolean = false,
        /** null 就留在原来那天；跨天挪动只需换一个日期，段上的其他内容跟着走。 */
        val date: LocalDate? = null) : ManualPlanChange
    data class Remove(val segmentId: String) : ManualPlanChange
    data object Clear : ManualPlanChange
}

class PlanChangeConfirmationRequired(val revision: String, message: String) : IllegalArgumentException(message)

data class PendingManualPlanChange(val change: ManualPlanChange, val revision: String, val message: String)
