package com.swan1127.repland.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swan1127.repland.ReplandApplication
import com.swan1127.repland.domain.model.ExecutionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Applies the round the notification was drawn for, so a stale notification can never end a newer one. */
class FocusSessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FocusProgressNotifier.ACTION_FOCUS_ACTION) return
        val sessionId = intent.getStringExtra(FocusProgressNotifier.EXTRA_SESSION_ID) ?: return
        val outcome = intent.getStringExtra(FocusProgressNotifier.EXTRA_OUTCOME)
            ?.let { raw -> runCatching { ExecutionOutcome.valueOf(raw) }.getOrNull() } ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notifier = FocusProgressNotifier(context.applicationContext)
                val sessions = (context.applicationContext as ReplandApplication).appContainer.executionSessionRepository
                val active = sessions.observeActive().first()?.takeIf { it.id == sessionId }
                if (active == null) {
                    notifier.clear()
                } else if (runCatching { sessions.finish(sessionId, outcome) }.isSuccess) {
                    notifier.clear()
                } else {
                    // 没写成就不收这条：这一轮还在跑，通知继续按库里的时间计时。
                    notifier.publish(active, System.currentTimeMillis())
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
