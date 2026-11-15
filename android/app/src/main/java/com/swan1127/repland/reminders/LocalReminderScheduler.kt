package com.swan1127.repland.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.swan1127.repland.AppContainer
import com.swan1127.repland.domain.model.ConfirmedPlan
import com.swan1127.repland.domain.model.DateOverride
import com.swan1127.repland.domain.model.LocalReminder
import com.swan1127.repland.domain.model.LocalReminderPlanner
import com.swan1127.repland.domain.model.ReminderKind
import com.swan1127.repland.domain.model.Task
import com.swan1127.repland.domain.model.WeeklyTimeBlock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** Schedules only one-time local alarms derived from confirmed plan and time-setting data. */
class LocalReminderScheduler(
    private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun sync(
        isEnabled: Boolean,
        currentPlan: ConfirmedPlan?,
        planHistory: List<ConfirmedPlan>,
        tasks: List<Task>,
        weeklyBlocks: List<WeeklyTimeBlock> = emptyList(),
        dateOverrides: List<DateOverride> = emptyList(),
        semesterFirstWeekMonday: LocalDate? = null,
    ) {
        planHistory.forEach { plan ->
            plan.segments.forEach { segment ->
                cancel(ReminderKind.SEGMENT_START, "${plan.id}:${segment.id}")
            }
        }
        tasks.forEach { task -> cancel(ReminderKind.DEADLINE, task.id) }
        weeklyBlocks.forEach { block -> cancel(ReminderKind.WEEKLY_BLOCK_START, "weekly:${block.id}") }
        dateOverrides.forEach { override -> cancel(ReminderKind.OVERRIDE_START, "override:${override.id}") }
        cancel(ReminderKind.DAILY_REVIEW, DAILY_REVIEW_KEY)
        // 跨天滚入是数据维护，不是提醒：提醒关了也要在午夜后把昨天没做完的接住。
        scheduleDayRollover()
        if (!isEnabled) return

        LocalReminderPlanner.plan(currentPlan, tasks, weeklyBlocks, dateOverrides, semesterFirstWeekMonday)
            .forEach(::schedule)
    }

    /**
     * 每天 00:05 醒一次。用户早上打开 App 之前，昨天的未完成就已经被接进今天，
     * 今天新排出来的工作段也就有了闹钟——不然那些段只在上一天的计划里不存在，没人会给它们响。
     */
    private fun scheduleDayRollover() {
        val zone = ZoneId.systemDefault()
        val triggerAt = LocalDateTime.now(zone).toLocalDate().plusDays(1)
            .atTime(DAY_ROLLOVER_HOUR, DAY_ROLLOVER_MINUTE)
            .atZone(zone)
            .toInstant()
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, LocalReminderReceiver::class.java).apply {
                action = ACTION_DAY_ROLLOVER
                data = Uri.parse("repland://local-reminder/$DAY_ROLLOVER_KEY")
            },
            pendingIntentFlags(),
        )
        setAlarm(operation, triggerAt.toEpochMilli())
    }

    private fun schedule(reminder: LocalReminder) {
        val intent = reminderIntent(reminder.kind, reminder.key).apply {
            putExtra(EXTRA_KIND, reminder.kind.name)
            putExtra(EXTRA_KEY, reminder.key)
            putExtra(EXTRA_START_AT_MILLIS, reminder.startAt?.toEpochMilli() ?: NO_START_TIME)
            putExtra(EXTRA_PLAN_ID, reminder.planId)
            putExtra(EXTRA_SEGMENT_ID, reminder.segmentId)
            putExtra(EXTRA_TASK_ID, reminder.taskId)
            putExtra(EXTRA_TASK_NAME, reminder.taskName)
            putExtra(EXTRA_DUE_DATE_EPOCH_DAY, reminder.dueDate?.toEpochDay() ?: NO_DUE_DATE)
        }
        // 精确闹钟权限被收走时退回非精确：提醒仍会响，只是可能晚几分钟。
        setAlarm(
            PendingIntent.getBroadcast(context, 0, intent, pendingIntentFlags()),
            reminder.triggerAt.toEpochMilli(),
        )
    }

    private fun setAlarm(operation: PendingIntent, triggerAtMillis: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    /** 一切排期变化最后都回到这里：从本地库重读现状，再按现状重摆闹钟。 */
    suspend fun syncFromDatabase(container: AppContainer) {
        sync(
            isEnabled = container.reminderSettingsRepository.observe().first().isEnabled,
            currentPlan = container.planRepository.observeCurrentPlan().first(),
            planHistory = container.planRepository.observePlanHistory().first(),
            tasks = container.taskRepository.observeTasks().first(),
            weeklyBlocks = container.timeRepository.observeWeeklyBlocks().first(),
            dateOverrides = container.timeRepository.observeDateOverrides().first(),
            semesterFirstWeekMonday = container.timeRepository.observeTimeConstraintSettings()
                .first().semesterFirstWeekMonday,
        )
    }

    private fun cancel(kind: ReminderKind, key: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            reminderIntent(kind, key),
            PendingIntent.FLAG_NO_CREATE or immutableFlag(),
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun reminderIntent(kind: ReminderKind, key: String): Intent =
        Intent(context, LocalReminderReceiver::class.java).apply {
            action = ACTION_DELIVER_REMINDER
            data = Uri.Builder()
                .scheme("repland")
                .authority("local-reminder")
                .appendPath(kind.name)
                .appendPath(key)
                .build()
        }

    companion object {
        const val CHANNEL_ID = "confirmed-plan-reminders"

        /**
         * 事件提醒走闹钟音量：通道一旦创建就不可改，铃声是后来补的，所以换代到 v2 并删掉旧通道。
         * CompetitionDeadlineReceiver 等所有事件类通知都发到这里。
         */
        const val EVENT_CHANNEL_ID = "event-alarms-v2"
        private const val LEGACY_EVENT_CHANNEL_ID = "event-alarms"
        const val ACTION_DELIVER_REMINDER = "com.swan1127.repland.DELIVER_REMINDER"
        const val ACTION_DAY_ROLLOVER = "com.swan1127.repland.DAY_ROLLOVER"
        const val EXTRA_KIND = "reminder_kind"
        const val EXTRA_KEY = "reminder_key"
        const val EXTRA_PLAN_ID = "plan_id"
        const val EXTRA_SEGMENT_ID = "segment_id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_NAME = "task_name"
        const val EXTRA_DUE_DATE_EPOCH_DAY = "due_date_epoch_day"
        const val EXTRA_START_AT_MILLIS = "reminder_start_at_millis"
        const val DAILY_REVIEW_KEY = "daily-review"
        /** 每日回顾通知的 deep link extra：直达回顾决策面，而不是只打开裸 MainActivity。 */
        const val EXTRA_OPEN_DAILY_REVIEW = "com.swan1127.repland.extra.OPEN_DAILY_REVIEW"
        const val DAY_ROLLOVER_KEY = "day-rollover"
        const val DAY_ROLLOVER_HOUR = 0
        const val DAY_ROLLOVER_MINUTE = 5
        const val NO_DUE_DATE = Long.MIN_VALUE
        const val NO_START_TIME = Long.MIN_VALUE

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(com.swan1127.repland.R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(com.swan1127.repland.R.string.reminder_channel_description)
                },
            )
            // 到点响铃要能穿过静音：声音挂在闹钟音量上（USAGE_ALARM），铃声用系统默认闹钟音。
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val alarmAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            manager.createNotificationChannel(
                NotificationChannel(
                    EVENT_CHANNEL_ID,
                    context.getString(com.swan1127.repland.R.string.reminder_event_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(com.swan1127.repland.R.string.reminder_event_channel_description)
                    setSound(alarmSound, alarmAttributes)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 80, 120, 80, 120, 400)
                },
            )
            manager.deleteNotificationChannel(LEGACY_EVENT_CHANNEL_ID)
        }

        private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()

        private fun immutableFlag(): Int = PendingIntent.FLAG_IMMUTABLE
    }
}
