package com.swan1127.repland.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.swan1127.repland.domain.competition.CompetitionTracking
import java.time.LocalDate
import java.time.ZoneId

/**
 * 比赛截止提醒：与计划提醒（LocalReminderScheduler）解耦的独立管线。
 * 只重摆自己的闹钟：追踪列表每变一次就全量 cancel→重排，状态不活跃的自然不再响。
 */
class CompetitionReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun sync(tracking: List<CompetitionTracking>) {
        tracking.forEach { cancel(it.instanceKey) }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        tracking
            .filter { it.remindEnabled && it.status.remindersActive && it.deadlineEpochDay != null }
            .forEach { item ->
                val deadline = LocalDate.ofEpochDay(item.deadlineEpochDay!!)
                if (deadline.isBefore(today)) return@forEach
                val eve = deadline.minusDays(1)
                    .atTime(EVE_REMIND_HOUR, EVE_REMIND_MINUTE).atZone(zone).toInstant().toEpochMilli()
                val day = deadline
                    .atTime(DAY_REMIND_HOUR, DAY_REMIND_MINUTE).atZone(zone).toInstant().toEpochMilli()
                if (eve > now) schedule(VARIANT_EVE, item, eve)
                if (day > now) schedule(VARIANT_DAY, item, day)
            }
    }

    private fun schedule(variant: String, item: CompetitionTracking, triggerAtMillis: Long) {
        val intent = Intent(context, CompetitionDeadlineReceiver::class.java).apply {
            action = CompetitionDeadlineReceiver.ACTION_DELIVER_COMPETITION
            data = Uri.Builder()
                .scheme("repland")
                .authority("comp-deadline")
                .appendPath(variant)
                .appendPath(item.instanceKey)
                .build()
            putExtra(CompetitionDeadlineReceiver.EXTRA_VARIANT, variant)
            putExtra(CompetitionDeadlineReceiver.EXTRA_INSTANCE_KEY, item.instanceKey)
        }
        setAlarm(PendingIntent.getBroadcast(context, 0, intent, PENDING_FLAGS), triggerAtMillis)
    }

    private fun cancel(instanceKey: String) {
        listOf(VARIANT_EVE, VARIANT_DAY).forEach { variant ->
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CompetitionDeadlineReceiver::class.java).apply {
                    action = CompetitionDeadlineReceiver.ACTION_DELIVER_COMPETITION
                    data = Uri.Builder()
                        .scheme("repland")
                        .authority("comp-deadline")
                        .appendPath(variant)
                        .appendPath(instanceKey)
                        .build()
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return@forEach
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    /** 精确闹钟权限被收走时退回非精确：提醒仍会响，只是可能晚几分钟。 */
    private fun setAlarm(operation: PendingIntent, triggerAtMillis: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    companion object {
        const val VARIANT_EVE = "eve"
        const val VARIANT_DAY = "day"
        const val EVE_REMIND_HOUR = 20
        const val EVE_REMIND_MINUTE = 0
        const val DAY_REMIND_HOUR = 9
        const val DAY_REMIND_MINUTE = 0
        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
