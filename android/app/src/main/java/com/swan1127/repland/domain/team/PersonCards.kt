package com.swan1127.repland.domain.team

import java.time.DayOfWeek

/** 资料卡（个人域）：把「负责说明」延长成可复用的个人履历。
 * 默认卡只有一张，组队前就能写；竞赛卡只能由实际加入过的队伍生成——不能手填，
 * 所以它既是履历也是「不是吹嘘」的证据。公开卡可随邀请发出去，配合空闲时段按需求匹配。
 */
fun availabilityText(availability: Map<DayOfWeek, List<FreeWindow>>): String =
    availability.entries.sortedBy { it.key.value }.joinToString("；") { (day, windows) ->
        val label = "周" + "一二三四五六日"[day.value - 1]
        label + windows.sortedBy { it.startMinute }.joinToString("、") { window ->
            "%02d:%02d-%02d:%02d".format(
                window.startMinute / 60, window.startMinute % 60,
                window.endMinute / 60, window.endMinute % 60,
            )
        }
    }

enum class CardVisibility(val label: String) {
    PRIVATE("仅自己"),
    PUBLIC("公开可邀"),
}

enum class PersonCardKind { DEFAULT, COMPETITION }

data class PersonCard(
    val cardId: String,
    val kind: PersonCardKind,
    val name: String,
    /** 默认卡：一句话定位；竞赛卡：我在这场比赛里做了什么。 */
    val headline: String = "",
    /** 能力关键词 / 分工，匹配时与需求做确定性重叠。 */
    val abilities: String = "",
    /** 团队预期：想找什么样的队友、希望怎么配合（默认卡）。 */
    val expectation: String = "",
    val roleTag: String? = null,
    val teamId: String? = null,
    val teamName: String? = null,
    val competitionName: String? = null,
    /** 竞赛结果或收获（可选，归档后自动带出）。 */
    val result: String = "",
    val teammateCount: Int = 0,
    val weeklyAvailability: Map<DayOfWeek, List<FreeWindow>> = emptyMap(),
    val dailyCapacityHours: Double = 3.0,
    val visibility: CardVisibility = CardVisibility.PRIVATE,
    val updatedAtEpochMillis: Long = 0L,
) {
    val isDefault: Boolean get() = kind == PersonCardKind.DEFAULT
    val isCompetition: Boolean get() = kind == PersonCardKind.COMPETITION
    val isPublic: Boolean get() = visibility == CardVisibility.PUBLIC

    val title: String
        get() = if (isDefault) name.ifBlank { "我" } else competitionName?.takeIf { it.isNotBlank() }
            ?: teamName?.takeIf { it.isNotBlank() } ?: "竞赛履历"

    val subtitle: String
        get() = buildList {
            if (isDefault) {
                roleTag?.takeIf { it.isNotBlank() }?.let(::add)
                add("组队前可写")
            } else {
                teamName?.takeIf { it.isNotBlank() }?.let(::add)
                roleTag?.takeIf { it.isNotBlank() }?.let(::add) ?: add("队伍记录")
            }
        }.joinToString(" · ")

    val weeklyFreeMinutes: Int get() = weeklyAvailability.values.sumOf { windows -> windows.sumOf { it.minutes } }

    /** 可再解析的一行，用于编辑框回填（[MemberImport.parseAvailability] 能直接读回来）。 */
    val availabilityInput: String get() = availabilityText(weeklyAvailability)

    val availabilitySummary: String
        get() {
            if (weeklyAvailability.isEmpty()) return "空闲未登记"
            val days = weeklyAvailability.keys.sortedBy { it.value }.joinToString("、") { day ->
                dayLabel(day) + " " + weeklyAvailability.getValue(day).joinToString("、") { window ->
                    "${clock(window.startMinute)}-${clock(window.endMinute)}"
                }
            }
            return "$days · 每周约 ${hoursText(weeklyFreeMinutes)}"
        }

    /** 供名册/队友卡复用：只带本人的能力与空闲，不泄露团队数据。 */
    fun toMemberCard(memberId: String): MemberCard = MemberCard(
        memberId = memberId,
        name = name,
        roleTag = roleTag,
        responsibility = abilities.ifBlank { headline },
        weeklyAvailability = weeklyAvailability,
        scheduleConfidence = if (weeklyAvailability.isEmpty()) ScheduleConfidence.LOW else ScheduleConfidence.HIGH,
        dailyCapacityHours = dailyCapacityHours,
    )

    companion object {
        const val DEFAULT_ID = "default"

        fun defaultCard(name: String = "我"): PersonCard = PersonCard(
            cardId = DEFAULT_ID,
            kind = PersonCardKind.DEFAULT,
            name = name.ifBlank { "我" },
        )

        fun competitionCardId(teamId: String): String = "team:$teamId"

        private fun dayLabel(day: DayOfWeek): String =
            "周" + "一二三四五六日"[day.value - 1]

        private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

        private fun hoursText(minutes: Int): String =
            if (minutes >= 60) ("%.1f".format(minutes / 60f).removeSuffix(".0")) + "h" else "${minutes}min"
    }
}

