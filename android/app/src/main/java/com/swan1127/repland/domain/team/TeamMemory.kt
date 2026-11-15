package com.swan1127.repland.domain.team

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 记忆体系（TEAM_COLLAB_DESIGN.md §12，T5）。
 * 团队域 [TeamMemoryEntry]：档案要点 / 负责说明 / 会议结论，全队可读，写入受「记忆库写入」权限门控
 * （写入口在 [TeamCollabState]，域内代码归属 domain/team，§14）。
 * 参赛归档产物 [ArchiveEntry] 由队伍一键归档产出，沉淀进个人域 [HumanProfileMemory]：
 * 个人域仅本人可见、跟随账号、跨队复用（§16），独立于队伍快照持久化。
 */

/** 团队记忆条目种类（§12 团队域）。 */
enum class TeamMemoryKind(val label: String) {
    PROFILE("档案要点"),
    RESPONSIBILITY("负责说明"),
    MEETING("会议结论"),
}

data class TeamMemoryEntry(
    val memoryId: String,
    val kind: TeamMemoryKind,
    val content: String,
    val authorId: String,
    val at: LocalDateTime,
)

/** 参赛档案（§12/§16）：成绩 + 最终台账 + 作品外链；纯本地结构，可导出纯文本。 */
data class ArchiveEntry(
    val archiveId: String,
    val teamId: String,
    val teamName: String,
    val competitionName: String?,
    val result: String,
    val archivedAt: LocalDateTime,
    val archivedBy: String,
    val stats: Map<String, Int>,
    val finalLedger: List<LedgerEntry>,
    val workLinks: List<String>,
) {
    fun exportText(): String = buildString {
        appendLine("参赛档案 · $teamName")
        competitionName?.let { appendLine("比赛：$it") }
        appendLine("成绩：$result")
        appendLine("归档时间：${archivedAt.format(DATETIME_FMT)}")
        appendLine(
            "规模：成员 ${stats["members"] ?: 0} 人 · 任务 ${stats["tasksDone"] ?: 0}/${stats["tasks"] ?: 0} 完成 · " +
                "台账 ${stats["ledger"] ?: 0} 条 · 外链 ${stats["externalLinks"] ?: 0} 个",
        )
        if (workLinks.isNotEmpty()) {
            appendLine("—— 作品外链 ——")
            workLinks.forEach { appendLine("- $it") }
        }
        if (finalLedger.isNotEmpty()) {
            appendLine("—— 最终台账 ——")
            finalLedger.forEach { entry ->
                appendLine("[${entry.at.format(DATETIME_FMT)}] ${entry.kind.label}：${entry.evidence}")
            }
        }
    }

    private companion object {
        val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}

/** 个人画像要点：跨项目沉淀（技能 / 擅长 / 偏好），来源可以是某支队伍或手动记录。 */
data class ProfileFact(
    val factId: String,
    val content: String,
    val at: LocalDateTime,
    val sourceTeamId: String? = null,
)

/**
 * 个人记忆域（§12）：跨项目画像 + 参赛档案沉淀。
 * 仅本人可见、跟随账号、跨队复用（§16 多队共享个人画像、基线隔离）；
 * 持久化走独立 JSON（不进 TeamCollabState 队伍快照），写入方是本人，无需团队权限门控。
 */
class HumanProfileMemory {

    val facts = mutableListOf<ProfileFact>()
    val archives = mutableListOf<ArchiveEntry>()

    /** 同文去重；内容为空返回 null。 */
    fun addFact(content: String, at: LocalDateTime, sourceTeamId: String? = null): ProfileFact? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        if (facts.any { it.content == trimmed }) return null
        val fact = ProfileFact("fact-" + UUID.randomUUID().toString().take(8), trimmed, at, sourceTeamId)
        facts += fact
        return fact
    }

    fun removeFact(factId: String): Boolean = facts.removeAll { it.factId == factId }

    /** 参赛档案沉淀（归档一键触发）；按 archiveId 幂等。 */
    fun depositArchive(entry: ArchiveEntry): Boolean {
        if (archives.any { it.archiveId == entry.archiveId }) return false
        archives += entry
        return true
    }

    fun exportArchiveText(archiveId: String): String? =
        archives.firstOrNull { it.archiveId == archiveId }?.exportText()

    /** 全量导出：画像要点 + 所有参赛档案（可导出）。 */
    fun exportText(): String = buildString {
        appendLine("我的个人记忆 · ${facts.size} 条画像 · ${archives.size} 份参赛档案")
        if (facts.isNotEmpty()) {
            appendLine("—— 跨项目画像 ——")
            facts.forEach { appendLine("- ${it.content}") }
        }
        archives.forEach { archive ->
            appendLine()
            appendLine(archive.exportText())
        }
    }
}
