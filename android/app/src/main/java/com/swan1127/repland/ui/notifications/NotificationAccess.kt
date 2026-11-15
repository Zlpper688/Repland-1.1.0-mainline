package com.swan1127.repland.ui.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 「能不能发通知」不是一个布尔值，两条缺口的修法完全不同：运行时权限可以当场申请，
 * App 通知总开关被关时 requestPermission 会立刻回 false 且永不弹界面——把它当成
 * 「没给权限」去要，就是一条永久静默的死路。
 */
enum class NotificationAccess {
    Allowed,
    NeedsPermission,
    BlockedInSettings,
}

fun Context.notificationAccess(): NotificationAccess = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED -> NotificationAccess.NeedsPermission

    !NotificationManagerCompat.from(this).areNotificationsEnabled() -> NotificationAccess.BlockedInSettings

    else -> NotificationAccess.Allowed
}

class NotificationAccessHandle internal constructor(
    private val readAccess: () -> NotificationAccess,
    private val askForPermission: () -> Unit,
    private val openNotificationSettings: () -> Unit,
    private val holdPending: (() -> Unit) -> Unit,
) {
    val access: NotificationAccess get() = readAccess()

    /**
     * 通知是这个功能成立的前提时用这条：能发就立刻执行，缺什么就当场要一次，
     * 拿到之后再接着执行；要不到就不执行——调用方拿到的 false 就是「现在别装作成功了」。
     */
    fun ensuring(action: () -> Unit): Boolean {
        val current = access
        if (current == NotificationAccess.Allowed) {
            action()
            return true
        }
        // 存的是「再确认一次才执行」而不是动作本身：弹窗回来的那会儿权限可能已经被撤回，
        // 也可能用户在别处改好了——以执行那一刻的真实状态为准，不拿发起时的旧结论。
        holdPending { if (access == NotificationAccess.Allowed) action() }
        if (current == NotificationAccess.NeedsPermission) askForPermission() else openNotificationSettings()
        return false
    }

    /** 功能本身照做、通知只是顺带时用这条：缺权限就顺手要一次，但不拦住操作。 */
    fun askIfMissing() {
        ensuring {}
    }
}

@Composable
fun rememberNotificationAccess(): NotificationAccessHandle {
    val context = LocalContext.current
    var access by remember { mutableStateOf(context.notificationAccess()) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 系统设置里改完是「人回来了」，没有任何回调；只能靠 ON_RESUME 重新观测，
    // 否则界面会一直停在用户早就改好的那个旧结论上。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = context.notificationAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun settle() {
        access = context.notificationAccess()
        // 要不要真的执行由闸门自己按当下状态判定，这里只负责「人回来了，把欠的那件事交回去」。
        val action = pending
        pending = null
        action?.invoke()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { settle() }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { settle() }

    return remember(context) {
        NotificationAccessHandle(
            readAccess = { access },
            askForPermission = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            openNotificationSettings = {
                settingsLauncher.launch(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            holdPending = { action -> pending = action },
        )
    }
}

/** 被拒/被关时说的那句真话——三态各有各的原因，不能都写成「没给权限」。 */
fun NotificationAccess.refusalReason(): String = when (this) {
    NotificationAccess.Allowed -> ""
    NotificationAccess.NeedsPermission -> "还没允许发通知，到点不会提醒"
    NotificationAccess.BlockedInSettings -> "通知在系统设置里被关掉了，到点不会提醒"
}