/** 一张竞赛履历卡的生成依据：来自某一支真实队伍，而不是本人自述。 */
data class CompetitionParticipation(
    val teamId: String,
    val teamName: String,
    val competitionName: String?,
    val memberName: String,
    val roleTag: String?,
    val responsibility: String,
    val result: String,
    val teammateCount: Int,
    val seenAtEpochMillis: Long,
)

/**
 * 资料卡集合。约束在 [normalize] 里收口：
 * - 默认卡恒有且只有一张；
 * - 竞赛卡必须有队伍凭据（teamId + teamName），不能凭手填「参加过」；
 * - 没有真实队伍的人只有默认卡，因此「多张卡」本身就代表参赛履历。
 */
data class PersonCardBook(val cards: List<PersonCard> = emptyList()) {

    private val ordered: List<PersonCard> get() = normalize(cards)

    fun allCards(): List<PersonCard> = ordered
    fun defaultCard(): PersonCard = ordered.first()
    fun competitionCards(): List<PersonCard> = ordered.filter { it.isCompetition }
    fun card(cardId: String): PersonCard? = ordered.firstOrNull { it.cardId == cardId }
    fun publicCards(): List<PersonCard> = ordered.filter { it.isPublic }
    val hasCompeted: Boolean get() = competitionCards().isNotEmpty()

