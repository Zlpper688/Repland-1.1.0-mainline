package com.swan1127.repland.domain.team

/**
 * 比赛材料粘贴导入：一行一个字段，`字段：内容`。
 * 预设字段落到档案卡六组槽位，识别不了的字段进补充区，原文不丢。
 */
data class CompetitionImportResult(
    val competitionName: String?,
    val fields: Map<ProfileSlotGroup, List<ProfileField>>,
    val extras: List<ExtraEntry>,
    val recognized: Int,
)

object CompetitionImport {
    private data class Slot(val group: ProfileSlotGroup, val key: String, val label: String)

    private val nameLabels = setOf("比赛名称", "比赛全称", "比赛", "赛事名称", "赛事全称", "赛事", "竞赛名称", "竞赛全称")

    private val slots: Map<String, Slot> = buildMap {
        fun slot(group: ProfileSlotGroup, key: String, label: String, vararg aliases: String) {
            aliases.forEach { alias -> put(alias, Slot(group, key, label)) }
        }
        slot(ProfileSlotGroup.BASIC_INFO, "signup_deadline", "报名截止", "报名截止", "报名截止时间", "报名结束")
        slot(ProfileSlotGroup.BASIC_INFO, "member_limit", "人数上限", "人数上限", "队伍人数", "每队人数", "队伍人数上限")
        slot(ProfileSlotGroup.BASIC_INFO, "organizer", "主办方", "主办方", "主办单位", "承办方", "承办单位")
        slot(ProfileSlotGroup.BASIC_INFO, "level", "赛事级别", "赛事级别", "比赛级别", "竞赛级别")
        slot(ProfileSlotGroup.TIMELINE, "preliminary", "初赛/初评", "初赛", "初评", "校赛初赛", "校赛初评", "院赛初评")
        slot(ProfileSlotGroup.TIMELINE, "final", "决赛/终审", "决赛", "终审", "校赛决赛", "校赛终审", "市赛决赛")
        slot(
            ProfileSlotGroup.TIMELINE, "submission_deadline", "材料提交截止",
            "材料提交截止", "提交截止", "作品提交截止", "作品提交时间",
        )
        slot(ProfileSlotGroup.QUOTA, "eligibility", "参赛资格", "参赛资格", "报名资格", "参赛对象", "参赛要求")
        slot(ProfileSlotGroup.QUOTA, "team_composition", "组队要求", "组队要求", "队伍组成", "组队方式", "分工要求")
        slot(ProfileSlotGroup.QUOTA, "signup_channel", "报名方式", "报名方式", "报名平台", "报名渠道", "报名入口")
        slot(ProfileSlotGroup.DELIVERABLES, "proposal", "项目书", "项目书", "商业计划书", "计划书")
        slot(ProfileSlotGroup.DELIVERABLES, "pitch_deck", "路演PPT", "路演PPT", "路演ppt", "PPT", "演示文稿")
        slot(ProfileSlotGroup.DELIVERABLES, "demo_video", "演示视频", "演示视频", "宣传视频", "产品视频")
        slot(ProfileSlotGroup.DELIVERABLES, "prototype", "作品/原型", "作品", "原型", "源码", "代码")
        slot(ProfileSlotGroup.DELIVERABLES, "deliverables", "提交物", "提交物", "提交材料", "交付物", "材料清单")
        slot(ProfileSlotGroup.REVIEW, "criteria", "评审标准", "评审标准", "评审规则", "评分标准", "评分规则")
        slot(ProfileSlotGroup.REVIEW, "defense", "答辩形式", "答辩形式", "答辩时长", "答辩要求", "路演形式", "路演时长")
        slot(ProfileSlotGroup.RESOURCES, "materials", "官方材料", "官方材料", "官方附件", "附件", "资料")
        slot(ProfileSlotGroup.RESOURCES, "contact", "联系方式", "联系方式", "咨询方式", "联系人")
    }

    fun parse(text: String): CompetitionImportResult {
        var competitionName: String? = null
        val byGroup = linkedMapOf<ProfileSlotGroup, MutableList<ProfileField>>()
        val extras = linkedMapOf<String, String>()
        var recognized = 0
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach
            val (rawLabel, value) = split(line) ?: return@forEach
            val label = rawLabel.trim()
            if (value.isBlank()) return@forEach
            if (label in nameLabels) {
                competitionName = value
                recognized += 1
                return@forEach
            }
            val slot = resolve(label)
            if (slot == null) {
                extras[label] = value
                recognized += 1
                return@forEach
            }
            val fields = byGroup.getOrPut(slot.group) { mutableListOf() }
            val index = fields.indexOfFirst { it.key == slot.key }
            if (index >= 0) {
                val old = fields[index]
                fields[index] = old.copy(value = listOfNotNull(old.value, value).joinToString("；"))
            } else {
                fields += ProfileField(slot.key, slot.label, value)
            }
            recognized += 1
        }
        return CompetitionImportResult(
            competitionName = competitionName,
            fields = byGroup,
            extras = extras.map { ExtraEntry(it.key, it.value) },
            recognized = recognized,
        )
    }

    private fun resolve(label: String): Slot? {
        val normalized = label.replace(Regex("\\s+"), "")
        return slots[normalized] ?: slots[normalized.lowercase()]
    }

    private fun split(line: String): Pair<String, String>? {
        val colon = line.indexOfFirst { it == '：' || it == ':' }
        if (colon > 0) return line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        val parts = line.split(Regex("\\s+"), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0] to parts[1]
        } else {
            null
        }
    }
}
