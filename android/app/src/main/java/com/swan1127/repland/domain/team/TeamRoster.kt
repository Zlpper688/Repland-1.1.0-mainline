package com.swan1127.repland.domain.team

import java.time.DayOfWeek

/**
 * 跨队队友名册（个人域）：一起参赛过的人与「想了解」的人都记在这里，换队/归档不丢。
 * 名册是邀请的入口：拉人时姓名、分工、空闲时段直接带过去，不用每次重填。
 */
data class RosterPerson(
    val key: String,
    val name: String,
    val roleTag: String? = null,
    val responsibility: String = "",
    val weeklyAvailability: Map<DayOfWeek, List<FreeWindow>> = emptyMap(),
    val scheduleConfidence: ScheduleConfidence = ScheduleConfidence.HIGH,
    val dailyCapacityHours: Double = 3.0,
    val teamIds: Set<String> = emptySet(),
    val lastTeamId: String? = null,
    val lastTeamName: String? = null,
    val competitionName: String? = null,
    val lastSeenEpochMillis: Long = 0L,
    val note: String = "",
) {
    /** 共同参赛过 = 至少在一支队伍里合作过；否则只待在「你想了解」里。 */
    val hasTeamed: Boolean get() = teamIds.isNotEmpty()

    val subtitle: String
        get() = buildList {
            roleTag?.takeIf { it.isNotBlank() }?.let(::add)
            if (hasTeamed) add("一起 ${teamIds.size} 场")
            lastTeamName?.takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" · ")

    fun toMemberCard(memberId: String): MemberCard = MemberCard(
        memberId = memberId,
        name = name,
        roleTag = roleTag,
        responsibility = responsibility,
        weeklyAvailability = weeklyAvailability,
        scheduleConfidence = scheduleConfidence,
        dailyCapacityHours = dailyCapacityHours,
    )
}

/** 名册合并全在纯域里完成，便于单测；持久化只负责存取这份结果。 */
data class TeamRosterBook(val people: List<RosterPerson> = emptyList()) {

    fun person(key: String): RosterPerson? = people.firstOrNull { it.key == key }

    fun teammates(): List<RosterPerson> = people
        .filter { it.hasTeamed }
        .sortedWith(compareByDescending<RosterPerson> { it.lastSeenEpochMillis }.thenBy { it.name })

    fun candidates(): List<RosterPerson> = people
        .filterNot { it.hasTeamed }
        .sortedWith(compareByDescending<RosterPerson> { it.lastSeenEpochMillis }.thenBy { it.name })

    /** 把一支队伍的成员记进「共同参赛过」；队长是自己，不记。 */
    fun recordTeam(state: TeamCollabState, seenAtEpochMillis: Long): TeamRosterBook {
        val competition = state.profileCard?.competitionName
        return state.members
            .filterNot { it.isCaptain }
            .fold(this) { book, member ->
                book.record(member, state.teamId, state.teamName, competition, seenAtEpochMillis)
            }
    }

    fun record(
        member: MemberCard,
        teamId: String,
        teamName: String?,
        competitionName: String?,
        seenAtEpochMillis: Long,
    ): TeamRosterBook {
        val key = keyOf(member.name)
        if (key.isEmpty() || teamId.isBlank()) return this
        val existing = person(key)
        val merged = RosterPerson(
            key = key,
            name = member.name.trim(),
            roleTag = member.roleTag?.takeIf { it.isNotBlank() } ?: existing?.roleTag,
            responsibility = member.responsibility.ifBlank { existing?.responsibility.orEmpty() },
            weeklyAvailability = member.weeklyAvailability.ifEmpty { existing?.weeklyAvailability.orEmpty() },
            scheduleConfidence = if (member.weeklyAvailability.isNotEmpty()) {
                member.scheduleConfidence
            } else {
                existing?.scheduleConfidence ?: member.scheduleConfidence
            },
            dailyCapacityHours = if (member.weeklyAvailability.isNotEmpty()) {
                member.dailyCapacityHours
            } else {
                existing?.dailyCapacityHours ?: member.dailyCapacityHours
            },
            teamIds = existing?.teamIds.orEmpty() + teamId,
            lastTeamId = teamId,
            lastTeamName = teamName ?: existing?.lastTeamName,
            competitionName = competitionName ?: existing?.competitionName,
            lastSeenEpochMillis = maxOf(existing?.lastSeenEpochMillis ?: 0L, seenAtEpochMillis),
            note = existing?.note.orEmpty(),
        )
        return upsert(merged)
    }