    /** 默认卡：组队前也能编辑；空闲与每日投入决定匹配时的可排容量。 */
    fun editDefault(
        name: String,
        headline: String,
        abilities: String,
        expectation: String,
        availability: Map<DayOfWeek, List<FreeWindow>>,
        dailyCapacityHours: Double,
        visibility: CardVisibility,
        nowEpochMillis: Long,
    ): PersonCardBook {
        val current = defaultCard()
        return replace(
            current.copy(
                name = name.trim().ifBlank { "我" },
                headline = headline.trim(),
                abilities = abilities.trim(),
                expectation = expectation.trim(),
                weeklyAvailability = availability,
                dailyCapacityHours = dailyCapacityHours.coerceIn(0.5, 16.0),
                visibility = visibility,
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, nowEpochMillis),
            ),
        )
    }

    fun editCompetition(
        cardId: String,
        contribution: String,
        result: String,
        visibility: CardVisibility,
        nowEpochMillis: Long,
    ): PersonCardBook {
        val current = card(cardId)?.takeIf { it.isCompetition } ?: return this
        return replace(
            current.copy(
                headline = contribution.trim(),
                result = result.trim(),
                visibility = visibility,
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, nowEpochMillis),
            ),
        )
    }

    fun setVisibility(cardId: String, visibility: CardVisibility, nowEpochMillis: Long = 0L): PersonCardBook {
        val current = card(cardId) ?: return this
        if (current.visibility == visibility) return this
        return replace(current.copy(visibility = visibility, updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, nowEpochMillis)))
    }

    /**
     * 从真实参赛记录同步竞赛卡：已有的本人编辑（贡献、结果、公开状态）保留，
     * 只补队伍侧事实（队名、比赛名、角色、人数、归档结果）。重复调用幂等。
     */
    fun syncTeams(participations: List<CompetitionParticipation>): PersonCardBook {
        if (participations.isEmpty()) return this
        var next = this
        participations
            .filter { it.teamId.isNotBlank() && it.teamName.isNotBlank() }
            .distinctBy { it.teamId }
            .forEach { participation ->
                val cardId = PersonCard.competitionCardId(participation.teamId)
                val existing = next.cards.firstOrNull { it.cardId == cardId }
                val merged = PersonCard(
                    cardId = cardId,
                    kind = PersonCardKind.COMPETITION,
                    name = participation.memberName.trim().ifBlank { existing?.name.orEmpty() },
                    headline = existing?.headline?.ifBlank { participation.responsibility }.orEmpty()
                        .ifBlank { participation.responsibility.trim() },
                    abilities = existing?.abilities?.ifBlank { participation.responsibility }.orEmpty()
                        .ifBlank { participation.responsibility.trim() },
                    expectation = existing?.expectation.orEmpty(),
                    roleTag = participation.roleTag?.takeIf { it.isNotBlank() } ?: existing?.roleTag,
                    teamId = participation.teamId,
                    teamName = participation.teamName,
                    competitionName = participation.competitionName?.takeIf { it.isNotBlank() }
                        ?: existing?.competitionName,
                    result = existing?.result?.takeIf { it.isNotBlank() } ?: participation.result.trim(),
                    teammateCount = maxOf(participation.teammateCount, existing?.teammateCount ?: 0),
                    weeklyAvailability = existing?.weeklyAvailability.orEmpty(),
                    dailyCapacityHours = existing?.dailyCapacityHours ?: 3.0,
                    visibility = existing?.visibility ?: CardVisibility.PRIVATE,
                    updatedAtEpochMillis = maxOf(existing?.updatedAtEpochMillis ?: 0L, participation.seenAtEpochMillis),
                )
                next = next.replace(merged)
            }
        return next
    }

    /** 默认卡补名字/空闲只在占位值上做，不覆盖用户写过的内容。 */
    fun seedFromMember(member: MemberCard, nowEpochMillis: Long): PersonCardBook {
        val current = defaultCard()
        val nameIsPlaceholder = current.name.isBlank() || current.name == "我"
        val shouldFillAvailability = current.weeklyAvailability.isEmpty() && member.weeklyAvailability.isNotEmpty()
        if (!nameIsPlaceholder && !shouldFillAvailability) return this
        return replace(
            current.copy(
                name = if (nameIsPlaceholder) member.name.trim().ifBlank { current.name } else current.name,
                roleTag = current.roleTag ?: member.roleTag,
                weeklyAvailability = if (shouldFillAvailability) member.weeklyAvailability else current.weeklyAvailability,
                // 3.0 是未填写时的占位值；用户改过的容量不拿队伍侧数据覆盖。
                dailyCapacityHours = if (shouldFillAvailability && current.dailyCapacityHours == 3.0) {
                    member.dailyCapacityHours
                } else {
                    current.dailyCapacityHours
                },
                updatedAtEpochMillis = maxOf(current.updatedAtEpochMillis, nowEpochMillis),
            ),
        )
    }

    /** 公开卡摘要：只含公开卡，用于邀请/分享文案与「公开可被邀请」的落地。 */
    fun publicSummary(): String? {
        val public = publicCards()
        if (public.isEmpty()) return null
        val owner = public.firstOrNull { it.isDefault }?.name
            ?: public.first().name.takeIf { it.isNotBlank() }
            ?: "我"
        return buildString {
            appendLine("【公开资料卡 · $owner】")
            public.firstOrNull { it.isDefault }?.let { card ->
                if (card.abilities.isNotBlank()) appendLine("能力：${card.abilities}")
                if (card.expectation.isNotBlank()) appendLine("团队预期：${card.expectation}")
                appendLine("空闲：${card.availabilitySummary} · 每日可投入 ${formatHours(card.dailyCapacityHours)}")
            }
            val competitions = public.filter { it.isCompetition }
            if (competitions.isNotEmpty()) {
                append("竞赛履历：")
                appendLine(
                    competitions.joinToString("；") { card ->
                        buildString {
                            append(card.title)
                            card.teamName?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
                            card.result.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        }
                    } + "。",
                )
            }
        }.trimEnd()
    }

    private fun replace(person: PersonCard): PersonCardBook {
        val index = cards.indexOfFirst { it.cardId == person.cardId }
        val next = if (index < 0) cards + person else cards.toMutableList().also { it[index] = person }
        return PersonCardBook(next)
    }

    override fun equals(other: Any?): Boolean = other is PersonCardBook && ordered == other.ordered
    override fun hashCode(): Int = ordered.hashCode()

    companion object {
        /** 空档也永远有一张默认卡：没参赛的人资料卡数量恰好是 1。 */
        fun empty(): PersonCardBook = PersonCardBook(listOf(PersonCard.defaultCard()))

        private fun normalize(raw: List<PersonCard>): List<PersonCard> {
            val base = raw.filter { it.isDefault }.maxByOrNull { it.updatedAtEpochMillis }
                ?: PersonCard.defaultCard()
            val competitions = raw
                .asSequence()
                .filter { it.isCompetition }
                .filter { !it.teamId.isNullOrBlank() && !it.teamName.isNullOrBlank() }
                .map { it.copy(cardId = PersonCard.competitionCardId(it.teamId!!)) }
                .sortedByDescending { it.updatedAtEpochMillis }
                .distinctBy { it.cardId }
                .toList()
            val normalizedDefault = base.copy(
                cardId = PersonCard.DEFAULT_ID,
                kind = PersonCardKind.DEFAULT,
                name = base.name.trim().ifBlank { "我" },
                teamId = null,
                teamName = null,
                competitionName = null,
                teammateCount = 0,
            )
            return listOf(normalizedDefault) + competitions
        }

        private fun formatHours(hours: Double): String =
            if (hours % 1.0 == 0.0) "${hours.toInt()}h" else "${"%.1f".format(hours)}h"
    }
}

