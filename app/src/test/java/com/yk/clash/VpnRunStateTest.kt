package com.yk.clash

import org.junit.Assert.*
import org.junit.Test

class VpnRunStateTest {
    @Test fun duplicateStartDoesNotInvalidateConnectionInProgress() {
        val state = VpnRunState()
        val first = state.start()!!
        assertNull(state.start()); assertTrue(state.current(first))
    }
    @Test fun stopCancelsOldWorkAndNewStartSupersedesStopCallback() {
        val state = VpnRunState()
        val first = state.start()!!
        val stop = state.stop()
        assertFalse(state.current(first)); assertTrue(state.sameVersion(stop))
        val second = state.start()!!
        assertFalse(state.sameVersion(stop)); assertTrue(state.current(second))
        assertFalse(state.current(first))
    }
    @Test fun stopDuringDelayedRetryPreventsRetryFromConnecting() {
        val state = VpnRunState()
        state.start(); state.stop()
        val retry = state.start()!!
        state.stop()
        assertFalse(state.current(retry))
    }
}
