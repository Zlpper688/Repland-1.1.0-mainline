package com.swan1127.repland.ui.team

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swan1127.repland.domain.competition.AwardGrade
import com.swan1127.repland.domain.competition.AwardInput
import com.swan1127.repland.domain.competition.AwardLevel
import com.swan1127.repland.domain.competition.CompetitionAward
import com.swan1127.repland.domain.competition.CompetitionCatalogEntry
import com.swan1127.repland.domain.competition.CompetitionInstance
import com.swan1127.repland.domain.competition.CompetitionTracking
import com.swan1127.repland.domain.competition.CompetitionTrackStatus
import com.swan1127.repland.domain.competition.PointsCalculator
import com.swan1127.repland.domain.competition.PointsRuleVersion
import com.swan1127.repland.domain.competition.SchoolCompetitionSeed
import com.swan1127.repland.ui.components.rememberLandscapeSurface
import com.swan1127.repland.ui.theme.replandAccent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val COMP_DAY = DateTimeFormatter.ofPattern("M月d日")

/**
 * 校内比赛：比赛卡堆下方的内置竞赛库模块。进行中/即将开始 + A/B 徽标 + 官网。
 * 数据来自 assets 种子（抓取快照）；所有数字都标注口径来源，不冒充权威。
 */
@Composable
internal fun SchoolCompetitionsCard(
    seed: SchoolCompetitionSeed,
    tracking: List<CompetitionTracking>,
    awards: List<CompetitionAward>,
    onOpenInstance: (CompetitionInstance) -> Unit,
    onDeleteAward: (String) -> Unit,
) {
    val accent = replandAccent(5)
    val surface = rememberLandscapeSurface(MaterialTheme.colorScheme.surfaceContainerHigh)
    val today = LocalDate.now()
    val ongoing = remember(seed, today) { seed.ongoing(today) }
    val upcoming = remember(seed, today) { seed.upcoming(today) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var awardsExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = surface,
        border = BorderStroke(1.dp, accent.ink.copy(alpha = .14f)),
        modifier = Modifier.fillMaxWidth().testTag("team-comp-library"),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Text("校内比赛 · ${seed.schoolName}", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = accent.ink.copy(alpha = .74f))
                Text(
                    "进行中 ${ongoing.size} · 即将开始 ${upcoming.size} · 关注中 ${tracking.size} · 履历 ${awards.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tracking.isNotEmpty()) {
                Text("我的追踪", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                tracking.forEach { item ->
                    val instance = seed.instance(item.instanceKey)
                    if (instance != null) {
                        TrackedRow(item, today, accent.ink, onClick = { onOpenInstance(instance) })
                    }
                }
            }
            if (awards.isNotEmpty()) {
                Text("获奖履历", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                (if (awardsExpanded) awards else awards.take(3)).forEach { award ->
                    AwardRow(award, seed, accent.ink, onDelete = { onDeleteAward(award.awardId) })
                }
                if (awards.size > 3) {
                    TextButton(onClick = { awardsExpanded = !awardsExpanded },
                        modifier = Modifier.testTag("team-comp-awards-more")) {
                        Text(
                            if (awardsExpanded) "收起 ▴" else "看全部 ${awards.size} 条 ›",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (ongoing.isEmpty() && upcoming.isEmpty()) {
                Text("平台快照里没有进行中或待开始的比赛", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val rows = if (expanded) ongoing + upcoming else ongoing.take(3)
                rows.forEach { instance ->
                    CompetitionRow(instance, seed, today, accent.ink, onClick = { onOpenInstance(instance) })
                }
                if (ongoing.size + upcoming.size > 3 || expanded) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("team-comp-more")) {
                        Text(
                            if (expanded) "收起 ▴" else "看全部 ${ongoing.size + upcoming.size} 场 ›",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            seed.caveats.forEach { caveat ->
                Text("· $caveat", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompetitionRow(
    instance: CompetitionInstance,
    seed: SchoolCompetitionSeed,
    today: LocalDate,
    ink: Color,
    onClick: () -> Unit,
) {
    val entry = remember(instance.key, seed) { seed.entry(instance.catalogueKey) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).testTag("team-comp-row")
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(999.dp), color = ink.copy(alpha = .1f)) {
            Text(instance.level, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall, color = ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(instance.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(windowLine(instance, today), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        entry?.let { AbPill(it.category, ink) }
        Text("›", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackedRow(
    item: CompetitionTracking,
    today: LocalDate,
    ink: Color,
    onClick: () -> Unit,
) {
    val deadline = item.deadlineEpochDay?.let { LocalDate.ofEpochDay(it) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).testTag("team-comp-tracked")
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
            Text(item.status.label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    deadline == null -> "未设截止"
                    deadline.isBefore(today) -> "${deadline.format(COMP_DAY)}已截止"
                    else -> "${deadline.format(COMP_DAY)}截止" + if (item.remindEnabled) " · 提醒开" else " · 提醒关"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AbPill(category: String, ink: Color) {
    val label = when (category) {
        "A" -> "A类"
        "B" -> "B类"
        else -> "目录外"
    }
    Surface(shape = RoundedCornerShape(999.dp), color = ink.copy(alpha = .14f)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ink)
    }
}

/** 比赛详情：窗口与报名来自平台快照；加分数字是按获奖年度口径的参考值，不是承诺。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CompetitionInstanceSheet(
    instance: CompetitionInstance,
    seed: SchoolCompetitionSeed,
    tracking: CompetitionTracking?,
    onFollow: (CompetitionInstance) -> Unit,
    onUnfollow: (String) -> Unit,
    onSetStatus: (String, CompetitionTrackStatus) -> Unit,
    onToggleRemind: (String) -> Unit,
    onRegisterAward: (String, String, String, AwardLevel, AwardGrade, Int, Boolean, Int, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val entry = remember(instance.key, seed) { seed.entry(instance.catalogueKey) }
    val rule = remember(seed) { seed.ruleVersionFor(today.year) ?: seed.ruleVersions.lastOrNull() }
    var showAwardEntry by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
                    Text(instance.level, Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(instance.state, Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry?.let { AbPill(it.category, MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.weight(1f))
            }
            Text(instance.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(windowLine(instance, today), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TrackingBlock(instance, tracking, onFollow, onUnfollow, onSetStatus, onToggleRemind)
            TextButton(
                onClick = { showAwardEntry = true },
                modifier = Modifier.testTag("team-comp-award-entry"),
            ) { Text("登记获奖 ›") }
            entry?.let { CatalogueBlock(it) }
            rule?.let { PointsReferenceBlock(it, englishHalfWeight = entry?.englishHalfWeight == true) }
            SourceLine(instance, seed, entry)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (instance.platformUrl.isNotBlank()) {
                    TextButton(onClick = { openUrl(context, instance.platformUrl) }) { Text("平台页 ›") }
                }
                if (entry?.officialSite?.isNotBlank() == true) {
                    TextButton(onClick = { openUrl(context, entry.officialSite) }) { Text("官网 ›") }
                }
            }
        }
    }
    if (showAwardEntry) {
        AwardEntryDialog(
            instance = instance,
            seed = seed,
            onDismiss = { showAwardEntry = false },
            onSave = { title, level, grade, year, isTeam, rank, teammates, note ->
                showAwardEntry = false
                onRegisterAward(instance.key, title, instance.catalogueKey, level, grade, year, isTeam, rank, teammates, note)
            },
        )
    }
}

/** 追踪块：没关注给一个入口；关注后给状态流转 + 提醒开关 + 退出。 */
@Composable
private fun TrackingBlock(
    instance: CompetitionInstance,
    item: CompetitionTracking?,
    onFollow: (CompetitionInstance) -> Unit,
    onUnfollow: (String) -> Unit,
    onSetStatus: (String, CompetitionTrackStatus) -> Unit,
    onToggleRemind: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item == null) {
            TextButton(onClick = { onFollow(instance) }, modifier = Modifier.testTag("team-comp-follow")) {
                Text("＋ 关注这个比赛")
            }
        } else {
            HorizontalDivider()
            Text("我的追踪", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            val deadline = item.deadlineEpochDay?.let { LocalDate.ofEpochDay(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        deadline == null -> "未设截止"
                        deadline.isBefore(LocalDate.now()) -> "${deadline.format(COMP_DAY)}已截止"
                        else -> "${deadline.format(COMP_DAY)}截止" +
                            if (item.remindEnabled) " · 截止前一天 20:00 提醒" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onToggleRemind(item.instanceKey) }, modifier = Modifier.testTag("team-comp-remind")) {
                    Text(if (item.remindEnabled) "关闭提醒" else "开启提醒")
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompetitionTrackStatus.entries.forEach { status ->
                    ChoiceChip(
                        status.label,
                        status == item.status,
                        tag = "team-comp-status-${status.name}",
                    ) { onSetStatus(item.instanceKey, status) }
                }
            }
            TextButton(onClick = { onUnfollow(item.instanceKey) }, modifier = Modifier.testTag("team-comp-unfollow")) {
                Text("取消关注")
            }
        }
    }
}

@Composable
private fun CatalogueBlock(entry: CompetitionCatalogEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text("加分目录 · ${entry.catalogueYear} 版", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
        val organizer = listOf(entry.nationalOrganizer, entry.provincialOrganizer)
            .filter { it.isNotBlank() }.joinToString(" / ")
        if (organizer.isNotBlank()) InfoLine("主办", organizer)
        if (entry.college.isNotBlank()) InfoLine("牵头学院", entry.college)
        if (entry.club.isNotBlank()) InfoLine("俱乐部", entry.club)
        InfoLine(
            "目录内",
            when {
                entry.nationalInCatalog && entry.provincialInCatalog -> "国家级目录 + 省级目录"
                entry.nationalInCatalog -> "仅国家级目录"
                entry.provincialInCatalog -> "仅省级目录"
                else -> "非官方目录（计分降一档）"
            },
        )
        if (entry.officialSite.isNotBlank()) {
            InfoLine(
                "官网",
                if (entry.siteStatus.startsWith("verified")) "${entry.officialSite}（已验可达）" else entry.officialSite,
            )
        }
    }
}

@Composable
private fun PointsReferenceBlock(rule: PointsRuleVersion, englishHalfWeight: Boolean) {
    val t = rule.tuimian
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text("加分参考（${rule.effectiveFrom} 年起口径）", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
        InfoLine(
            "推免",
            "国一 ${fmt(t.nationalFirst)} · 国二 ${fmt(t.nationalSecond)} · 国三 ${fmt(t.nationalThird)}；" +
                "省一 ${fmt(t.provincialFirst)} · 省二 ${fmt(t.provincialSecond)}（分/项）",
        )
        val ratios = t.teamRatios.map { "${(it * 100).toInt()}%" }
        if (ratios.isNotEmpty()) InfoLine("团体", "前三名按 ${ratios.joinToString(" / ")} 计")
        if (t.englishHalfWeight && englishHalfWeight) InfoLine("外语", "该赛事属外语类，按 50% 计分")
        if (t.unofficialCatalogDropOneTier) InfoLine("非目录", "不在国家/省竞赛目录内的赛事降一档计分")
        InfoLine(
            "学分",
            "创新学分 ${fmt(rule.credit.minPerAward)}~${fmt(rule.credit.maxPerAward)}/项，" +
                "累计修满 ${fmt(rule.credit.requiredTotal)}（${rule.credit.deadlineNote}）",
        )
        Text(rule.scope, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SourceLine(instance: CompetitionInstance, seed: SchoolCompetitionSeed, entry: CompetitionCatalogEntry?) {
    val parts = buildList {
        add("平台快照 ${seed.fetchedAt}")
        entry?.let { add("目录 ${it.catalogueYear} 版 · ${it.sourceDate}") }
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun windowLine(instance: CompetitionInstance, today: LocalDate): String {
    val start = instance.parsedStart
    val end = instance.parsedEnd
    val window = when {
        start != null && end != null -> "${start.format(COMP_DAY)} – ${end.format(COMP_DAY)}"
        end != null -> "至 ${end.format(COMP_DAY)}"
        else -> "窗口未知"
    }
    val days = end?.let { ChronoUnit.DAYS.between(today, it).toInt() }
    val left = when {
        days == null -> ""
        days < 0 -> " · 已过期 ${-days} 天"
        days == 0 -> " · 今天截止"
        else -> " · 剩 $days 天"
    }
    return "$window$left · ${instance.signupGroups} 组 / ${instance.signupPeople} 人"
}

private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, tag: String? = null, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AwardRow(award: CompetitionAward, seed: SchoolCompetitionSeed, ink: Color, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("team-comp-award"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(shape = RoundedCornerShape(999.dp), color = ink.copy(alpha = .1f)) {
            Text("${award.awardYear}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall, color = ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(award.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append("${award.level.label}${award.grade.label}")
                    if (award.isTeam) append(" · 团体第${rankLabel(award.teamRank)}名")
                    append(" · ${awardPointsLine(award, seed)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDelete, modifier = Modifier.testTag("team-comp-award-delete")) {
            Text("删", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 登记获奖：等级/奖项/年度/团体一次选完；推免与学分预览按获奖年度口径实时算，写清是参考不承诺。 */
@Composable
internal fun AwardEntryDialog(
    instance: CompetitionInstance,
    seed: SchoolCompetitionSeed,
    onDismiss: () -> Unit,
    onSave: (title: String, level: AwardLevel, grade: AwardGrade, year: Int, isTeam: Boolean, teamRank: Int, teammates: String, note: String) -> Unit,
) {
    val entry = remember(instance.key, seed) { seed.entry(instance.catalogueKey) }
    var title by rememberSaveable { mutableStateOf(instance.title) }
    var levelName by rememberSaveable { mutableStateOf(defaultLevelName(instance.level)) }
    var gradeName by rememberSaveable { mutableStateOf(AwardGrade.FIRST.name) }
    var year by rememberSaveable { mutableIntStateOf(instance.parsedEnd?.year ?: LocalDate.now().year) }
    var isTeam by rememberSaveable { mutableStateOf(false) }
    var rank by rememberSaveable { mutableIntStateOf(1) }
    var teammates by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val preview = remember(levelName, gradeName, year, isTeam, rank) {
        PointsCalculator.preview(
            AwardInput(
                awardYear = year,
                level = AwardLevel.valueOf(levelName),
                grade = AwardGrade.valueOf(gradeName),
                isTeam = isTeam,
                teamRank = if (isTeam) rank else 0,
                english = entry?.englishHalfWeight == true,
                catalogueCategory = entry?.category.orEmpty(),
                nationalInCatalog = entry?.nationalInCatalog ?: false,
                provincialInCatalog = entry?.provincialInCatalog ?: false,
            ),
            seed,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登记获奖", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("team-comp-award-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("获奖名称") }, singleLine = true)
                SectionLabel("奖项级别")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AwardLevel.entries.forEach { candidate ->
                        ChoiceChip(candidate.label, levelName == candidate.name) { levelName = candidate.name }
                    }
                }
                SectionLabel("奖项等级")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AwardGrade.entries.forEach { candidate ->
                        ChoiceChip(candidate.label, gradeName == candidate.name) { gradeName = candidate.name }
                    }
                }
                SectionLabel("获奖年度")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { year -= 1 }) { Text("−") }
                    Text("$year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { year += 1 }) { Text("＋") }
                }
                SectionLabel("个人 / 团体")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceChip("个人", !isTeam) { isTeam = false }
                    ChoiceChip("团体", isTeam) { isTeam = true }
                }
                if (isTeam) {
                    SectionLabel("本人排名")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 3, 9).forEach { candidate ->
                            ChoiceChip(if (candidate == 9) "3名以后" else "第${candidate}名", rank == candidate) {
                                rank = candidate
                            }
                        }
                    }
                    OutlinedTextField(teammates, { teammates = it },
                        label = { Text("队友（逗号分隔，选填）") }, singleLine = true)
                }
                Text(
                    "推免参考 ${fmt(preview.tuimianPoints)} 分 · 学分 ${fmt(preview.creditMin)}~${fmt(preview.creditMax)}" +
                        if (preview.scholarshipNote.isNotBlank()) " · ${preview.scholarshipNote}" else "",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
                preview.notes.take(3).forEach { line ->
                    Text("· $line", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title, AwardLevel.valueOf(levelName), AwardGrade.valueOf(gradeName),
                        year, isTeam, if (isTeam) rank else 0, teammates, note,
                    )
                },
                modifier = Modifier.testTag("team-comp-award-save"),
            ) { Text("保存登记") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun defaultLevelName(levelText: String): String = when (levelText) {
    "国家级" -> AwardLevel.NATIONAL.name
    "省级" -> AwardLevel.PROVINCIAL.name
    "市级" -> AwardLevel.MUNICIPAL.name
    "国际" -> AwardLevel.INTERNATIONAL.name
    else -> AwardLevel.NATIONAL.name
}

private fun rankLabel(rank: Int): String = if (rank >= 9) "3以后" else "$rank"

private fun awardPointsLine(award: CompetitionAward, seed: SchoolCompetitionSeed): String {
    val entry = seed.entry(award.catalogueKey)
    val preview = PointsCalculator.preview(
        AwardInput(
            awardYear = award.awardYear,
            level = award.level,
            grade = award.grade,
            isTeam = award.isTeam,
            teamRank = if (award.isTeam) award.teamRank else 0,
            english = entry?.englishHalfWeight == true,
            catalogueCategory = entry?.category.orEmpty(),
            nationalInCatalog = entry?.nationalInCatalog ?: false,
            provincialInCatalog = entry?.provincialInCatalog ?: false,
        ),
        seed,
    )
    return "推免参考 ${fmt(preview.tuimianPoints)} 分"
}
