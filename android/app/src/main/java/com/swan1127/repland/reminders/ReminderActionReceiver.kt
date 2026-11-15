package com.swan1127.repland.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import com.swan1127.repland.ReplandApplication
import com.swan1127.repland.domain.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 提醒通知上的就地动作：不用先解锁进 App 再翻到这一件。
 * 「开始专注」直接开一轮并把常驻进度通知接上，「顺延」走的是任务页那颗同一个按钮的写入口径。
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(LocalReminderScheduler.EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as ReplandApplication).appContainer
                if (intent.action == ACTION_START_FOCUS) {
                    val sessions = container.executionSessionRepository
                    val segmentId = intent.getStringExtra(LocalReminderScheduler.EXTRA_SEGMENT_ID)
                    runCatching {
                        if (segmentId != null) sessions.start(segmentId) else sessions.startForTask(taskId)
                    }
                    // 以库里的会话为准：写成了一轮就接上常驻进度通知，没写成也不能凭空画一条。
                    sessions.observeActive().first()?.let {
                        FocusProgressNotifier(context.applicationContext).publish(it, System.currentTimeMillis())
                    }
                } else if (intent.action == ACTION_POSTPONE) {
                    runCatching { container.taskRepository.confirmStatus(taskId, TaskStatus.POSTPONED) }
                }
                NotificationManagerCompat.from(context).cancel(
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_START_FOCUS = "com.swan1127.repland.REMINDER_START_FOCUS"
        const val ACTION_POSTPONE = "com.swan1127.repland.REMINDER_POSTPONE"
        private const val EXTRA_NOTIFICATION_ID = "reminder_notification_id"

        /** 两个按钮要各自留存：同一份提醒 + 同一个动作定位一条 PendingIntent。 */
        fun pendingIntent(
            context: Context,
            reminder: Intent,
            taskId: String,
            action: String,
            notificationId: Int,
        ): PendingIntent {
            val origin = reminder.dataString ?: "repland://local-reminder"
            return PendingIntent.getBroadcast(
                context,
                "$origin/$action".hashCode(),
                Intent(context, ReminderActionReceiver::class.java).apply {
                    this.action = action
                    data = Uri.parse("$origin/$action")
                    putExtra(LocalReminderScheduler.EXTRA_TASK_ID, taskId)
                    putExtra(
                        LocalReminderScheduler.EXTRA_SEGMENT_ID,
                        reminder.getStringExtra(LocalReminderScheduler.EXTRA_SEGMENT_ID),
                    )
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
