package com.swan1127.repland.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swan1127.repland.MainActivity
import com.swan1127.repland.R
import com.swan1127.repland.domain.model.ExecutionOutcome
import com.swan1127.repland.domain.model.ExecutionSession

/**
 * 专注期间常驻的一条进度通知：当前事项 + 剩余时间 + 就地的完成/退出。
 * 它只是计时会话的观察者——时间仍以库里的会话为准，进程被系统收走时通知随进程一起消失。
 */
class FocusProgressNotifier(private val context: Context) {

    fun publish(session: ExecutionSession, nowMillis: Long) {
        if (!canPost()) return
        val elapsed = session.elapsedMillis(nowMillis)
        val usedMinutes = ((elapsed + 59_999) / 60_000).toInt()
        val targetMinutes = ((session.targetSeconds + 59) / 60).coerceAtLeast(1)
        val minutesLeft = ((session.targetSeconds * 1_000L - elapsed).coerceAtLeast(0L) / 60_000L).toInt()
        // 通知在通知栏收起时只画得出标题一行（进度条会占掉正文那行），所以把「还剩多久」挂在标题上。
        val (state, detail) = when {
            session.isPaused -> context.getString(R.string.focus_notification_paused) to
                context.getString(R.string.focus_notification_paused_detail, usedMinutes)
            minutesLeft == 0 -> context.getString(R.string.focus_notification_topped) to
                context.getString(R.string.focus_notification_topped_detail)
            else -> context.getString(R.string.focus_notification_running, maxOf(1, minutesLeft)) to
                context.getString(R.string.focus_notification_running_detail)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${session.taskTitle} · $state")
            .setContentText(detail)
            .setProgress(targetMinutes, minOf(usedMinutes, targetMinutes), false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            // 「完成」收下这一轮并标记任务完成；「退出」只结束本轮并保留已投入的分钟，任务状态不动。
            .addAction(0, context.getString(R.string.focus_notification_finish), actionIntent(session, ExecutionOutcome.COMPLETED))
            .addAction(0, context.getString(R.string.focus_notification_exit), actionIntent(session, ExecutionOutcome.CONTINUE))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission may be revoked between the check and the post.
        }
    }

    fun clear() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_REQUEST,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 每条通知的两个按钮要能各自留存，所以按「这一轮 + 这个结果」分别取 requestCode 与 data。 */
    private fun actionIntent(session: ExecutionSession, outcome: ExecutionOutcome): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            session.id.hashCode() * 8 + outcome.ordinal,
            Intent(context, FocusSessionActionReceiver::class.java).apply {
                action = ACTION_FOCUS_ACTION
                data = Uri.parse("repland://focus-progress/${session.id}/${outcome.name}")
                putExtra(EXTRA_SESSION_ID, session.id)
                putExtra(EXTRA_OUTCOME, outcome.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_ID = "focus-progress"
        const val ACTION_FOCUS_ACTION = "com.swan1127.repland.FOCUS_PROGRESS_ACTION"
        const val EXTRA_SESSION_ID = "focus_session_id"
        const val EXTRA_OUTCOME = "focus_outcome"
        private const val OPEN_REQUEST = 7101
        private const val NOTIFICATION_ID = 7100

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.focus_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.focus_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
