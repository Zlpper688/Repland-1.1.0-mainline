package com.swan1127.repland.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccessHandleTest {
    private var access = NotificationAccess.Allowed
    private var permissionAsked = 0
    private var settingsOpened = 0
    private var held: (() -> Unit)? = null

    private fun handle() = NotificationAccessHandle(
        readAccess = { access },
        askForPermission = { permissionAsked += 1 },
        openNotificationSettings = { settingsOpened += 1 },
        holdPending = { action -> held = action },
    )

    @Test
    fun `allowed runs immediately and asks for nothing`() {
        var ran = false

        assertTrue(handle().ensuring { ran = true })

        assertTrue(ran)
        assertEquals(0, permissionAsked)
        assertEquals(0, settingsOpened)
        assertNull(held)
    }

    @Test
    fun `missing permission defers the write instead of pretending it succeeded`() {
        access = NotificationAccess.NeedsPermission
        var ran = false

        assertFalse(handle().ensuring { ran = true })

        assertFalse("通知发不出去时不能把提醒写成已开启", ran)
        assertEquals(1, permissionAsked)
        assertEquals(0, settingsOpened)
    }

    @Test
    fun `blocked master switch goes to app settings not the runtime dialog`() {
        // requestPermission 在总开关关闭时立刻回 false 且不弹界面，用它去要是一条永久静默的死路。
        access = NotificationAccess.BlockedInSettings
        var ran = false

        assertFalse(handle().ensuring { ran = true })

        assertFalse(ran)
        assertEquals(0, permissionAsked)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun `the deferred write runs once the answer comes back positive`() {
        access = NotificationAccess.NeedsPermission
        var ran = false
        handle().ensuring { ran = true }

        access = NotificationAccess.Allowed
        held?.invoke()

        assertTrue(ran)
    }

    @Test
    fun `a refused request leaves nothing armed for later`() {
        access = NotificationAccess.NeedsPermission
        var ran = false
        handle().ensuring { ran = true }

        held?.invoke()
        assertFalse("拒绝后不该有任何动作等着被执行", ran)
    }
}
