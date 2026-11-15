package com.swan1127.repland.domain.competition

/** 用户对一场具体赛事的本地追踪：个人域数据，不进团队快照。 */
data class CompetitionTracking(
    val instanceKey: String,
    val title: String,
    val level: String,
    val catalogueKey: String,
    val status: CompetitionTrackStatus,
    val deadlineEpochDay: Long?,
    val remindEnabled: Boolean,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

enum class CompetitionTrackStatus(val label: String) {
    WATCHING("关注"),
    PREPARING("备赛"),
    SIGNED_UP("已报名"),
    FINISHED("已参赛"),
    DROPPED("放弃");

    /** 只有还在进行中的追踪才配截止提醒：参赛完/放弃了的不再响。 */
    val remindersActive: Boolean get() = this != FINISHED && this != DROPPED

    companion object {
        fun from(name: String?): CompetitionTrackStatus =
            entries.firstOrNull { it.name == name } ?: WATCHING
    }
}
