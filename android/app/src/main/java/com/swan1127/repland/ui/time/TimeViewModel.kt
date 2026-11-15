package com.swan1127.repland.ui.time

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swan1127.repland.data.importer.PdfTimetableImporter
import com.swan1127.repland.domain.model.ClassPeriodClock
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.DateOverrideDraft
import com.swan1127.repland.domain.model.DateOverrideType
import com.swan1127.repland.domain.model.ImportedCourse
import com.swan1127.repland.domain.model.QuickAvailability
import com.swan1127.repland.domain.model.TimeBlockKind
import com.swan1127.repland.domain.model.TimeBlockValidator
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.domain.model.WeeklyTimeBlockDraft
import com.swan1127.repland.domain.ports.TimeRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class TimeUiState(
    val weeklyBlocks: List<WeeklyTimeBlock> = emptyList(),
    val dateOverrides: List<DateOverride> = emptyList(),
    val semesterFirstWeekMonday: LocalDate? = null,
    val timeConstraintsUpdatedAtEpochMillis: Long = 0L,
    val classPeriodClock: ClassPeriodClock = ClassPeriodClock.DEFAULT,
    val isLoading: Boolean = true,
    val timetableImport: TimetableImportState = TimetableImportState.Idle,
    val hasLoaded: Boolean = false,
    val readError: String? = null,
) {
    val isTrusted: Boolean get() = hasLoaded && !isLoading && readError == null
}

sealed interface TimetableImportState {
    data object Idle : TimetableImportState

    data object Reading : TimetableImportState

    data class Review(
        val courses: List<ImportedCourse>,
        val selectedIds: Set<String> = courses.map(ImportedCourse::id).toSet(),
        val clockAcknowledged: Boolean = false,
        val saving: Boolean = false,
        val error: String? = null,
    ) : TimetableImportState

    data class Completed(val addedCount: Int, val skippedCount: Int) : TimetableImportState

    data class Failed(val message: String) : TimetableImportState
}

enum class TimeMutationKind { WEEKLY_SAVE, WEEKLY_DELETE, OVERRIDE_SAVE, OVERRIDE_DELETE, SEMESTER_SAVE, CLOCK_SAVE }
data class TimeMutationState(
    val kind: TimeMutationKind? = null,
    val targetId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val receipt: String? = null,
)

