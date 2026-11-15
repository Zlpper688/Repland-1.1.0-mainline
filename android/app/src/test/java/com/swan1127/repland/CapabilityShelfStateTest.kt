package com.swan1127.repland

import com.swan1127.repland.ui.mine.CapabilityState
import com.swan1127.repland.ui.mine.cloudCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 云端 AI 货架状态必须与群聊 cloudEnabled 同口径：四个条件缺一不可，构建不支持就直说「暂不支持」。 */
class CapabilityShelfStateTest {
    @Test fun connected_only_when_all_four_hold() {
        assertEquals(
            CapabilityState.CONNECTED,
            cloudCapabilityState(aiEnabled = true, consented = true, hasApiKey = true, remoteSupported = true),
        )
    }

    @Test fun any_missing_gate_is_not_connected() {
        assertEquals(CapabilityState.NOT_CONNECTED, cloudCapabilityState(false, true, true, true))
        assertEquals(CapabilityState.NOT_CONNECTED, cloudCapabilityState(true, false, true, true))
        assertEquals(CapabilityState.NOT_CONNECTED, cloudCapabilityState(true, true, false, true))
    }

    @Test fun unsupported_build_is_unavailable_not_a_fake_checkmark() {
        assertEquals(CapabilityState.UNAVAILABLE, cloudCapabilityState(true, true, true, false))
        assertEquals(CapabilityState.UNAVAILABLE, cloudCapabilityState(false, false, false, false))
    }
}