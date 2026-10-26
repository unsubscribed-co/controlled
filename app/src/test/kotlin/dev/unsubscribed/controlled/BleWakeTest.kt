package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleWakeTest {
    @Test fun bluetoothAddressIsWifiPlusOne() {
        assertArrayEquals(BleWake.hex("a4ceda61d0d5"), BleWake.bluetoothAddress("A4:CE:DA:61:D0:D4"))
        assertArrayEquals(BleWake.hex("a4ceda61d100"), BleWake.bluetoothAddress("a4-ce-da-61-d0-ff"))
        assertNull(BleWake.bluetoothAddress("nope"))
    }

    @Test fun thinqPrefixParses() {
        assertArrayEquals(byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x07, 0x00), BleWake.hex(BleWake.THINQ_PREFIX))
        assertNull(BleWake.hex("zz"))
    }
}
