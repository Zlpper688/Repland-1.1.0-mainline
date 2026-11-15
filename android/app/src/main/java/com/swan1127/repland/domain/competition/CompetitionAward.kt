package com.swan1127.repland.domain.competition

/** 一条获奖登记：个人履历线，只由用户自己写；竞赛卡履历仍只从真实队伍同步（两条线不混）。 */
data class CompetitionAward(
    val awardId: String,
    val instanceKey: String,
    val title: String,
    val catalogueKey: String,
    val level: AwardLevel,
    val grade: AwardGrade,
    val awardYear: Int,
    val isTeam: Boolean,
    val teamRank: Int,
    val teammates: String,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
