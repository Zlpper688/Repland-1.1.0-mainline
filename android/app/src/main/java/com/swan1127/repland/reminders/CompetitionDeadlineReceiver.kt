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
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 响铃时再核对追踪现状：已取消关注/关提醒/状态不算数的直接吞掉，不出通知。 */
class CompetitionDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELIVER_COMPETITION) return
        val instanceKey = intent.getStringExtra(EXTRA_INSTANCE_KEY) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as ReplandApplication).appContainer
                val tracking = runCatching { container.competitionTrackingRepository.get(instanceKey) }
                    .getOrNull() ?: return@launch
                val deadline = tracking.deadlineEpochDay?.let { LocalDate.ofEpochDay(it) } ?: return@launch
                if (!tracking.remindEnabled || !tracking.status.remindersActive) return@launch
                if (deadline.isBefore(LocalDate.now())) return@launch
                if (canPostNotifications(context)) post(context, intent, tracking, deadline)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun post(context: Context, intent: Intent, tracking: com.swan1127.repland.domain.competition.CompetitionTracking, deadline: LocalDate) {
        val isEve = intent.getStringExtra(EXTRA_VARIANT) == CompetitionReminderScheduler.VARIANT_EVE
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentTitle = if (isEve) {
            context.getString(R.string.reminder_comp_eve_title, tracking.title)
        } else {
            context.getString(R.string.reminder_comp_day_title, tracking.title)
        }
        val content = if (isEve) {
            context.getString(R.string.reminder_comp_eve_content, formatDeadline(deadline))
        } else {
            context.getString(R.string.reminder_comp_day_content, formatDeadline(deadline))
        }
        val notification = NotificationCompat.Builder(context, LocalReminderScheduler.EVENT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentTitle)
            .setContentText(content)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(intent.dataString.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission may be revoked after the check above.
        }
    }

    private fun formatDeadline(deadline: LocalDate): String =
        "${deadline.monthValue}月${deadline.dayOfMonth}日"

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_DELIVER_COMPETITION = "com.swan1127.repland.DELIVER_COMPETITION_DEADLINE"
        const val EXTRA_VARIANT = "comp_deadline_variant"
        const val EXTRA_INSTANCE_KEY = "comp_instance_key"
    }
}
