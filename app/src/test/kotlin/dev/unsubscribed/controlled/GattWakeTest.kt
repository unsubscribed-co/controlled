package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GattWakeTest {
    // BleModel$BleAction$Request.getBytes: [actionId][subActionId][actionData...].
    @Test fun setWakeUpIsActionId11Sub1NoData() {
        assertArrayEquals(byteArrayOf(11, 1), GattWake.SET_WAKE_UP)
    }

    // SET_PAIRING send-PIN: [12][2] + PairingData.getBytes [pairCode=2][pin ascii].
    @Test fun pairPinFramesPairCodeThenAscii() {
        assertArrayEquals(byteArrayOf(12, 2, 2, '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte()), GattWake.pairPin("1234"))
    }
}