class TimeViewModel(
    private val timeRepository: TimeRepository,
    private val timetableImporter: PdfTimetableImporter,
    private val parseTimetable: suspend (Uri) -> List<ImportedCourse> = timetableImporter::parse,
) : ViewModel() {
    private val timetableImport = MutableStateFlow<TimetableImportState>(TimetableImportState.Idle)
    val timetableImportState: StateFlow<TimetableImportState> = timetableImport
    private var importJob: Job? = null
    private var importVersion = 0L
    private val mutation = MutableStateFlow(TimeMutationState())
    val mutationState: StateFlow<TimeMutationState> = mutation
    private var retryAction: (() -> Unit)? = null

    private val readRetries = MutableStateFlow(0)
    val uiState: StateFlow<TimeUiState> = com.swan1127.repland.ui.state.recoverableRead(
        initial = TimeUiState(), retries = readRetries, errorMessage = "时间设置读取失败，请重试；未删除课程或例外。",
    ) {
        combine(timeRepository.observeWeeklyBlocks(), timeRepository.observeDateOverrides(),
            timeRepository.observeTimeConstraintSettings(), timeRepository.observeClassPeriodClock()) {
            weeklyBlocks, dateOverrides, settings, classPeriodClock ->
            TimeUiState(weeklyBlocks, dateOverrides, settings.semesterFirstWeekMonday, settings.updatedAtEpochMillis,
                classPeriodClock = classPeriodClock, isLoading = false, hasLoaded = true)
        }
    }.combine(timetableImport) { read, importState ->
        read.value.copy(isLoading = read.isLoading, hasLoaded = read.hasLoaded, readError = read.error, timetableImport = importState)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TimeUiState(),
        )

    fun retryRead() { if (!uiState.value.isLoading && uiState.value.readError != null) readRetries.value++ }

    fun saveWeeklyBlock(draft: WeeklyTimeBlockDraft) = saveWeeklyBlock(draft) {}

    fun saveWeeklyBlock(draft: WeeklyTimeBlockDraft, onSaved: () -> Unit) {
        mutate(TimeMutationKind.WEEKLY_SAVE, draft.id, "每周时间已保存", onSaved) {
            require(TimeBlockValidator.isValid(draft))
            timeRepository.saveWeeklyBlock(draft)
        }
    }

    /** 一页式新增日程的重复路径：每天=七块、每周=勾选的星期各一块，一次写入一次回执。 */
    fun saveWeeklyBlocks(drafts: List<WeeklyTimeBlockDraft>, onSaved: () -> Unit = {}) {
        mutate(TimeMutationKind.WEEKLY_SAVE, null, "已加入每周日程", onSaved) {
            require(drafts.isNotEmpty() && drafts.all { TimeBlockValidator.isValid(it) && it.id == null })
            timeRepository.saveWeeklyBlocks(drafts)
        }
    }

    fun deleteWeeklyBlock(id: String) {
        mutate(TimeMutationKind.WEEKLY_DELETE, id, "每周时间已删除") { timeRepository.deleteWeeklyBlock(id) }
    }

    fun saveDateOverride(draft: DateOverrideDraft) = saveDateOverride(draft) {}

    fun saveDateOverride(draft: DateOverrideDraft, onSaved: () -> Unit) {
        mutate(TimeMutationKind.OVERRIDE_SAVE, draft.id, "单日例外已保存", onSaved) {
            require(TimeBlockValidator.isValid(draft))
            timeRepository.saveDateOverride(draft)
        }
    }

    fun deleteDateOverride(id: String) {
        mutate(TimeMutationKind.OVERRIDE_DELETE, id, "单日例外已删除") { timeRepository.deleteDateOverride(id) }
    }

    fun setWeeklyBlockReminder(id: String, enabled: Boolean) {
        mutate(TimeMutationKind.WEEKLY_SAVE, id, if (enabled) "已开启开始提醒" else "已关闭开始提醒") {
            timeRepository.setWeeklyBlockReminder(id, enabled)
        }
    }

    fun setDateOverrideReminder(id: String, enabled: Boolean) {
        mutate(TimeMutationKind.OVERRIDE_SAVE, id, if (enabled) "已开启开始提醒" else "已关闭开始提醒") {
            timeRepository.setDateOverrideReminder(id, enabled)
        }
    }

    /**
     * 助手登记一档可用时间：跟界面上那条快捷登记同一口径（同样的档就跳过不重复登记），
     * 但不走 mutate——那是界面的一次性提示，成没成读不回来，回执不能替仓储说话。
     */
    suspend fun registerAgentAvailability(value: QuickAvailability): String? {
        if (!value.isValid(LocalDate.now())) return "只能登记今天和以后的时间，起止也要写对。"
        val title = value.title?.trim()?.takeIf(String::isNotBlank) ?: when {
            value.free && value.repeatWeekly -> "每周可用时间"
            value.free -> "本次可用时间"
            value.repeatWeekly -> "每周固定安排"
            else -> "临时安排"
        }
        return try {
            if (value.repeatWeekly) {
                val known = timeRepository.observeWeeklyBlocks().first().any {
                    (it.kind == TimeBlockKind.AVAILABLE) == value.free && it.weekPattern == null &&
                        it.dayOfWeek == value.date.dayOfWeek && it.startMinute == value.startMinute && it.endMinute == value.endMinute
                }
                if (!known) timeRepository.saveWeeklyBlock(
                    WeeklyTimeBlockDraft(
                        title = title,
                        kind = if (value.free) TimeBlockKind.AVAILABLE else TimeBlockKind.OTHER,
                        dayOfWeek = value.date.dayOfWeek,
                        startMinute = value.startMinute,
                        endMinute = value.endMinute,
                        trackId = if (value.free) "focus" else "fixed",
                    ),
                )
            } else {
                val type = if (value.free) DateOverrideType.AVAILABLE else DateOverrideType.BLOCKED
                val known = timeRepository.observeDateOverrides().first().any {
                    it.type == type && it.date == value.date && it.startMinute == value.startMinute && it.endMinute == value.endMinute
                }
                if (!known) timeRepository.saveDateOverride(
                    DateOverrideDraft(
                        title = title,
                        type = type,
                        date = value.date,
                        startMinute = value.startMinute,
                        endMinute = value.endMinute,
                    ),
                )
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "可用档没有保存，原设置仍保留。"
        }
    }

    fun saveSemesterFirstWeekMonday(date: LocalDate?) {
        mutate(TimeMutationKind.SEMESTER_SAVE, null, "学期起点已保存") { timeRepository.saveSemesterFirstWeekMonday(date) }
    }

    /** 助手登记或替换每周固定安排；删除与新增必须落进同一次仓储写入。 */
    suspend fun saveAgentRoutine(removeIds: List<String>, drafts: List<WeeklyTimeBlockDraft>): String? =
        try {
            timeRepository.replaceWeeklyBlocks(removeIds, drafts)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "固定安排没有保存，原设置仍保留。"
        }

    /**
     * 节次时间表只影响之后的课表换算；改完让预览里的核对勾选失效，
     * 逼着用户重新看一眼新钟点，而不是沿用旧钟点的确认。
     */
    fun saveClassPeriodClock(clock: ClassPeriodClock, retimeCourses: Boolean = true) {
        mutate(TimeMutationKind.CLOCK_SAVE, null, "节次时间已保存",
            onSaved = { updateReview { it.copy(clockAcknowledged = false, error = null) } }) {
            require(ClassPeriodClock.isValid(clock.renumbered()))
            timeRepository.saveClassPeriodClock(clock, retimeCourses)
        }
    }

    private fun mutate(kind: TimeMutationKind, targetId: String?, receipt: String, onSaved: () -> Unit = {}, action: suspend () -> Unit) {
        if (mutation.value.busy || mutation.value.receipt != null ||
            (timetableImport.value as? TimetableImportState.Review)?.saving == true) return
        if (uiState.value.readError != null || (uiState.value.hasLoaded && uiState.value.isLoading)) {
            mutation.value = TimeMutationState(kind, targetId, error = "请先重新读取时间设置；本次未保存，输入仍保留。")
            retryAction = { mutate(kind, targetId, receipt, onSaved, action) }
            return
        }
        mutation.value = TimeMutationState(kind, targetId, busy = true)
        retryAction = { mutate(kind, targetId, receipt, onSaved, action) }
        viewModelScope.launch {
            try {
                action()
                retryAction = null
                mutation.value = TimeMutationState(kind, targetId, receipt = receipt)
                onSaved()
            } catch (cancelled: CancellationException) {
                retryAction = null
                mutation.value = TimeMutationState()
                throw cancelled
            } catch (_: Exception) {
                mutation.value = TimeMutationState(kind, targetId, error = "时间设置未保存。输入仍保留，请重试；若原记录已移除，请取消并重新打开。")
            }
        }
    }

    fun retryMutation() { retryAction?.invoke() }
    fun resetMutation() {
        if (!mutation.value.busy) { retryAction = null; mutation.value = TimeMutationState() }
    }

    fun readTimetable(uri: Uri) {
        if ((timetableImport.value as? TimetableImportState.Review)?.saving == true || mutation.value.busy) return
        importJob?.cancel()
        val version = ++importVersion
        timetableImport.value = TimetableImportState.Reading
        importJob = viewModelScope.launch {
            try {
                val courses = parseTimetable(uri)
                if (version != importVersion) return@launch
                timetableImport.value = if (courses.isEmpty()) {
                    TimetableImportState.Failed("未识别到课程，请确认这是星期与节次网格形式的课表 PDF")
                } else if (courses.map(ImportedCourse::id).distinct().size != courses.size) {
                    TimetableImportState.Failed("课程识别身份重复，请换一个课表 PDF 后重试")
                } else TimetableImportState.Review(courses)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == importVersion) timetableImport.value = TimetableImportState.Failed(
                    "无法识别此课表，请检查文件权限和 PDF 格式后重新选择；没有写入课程",
                )
            }
        }
    }

    fun selectImportedCourse(id: String, selected: Boolean) {
        updateReview { review ->
            if (review.courses.none { it.id == id }) review
            else review.copy(selectedIds = if (selected) review.selectedIds + id else review.selectedIds - id, error = null)
        }
    }

    fun acknowledgeImportClock(acknowledged: Boolean) {
        updateReview { it.copy(clockAcknowledged = acknowledged, error = null) }
    }

    fun updateImportedCourse(course: ImportedCourse) {
        updateReview { review ->
            val draft = uiState.value.classPeriodClock.toWeeklyTimeBlockDraft(course)
            if (review.courses.none { it.id == course.id } || draft == null || !TimeBlockValidator.isValid(draft)) {
                review.copy(error = "课程名称或节次无效，请修改后重试")
            } else review.copy(courses = review.courses.map { if (it.id == course.id) course else it },
                clockAcknowledged = false, error = null)
        }
    }

    private fun updateReview(transform: (TimetableImportState.Review) -> TimetableImportState.Review) {
        val review = timetableImport.value as? TimetableImportState.Review ?: return
        if (!review.saving) timetableImport.value = transform(review)
    }

    fun confirmTimetableImport() {
        val review = timetableImport.value as? TimetableImportState.Review ?: return
        if (review.saving) return
        if (uiState.value.readError != null || (uiState.value.hasLoaded && uiState.value.isLoading)) {
            timetableImport.value = review.copy(error = "请先重新读取时间设置；导入预览仍保留，没有写入课程。")
            return
        }
        val selected = review.courses.filter { it.id in review.selectedIds }
        if (!review.clockAcknowledged || selected.isEmpty()) {
            timetableImport.value = review.copy(error = "请至少选择一门课程并核对节次时间")
            return
        }
        val clock = uiState.value.classPeriodClock
        val drafts = selected.map(clock::toWeeklyTimeBlockDraft)
        if (drafts.any { it == null || !TimeBlockValidator.isValid(it) }) {
            timetableImport.value = review.copy(error = "所选课程名称或节次无效，请编辑后重试；节次超出时间表时先调整节次时间")
            return
        }
        timetableImport.value = review.copy(saving = true, error = null)
        importJob = viewModelScope.launch {
            try {
                val added = timeRepository.importWeeklyBlocks(drafts.filterNotNull())
                timetableImport.value = TimetableImportState.Completed(added, selected.size - added)
            } catch (cancelled: CancellationException) {
                timetableImport.value = review
                throw cancelled
            } catch (_: Exception) {
                timetableImport.value = review.copy(error = "课程未保存，预览仍保留；请重试")
            }
        }
    }

    fun clearTimetableImport() {
        if ((timetableImport.value as? TimetableImportState.Review)?.saving == true) return
        ++importVersion
        importJob?.cancel()
        timetableImport.value = TimetableImportState.Idle
    }

    class Factory(
        private val timeRepository: TimeRepository,
        private val timetableImporter: PdfTimetableImporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(TimeViewModel::class.java))
            return TimeViewModel(timeRepository, timetableImporter) as T
        }
    }
}