/**
 * 把「本机所有队伍 + 已归档结果」折成竞赛卡依据。
 * [aliases] 是本人名字的归一化写法（默认卡名字 + 当前身份名），只有队里真有这个人才会生成卡。
 */
object PersonCardSync {

    fun participations(
        states: List<TeamCollabState>,
        archives: List<ArchiveEntry>,
        aliases: Set<String>,
        seenAtEpochMillis: Long,
    ): List<CompetitionParticipation> {
        if (aliases.isEmpty()) return emptyList()
        return states.mapNotNull { state ->
            val member = state.members.firstOrNull { TeamRosterBook.keyOf(it.name) in aliases } ?: return@mapNotNull null
            val archive = archives
                .filter { it.teamId == state.teamId }
                .maxByOrNull { it.archivedAt }
            CompetitionParticipation(
                teamId = state.teamId,
                teamName = state.teamName,
                competitionName = state.profileCard?.competitionName,
                memberName = member.name,
                roleTag = member.roleTag,
                responsibility = member.responsibility,
                result = archive?.let { entry ->
                    entry.result.takeIf { it.isNotBlank() }?.let { "已归档 · $it" }
                        ?: "已归档"
                }.orEmpty(),
                teammateCount = state.members.size,
                seenAtEpochMillis = seenAtEpochMillis,
            )
        }.distinctBy { it.teamId }
    }
}
