package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class IrWakeTest {
    @Test fun necFrameShape() {
        val p = IrWake.nec(0)
        assertEquals(67, p.size)          // 9ms+4.5ms lead, 32 bits x 2, 1 stop
        assertEquals(9000, p[0]); assertEquals(4500, p[1]); assertEquals(560, p[66])
    }

    @Test fun necEncodesBitsMsbFirst() {
        // top bit set -> first data space is a "1" (1690us); everything else "0" (560us)
        val p = IrWake.nec(1 shl 31)
        assertEquals(560, p[2]); assertEquals(1690, p[3])   // bit 31 = 1
        assertEquals(560, p[4]); assertEquals(560, p[5])    // bit 30 = 0
        // bottom bit set -> last data space is a "1"
        val q = IrWake.nec(1)
        assertEquals(1690, q[65]); assertEquals(560, q[63])
    }
}
