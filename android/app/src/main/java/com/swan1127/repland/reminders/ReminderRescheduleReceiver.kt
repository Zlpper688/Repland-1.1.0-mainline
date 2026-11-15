package com.swan1127.repland.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swan1127.repland.ReplandApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores local alarms after device restart, clock, or timezone changes without using network.
 * A phone that was off across midnight missed its 00:05 rollover, so this path picks up the day too.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )
        ) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as ReplandApplication
                runCatching { app.appContainer.autonomyRepository.reconcile() }
                LocalReminderScheduler(context.applicationContext).syncFromDatabase(app.appContainer)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
