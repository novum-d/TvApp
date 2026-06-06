package io.novumd.tvapp.tv

import io.novumd.tvapp.ble.toDisplayHex
import org.junit.Assert.assertEquals
import org.junit.Test

class TvCommandTest {
    @Test
    fun payload_returnsInspectableCommandBytes() {
        assertEquals("01", TvCommand.Power.payload().toDisplayHex())
        assertEquals("02", TvCommand.VolumeUp.payload().toDisplayHex())
        assertEquals("03", TvCommand.VolumeDown.payload().toDisplayHex())
        assertEquals("04", TvCommand.Mute.payload().toDisplayHex())
        assertEquals("05", TvCommand.InputSwitch.payload().toDisplayHex())
    }
}