    /** 加入「你想了解」：只记名字和备注；以后一起参赛会自动升进「共同参赛过」。 */
    fun pin(name: String, note: String = "", seenAtEpochMillis: Long = 0L): TeamRosterBook {
        val trimmed = name.trim()
        val key = keyOf(trimmed)
        if (key.isEmpty()) return this
        val existing = person(key)
        val merged = RosterPerson(
            key = key,
            name = trimmed,
            roleTag = existing?.roleTag,
            responsibility = existing?.responsibility.orEmpty(),
            weeklyAvailability = existing?.weeklyAvailability.orEmpty(),
            scheduleConfidence = existing?.scheduleConfidence ?: ScheduleConfidence.HIGH,
            dailyCapacityHours = existing?.dailyCapacityHours ?: 3.0,
            teamIds = existing?.teamIds.orEmpty(),
            lastTeamId = existing?.lastTeamId,
            lastTeamName = existing?.lastTeamName,
            competitionName = existing?.competitionName,
            lastSeenEpochMillis = maxOf(existing?.lastSeenEpochMillis ?: 0L, seenAtEpochMillis),
            note = note.trim().ifBlank { existing?.note.orEmpty() },
        )
        return upsert(merged)
    }

    fun forget(key: String): TeamRosterBook =
        if (people.none { it.key == key }) this else TeamRosterBook(people.filterNot { it.key == key })

    /** 启动回填与在线记录可能先后到达：按人合并，队伍集合取并集，较新记录补字段。 */
    fun merge(other: TeamRosterBook): TeamRosterBook =
        other.people.fold(this) { book, incoming ->
            val existing = book.person(incoming.key)
            if (existing == null) book.upsert(incoming) else book.upsert(mergePerson(existing, incoming))
        }

    private fun upsert(person: RosterPerson): TeamRosterBook {
        val index = people.indexOfFirst { it.key == person.key }
        val next = if (index < 0) people + person else people.toMutableList().also { it[index] = person }
        return if (next == people) this else TeamRosterBook(next)
    }

    private fun mergePerson(left: RosterPerson, right: RosterPerson): RosterPerson = RosterPerson(
        key = left.key,
        name = right.name.ifBlank { left.name },
        roleTag = right.roleTag ?: left.roleTag,
        responsibility = right.responsibility.ifBlank { left.responsibility },
        weeklyAvailability = right.weeklyAvailability.ifEmpty { left.weeklyAvailability },
        scheduleConfidence = if (right.weeklyAvailability.isNotEmpty()) right.scheduleConfidence else left.scheduleConfidence,
        dailyCapacityHours = if (right.weeklyAvailability.isNotEmpty()) right.dailyCapacityHours else left.dailyCapacityHours,
        teamIds = left.teamIds + right.teamIds,
        lastTeamId = right.lastTeamId ?: left.lastTeamId,
        lastTeamName = right.lastTeamName ?: left.lastTeamName,
        competitionName = right.competitionName ?: left.competitionName,
        lastSeenEpochMillis = maxOf(left.lastSeenEpochMillis, right.lastSeenEpochMillis),
        note = right.note.ifBlank { left.note },
    )

    companion object {
        /** 跨队识别同一人：忽略大小写与空白；中文名不会因此被误并。 */
        fun keyOf(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), "")
    }
}
