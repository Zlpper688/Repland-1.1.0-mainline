package com.swan1127.repland.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swan1127.repland.MainActivity
import com.swan1127.repland.R
import com.swan1127.repland.ReplandApplication
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.ReminderKind
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import com.swan1127.repland.ui.widget.HomeWidgetProvider
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Validates an alarm against current local data before showing a notification. */
class LocalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocalReminderScheduler.ACTION_DAY_ROLLOVER) {
            rollForward(context)
            return
        }
        if (intent.action != LocalReminderScheduler.ACTION_DELIVER_REMINDER) return
        val kind = intent.getStringExtra(LocalReminderScheduler.EXTRA_KIND)
            ?.let { value -> runCatching { ReminderKind.valueOf(value) }.getOrNull() } ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as ReplandApplication
                val container = app.appContainer
                val settings = container.reminderSettingsRepository.observe().first()
                val tasks = container.taskRepository.observeTasks().first()
                val currentPlan = container.planRepository.observeCurrentPlan().first()
                val weeklyBlocks = container.timeRepository.observeWeeklyBlocks().first()
                val dateOverrides = container.timeRepository.observeDateOverrides().first()
                if (!settings.isEnabled ||
                    !isStillEligible(intent, kind, currentPlan, tasks, weeklyBlocks, dateOverrides)
                ) return@launch
                if (canPostNotifications(context)) {
                    post(context, intent, kind)
                }
                if (kind == ReminderKind.DAILY_REVIEW || kind == ReminderKind.WEEKLY_BLOCK_START) {
                    LocalReminderScheduler(context.applicationContext).syncFromDatabase(container)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 新的一天在后台把昨天没做完的接进今天，用户不必打开 App 再手动重排。
     * 顺序不能反：今天新排出来的段在昨天根本不存在，先摆闹钟就等于不给它们响。
     */
    private fun rollForward(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as ReplandApplication
                // 整理失败也不能挡住重摆闹钟：昨天的段已经过期，今天的段一条都还没响。
                runCatching { app.appContainer.autonomyRepository.reconcile() }
                LocalReminderScheduler(context.applicationContext).syncFromDatabase(app.appContainer)
                HomeWidgetProvider.requestRefresh(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isStillEligible(
        intent: Intent,
        kind: ReminderKind,
        currentPlan: com.swan1127.repland.domain.model.ConfirmedPlan?,
        tasks: List<Task>,
        weeklyBlocks: List<WeeklyTimeBlock>,
        dateOverrides: List<DateOverride>,
    ): Boolean = when (kind) {
        ReminderKind.SEGMENT_START -> {
            val planId = intent.getStringExtra(LocalReminderScheduler.EXTRA_PLAN_ID)
            val segmentId = intent.getStringExtra(LocalReminderScheduler.EXTRA_SEGMENT_ID)
            val taskId = intent.getStringExtra(LocalReminderScheduler.EXTRA_TASK_ID)
            currentPlan?.let { plan ->
                plan.id == planId &&
                    plan.segments.any {
                        it.id == segmentId && it.taskId == taskId && it.reminderEnabled
                    } &&
                    tasks.firstOrNull { it.id == taskId }?.status?.isActive == true
            } == true
        }

        ReminderKind.DEADLINE -> {
            val taskId = intent.getStringExtra(LocalReminderScheduler.EXTRA_TASK_ID)
            val dueEpochDay = intent.getLongExtra(
                LocalReminderScheduler.EXTRA_DUE_DATE_EPOCH_DAY,
                LocalReminderScheduler.NO_DUE_DATE,
            )
            tasks.firstOrNull { it.id == taskId }?.let { task ->
                task.status.isActive && task.dueDate?.toEpochDay() == dueEpochDay
            } == true
        }

        ReminderKind.DAILY_REVIEW -> currentPlan != null

        ReminderKind.WEEKLY_BLOCK_START -> {
            val blockId = intent.getStringExtra(LocalReminderScheduler.EXTRA_KEY)
                ?.removePrefix(WEEKLY_KEY_PREFIX)
            weeklyBlocks.firstOrNull { it.id == blockId }?.reminderEnabled == true
        }

        ReminderKind.OVERRIDE_START -> {
            val overrideId = intent.getStringExtra(LocalReminderScheduler.EXTRA_KEY)
                ?.removePrefix(OVERRIDE_KEY_PREFIX)
            dateOverrides.firstOrNull { it.id == overrideId }?.let { override ->
                override.reminderEnabled && override.date == LocalDate.now()
            } == true
        }
    }

    private fun post(context: Context, intent: Intent, kind: ReminderKind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val title = intent.getStringExtra(LocalReminderScheduler.EXTRA_TASK_NAME)
        // 提前响的那几分钟要说清楚：看一眼就知道来不来得及，不用点进去。
        val startMillis = intent.getLongExtra(
            LocalReminderScheduler.EXTRA_START_AT_MILLIS,
            LocalReminderScheduler.NO_START_TIME,
        )
        val leadMinutes: Int? = if (startMillis == LocalReminderScheduler.NO_START_TIME) {
            null
        } else {
            ((startMillis - System.currentTimeMillis() + 59_999) / 60_000L).toInt().coerceAtLeast(0)
        }
        val isEarly = (leadMinutes ?: 0) > 0
        val countdown = leadMinutes?.let {
            if (isEarly) {
                context.getString(R.string.reminder_start_in, it)
            } else {
                context.getString(R.string.reminder_start_now)
            }
        }
        val channelId = when (kind) {
            ReminderKind.SEGMENT_START,
            ReminderKind.WEEKLY_BLOCK_START,
            ReminderKind.OVERRIDE_START,
            -> LocalReminderScheduler.EVENT_CHANNEL_ID

            else -> LocalReminderScheduler.CHANNEL_ID
        }
        val (contentTitle, content) = when (kind) {
            ReminderKind.SEGMENT_START -> context.getString(
                if (isEarly) R.string.reminder_upcoming_title else R.string.reminder_start_title,
                title,
            ) to (countdown ?: context.getString(R.string.reminder_start_content))

            ReminderKind.WEEKLY_BLOCK_START, ReminderKind.OVERRIDE_START -> context.getString(
                if (isEarly) R.string.reminder_upcoming_title else R.string.reminder_event_title,
                title,
            ) to (countdown ?: context.getString(R.string.reminder_event_content))

            ReminderKind.DEADLINE -> {
                val epochDay = intent.getLongExtra(LocalReminderScheduler.EXTRA_DUE_DATE_EPOCH_DAY, 0)
                context.getString(R.string.reminder_deadline_title, title) to
                    context.getString(R.string.reminder_deadline_content, LocalDate.ofEpochDay(epochDay))
            }

            ReminderKind.DAILY_REVIEW -> context.getString(R.string.reminder_review_title) to
                context.getString(R.string.reminder_review_content)
        }
        // 每日回顾走独立的 PendingIntent（请求码区分开），并带 deep link extra 直达回顾决策面；
        // SINGLE_TOP 让已在前台时复用实例走 onNewIntent，而不是在旧实例上再叠一个。
        val openReview = kind == ReminderKind.DAILY_REVIEW
        val openApp = PendingIntent.getActivity(
            context,
            if (openReview) DAILY_REVIEW_OPEN_REQUEST_CODE else 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .also { target ->
                    if (openReview) target.putExtra(LocalReminderScheduler.EXTRA_OPEN_DAILY_REVIEW, true)
                },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationId = intent.dataString.hashCode()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentTitle)
            .setContentText(content)
            .setContentIntent(openApp)
            .setAutoCancel(true)
        // 提醒里带任务的那一类，就地给出「开始专注」和「顺延」：这两件事原本要在任务页各点一次。
        intent.getStringExtra(LocalReminderScheduler.EXTRA_TASK_ID)?.let { taskId ->
            builder
                .addAction(
                    0,
                    context.getString(R.string.reminder_action_focus),
                    ReminderActionReceiver.pendingIntent(
                        context, intent, taskId, ReminderActionReceiver.ACTION_START_FOCUS, notificationId,
                    ),
                )
                .addAction(
                    0,
                    context.getString(R.string.reminder_action_postpone),
                    ReminderActionReceiver.pendingIntent(
                        context, intent, taskId, ReminderActionReceiver.ACTION_POSTPONE, notificationId,
                    ),
                )
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Notification permission may be revoked after the check above.
        }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        const val WEEKLY_KEY_PREFIX = "weekly:"
        const val OVERRIDE_KEY_PREFIX = "override:"
        /** 与普通「打开应用」的 PendingIntent 区分开，避免 extras 被其他通知互相覆盖。 */
        const val DAILY_REVIEW_OPEN_REQUEST_CODE = 1001
    }
}
