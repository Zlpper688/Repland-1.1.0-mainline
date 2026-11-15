package com.swan1127.repland.domain.model

/**
 * 内容画像：让模型读真实的计划、推进和收尾文字，写出它对这个用户的理解。
 *
 * 和数值画像的分工：数值画像回答"估时准不准"，这里回答"他在做什么、习惯怎么推进"。
 * 每条结论都必须挂回材料编号——挂不回去的不收，避免模型拿套话冒充理解。
 * 用户自己写的更正单独存放，永远排在模型结论之前。
 */
data class PortraitMaterialLine(val id: String, val text: String)

data class PortraitEvidence(val id: String, val text: String)

data class PortraitPoint(val text: String, val evidenceIds: List<String>)

data class ContentPortrait(
    val version: Int = 0,
    val updatedAtMillis: Long? = null,
    val materialDays: Int = 30,
    /** 内容授权。和数值画像的授权相互独立：这里发的是任务原文和日记。 */
    val consentAtMillis: Long? = null,
    val points: List<PortraitPoint> = emptyList(),
    val evidence: List<PortraitEvidence> = emptyList(),
    val corrections: List<String> = emptyList(),
) {
    val hasContentConsent: Boolean get() = consentAtMillis != null

    fun evidenceOf(point: PortraitPoint): List<PortraitEvidence> =
        point.evidenceIds.mapNotNull { id -> evidence.firstOrNull { it.id == id } }

    /** 进提示词的一段。用户更正优先于任何推测，结论必须指向具体材料。 */
    fun briefing(maxPoints: Int = 6): String {
        if (corrections.isEmpty() && points.isEmpty()) return ""
        return buildString {
            if (corrections.isNotEmpty()) {
                appendLine("用户亲自更正过的自我说明（以此为准，优先于任何推测）：")
                corrections.takeLast(6).forEach { appendLine("- $it") }
            }
            if (points.isNotEmpty()) {
                appendLine("从最近 ${materialDays} 天真实内容里读出的理解（每行末尾是材料编号；用来理解用户，不要逐条复述给用户）：")
                points.take(maxPoints).forEach { point ->
                    appendLine("- ${point.text}（材料 ${point.evidenceIds.joinToString("、")}）")
                }
            }
        }.trimEnd()
    }
}

object ContentPortraitComposer {

    const val MAX_POINTS = 5
    private const val MAX_POINT_CHARS = 60

    fun isUsable(material: List<PortraitMaterialLine>): Boolean = material.size >= 3

    fun systemPrompt(): String =
        "你在读一个人的任务和日记，写一小段你对他的理解，供规划助手以后理解他的处境和做事方式。\n" +
            "只写材料支持得住的东西：他在忙什么、任务之间怎么分配、推进和收尾时的真实情况。\n" +
            "不评价人格、能力、自律或健康，不推测材料之外的事，不用套话。\n" +
            "输出不超过 $MAX_POINTS 行，每行必须以「- 」开头，一句话不超过 $MAX_POINT_CHARS 字，" +
            "行末用「（依据：编号,编号）」标出所依据的材料编号。没有依据就不写这一行。"

    fun userPrompt(material: List<PortraitMaterialLine>, days: Int): String = buildString {
        appendLine("材料（最近 $days 天的真实记录）：")
        material.forEach { appendLine("[${it.id}] ${it.text}") }
    }.trimEnd()

    /**
     * 只接受带有效编号的结论。模型没给依据、给了不存在的编号、写成空话的，
     * 一律丢掉；一条都不剩时返回 null，界面如实说这次没有可用的理解，而不是退回套话。
     */
    fun parse(raw: String, material: List<PortraitMaterialLine>): List<PortraitPoint>? {
        val known = material.map { it.id }.toSet()
        val points = raw.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val body = line.removePrefix("-").removePrefix("•").removePrefix("·").trim()
                if (body.isEmpty()) return@mapNotNull null
                val ids = EVIDENCE.findAll(body)
                    .flatMap { match -> match.groupValues[1].split(',', '，', '、', ' ', '/') }
                    .map { it.trim().trimStart('m', 'M') }
                    .filter { it in known }
                    .distinct()
                    .toList()
                if (ids.isEmpty()) return@mapNotNull null
                val text = body
                    .replace(EVIDENCE, "")
                    .replace(Regex("[（(]\\s*[)）]"), "")
                    .trim()
                    .trimEnd('。', '；', ';', '，', ',')
                    .take(MAX_POINT_CHARS)
                if (text.length < 6) return@mapNotNull null
                PortraitPoint(text, ids)
            }
            .distinctBy { it.text }
            .take(MAX_POINTS)
            .toList()
        return points.takeIf { it.isNotEmpty() }
    }

    /** 证据只留被用到的材料，顺序和编号保持稳定，便于再次生成时对照。 */
    fun evidenceFor(points: List<PortraitPoint>, material: List<PortraitMaterialLine>): List<PortraitEvidence> {
        val used = points.flatMap { it.evidenceIds }.toSet()
        return material.filter { it.id in used }.map { PortraitEvidence(it.id, it.text) }
    }

    /** 材料编号在一轮生成内保持唯一；重复文本(同日同类任务)各占一行，模型才能分别引用。 */
    fun numbered(lines: List<String>): List<PortraitMaterialLine> =
        lines.filter { it.isNotBlank() }.mapIndexed { index, text -> PortraitMaterialLine((index + 1).toString(), text) }

    /** 兼容「（依据：3,7）」「（材料 3、7）」「[3,7]」三种写法；编号必须在材料里存在才算数。 */
    private val EVIDENCE = Regex("[（(\\[]\\s*(?:(?:依据|材料)\\s*[:：]?\\s*)?([0-9][0-9,，、\\s/]*)\\s*[)）\\]]")
}
