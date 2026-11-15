package com.swan1127.repland.domain.competition

import kotlin.math.round

enum class AwardLevel(val label: String) {
    INTERNATIONAL("国际"),
    NATIONAL("国家级"),
    PROVINCIAL("省级"),
    MUNICIPAL("市级"),
    SCHOOL("校级"),
}

enum class AwardGrade(val label: String) {
    SPECIAL("特等"),
    FIRST("一等"),
    SECOND("二等"),
    THIRD("三等"),
    OTHER("其他"),
}

/** 登记一条获奖时的输入；目录标志位（是否目录内/外语类）从 CompetitionCatalogEntry 带过来。 */
data class AwardInput(
    val awardYear: Int,
    val level: AwardLevel,
    val grade: AwardGrade,
    val isTeam: Boolean,
    val teamRank: Int,
    val english: Boolean,
    val catalogueCategory: String,
    val nationalInCatalog: Boolean,
    val provincialInCatalog: Boolean,
)

/** 加分预览结果：全部是参考口径，不承诺制度结果。 */
data class AwardPointsPreview(
    val tuimianPoints: Double,
    val creditMin: Double,
    val creditMax: Double,
    val scholarshipNote: String,
    val notes: List<String>,
)

/** 按获奖年度选口径版本再算分：特等=一等、外语×50%、非官方目录降一档、团体前三 85/65/40。 */
object PointsCalculator {

    fun preview(input: AwardInput, seed: SchoolCompetitionSeed): AwardPointsPreview {
        val rule = seed.ruleVersionFor(input.awardYear)
            ?: return AwardPointsPreview(0.0, 0.0, 0.0, "", listOf(noRuleNote(input.awardYear, seed)))
        val notes = mutableListOf<String>()
        var grade = input.grade
        if (grade == AwardGrade.SPECIAL && rule.tuimian.specialEqualsFirst) {
            grade = AwardGrade.FIRST
            notes += "特等奖按一等奖计"
        }
        var base = basePoints(input, grade, rule, notes)
        if (base > 0 && input.english && rule.tuimian.englishHalfWeight) {
            base *= 0.5
            notes += "外语类竞赛按 50% 计分"
        }
        if (base > 0 && input.isTeam) {
            val ratio = rule.tuimian.teamRatios.getOrNull(input.teamRank - 1)
            if (ratio == null) {
                base = 0.0
                notes += "团体排名须在前 ${rule.tuimian.teamRatios.size} 名内才计分（当前第 ${input.teamRank} 名）"
            } else if (ratio < 1.0) {
                base *= ratio
                notes += "团体第 ${input.teamRank} 名按 ${(ratio * 100).toInt()}% 计分"
            }
        }
        notes += "口径：${rule.scope}"
        if (base > 0) {
            notes += "创新学分 ${rule.credit.minPerAward}~${rule.credit.maxPerAward}/项，累计修满 ${rule.credit.requiredTotal} 学分（${rule.credit.deadlineNote}）"
        }
        return AwardPointsPreview(
            tuimianPoints = round(base * 10.0) / 10.0,
            creditMin = rule.credit.minPerAward,
            creditMax = rule.credit.maxPerAward,
            scholarshipNote = scholarshipNote(input, grade, rule),
            notes = notes,
        )
    }

    private fun basePoints(
        input: AwardInput,
        grade: AwardGrade,
        rule: PointsRuleVersion,
        notes: MutableList<String>,
    ): Double {
        val t = rule.tuimian
        return when (input.level) {
            AwardLevel.INTERNATIONAL, AwardLevel.NATIONAL -> {
                var pts = when (grade) {
                    AwardGrade.FIRST -> t.nationalFirst
                    AwardGrade.SECOND -> t.nationalSecond
                    AwardGrade.THIRD -> t.nationalThird
                    else -> {
                        notes += "奖项等级不在一等/二等/三等计分范围"
                        0.0
                    }
                }
                if (pts > 0 && !input.nationalInCatalog && t.unofficialCatalogDropOneTier) {
                    notes += "该赛事不在国家竞赛目录内，按规则降一档计分"
                    pts = when (grade) {
                        AwardGrade.FIRST -> t.nationalSecond
                        AwardGrade.SECOND -> t.nationalThird
                        else -> 0.0
                    }
                }
                pts
            }

            AwardLevel.PROVINCIAL -> {
                var pts = when (grade) {
                    AwardGrade.FIRST -> t.provincialFirst
                    AwardGrade.SECOND -> t.provincialSecond
                    AwardGrade.THIRD -> {
                        notes += "省级三等奖未列入该口径表，按 0 计"
                        0.0
                    }

                    else -> {
                        notes += "奖项等级不在一等/二等/三等计分范围"
                        0.0
                    }
                }
                if (pts > 0 && !input.provincialInCatalog && t.unofficialCatalogDropOneTier) {
                    notes += "该赛事不在省级竞赛目录内，按规则降一档计分"
                    pts = if (grade == AwardGrade.FIRST) t.provincialSecond else 0.0
                }
                pts
            }

            AwardLevel.MUNICIPAL, AwardLevel.SCHOOL -> {
                notes += "校级/市级奖项不在推免加分范围"
                0.0
            }
        }
    }

    private fun scholarshipNote(input: AwardInput, grade: AwardGrade, rule: PointsRuleVersion): String = when {
        input.catalogueCategory == "B" && rule.scholarship.bClassWorkloadOnly -> "B类赛事不计奖学金，只计工作量"
        input.catalogueCategory == "A" &&
            (input.level == AwardLevel.NATIONAL || input.level == AwardLevel.INTERNATIONAL) &&
            grade == AwardGrade.FIRST && rule.scholarship.aNationalFirstCny > 0 ->
            "A类国家级一等奖奖学金参考 ¥${rule.scholarship.aNationalFirstCny}"
        else -> ""
    }

    private fun noRuleNote(year: Int, seed: SchoolCompetitionSeed): String {
        val earliest = seed.ruleVersions.minOfOrNull { it.effectiveFrom }
        return if (earliest == null) "种子中没有口径版本，无法计算加分"
        else "获奖年份 $year 早于现有口径（自 $earliest 年起），无法计算"
    }
}
