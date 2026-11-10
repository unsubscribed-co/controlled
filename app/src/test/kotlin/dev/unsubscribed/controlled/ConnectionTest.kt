package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTest {
    private fun run(from: Status, vararg events: Event) = events.fold(from, ::next)

    @Test fun aTvTurnedOffAfterAWakeIsWaitedForNotAFailedWake() {
        // Turn on, it connects, later the TV is turned off and attempts fail (2026-11-10 on Juan's LG).
        val s = run(Status.Waiting(), Event.WakeStarted, Event.Connected, Event.Ended, Event.Failed)
        assertEquals(Status.Waiting(Why.Unreachable), s)
    }

    @Test fun aWakeThatNeverConnectsShowsTheHintUntilItConnects() {
        val failed = run(Status.Connecting, Event.WakeStarted, Event.Failed, Event.Failed, Event.WakeTimedOut)
        assertEquals(Status.Waiting(Why.WakeFailed), failed)
        assertEquals(Status.Waiting(Why.WakeFailed), run(failed, Event.Failed, Event.Searching(false), Event.SearchDone))
        assertEquals(Status.Connected(), run(failed, Event.Connected))
        assertEquals(Status.Waking, run(failed, Event.WakeStarted)) // trying again
    }

    @Test fun aDroppedSessionReconnectsQuietlyThenWaits() {
        assertEquals(Status.Connecting, run(Status.Connected(), Event.Ended))
        assertEquals(Status.Waiting(), run(Status.Connected(), Event.Ended, Event.Failed))
    }

    @Test fun onlyTheFirstSearchIsShown() {
        assertEquals(Status.Waiting(searching = true), run(Status.Waiting(), Event.Searching(true)))
        assertEquals(Status.Waiting(), run(Status.Waiting(), Event.Searching(true), Event.SearchDone, Event.Searching(false)))
        assertEquals(Status.Connecting, run(Status.Connecting, Event.Searching(true)))
    }

    @Test fun standbyIsOnlyAboutAConnectedTv() {
        assertEquals(Status.Connected(asleep = true), run(Status.Connected(), Event.Asleep(true)))
        assertEquals(Status.Connected(), run(Status.Connected(asleep = true), Event.Asleep(false)))
        assertEquals(Status.Waiting(), run(Status.Waiting(), Event.Asleep(true)))
    }

    @Test fun noWifiHoldsUntilWifiIsBack() {
        val s = run(Status.Connected(), Event.WifiLost, Event.Failed, Event.WakeStarted)
        assertEquals(Status.NoWifi, s)
        assertEquals(Status.Connecting, run(s, Event.WifiBack))
        assertEquals(Status.Connected(), run(Status.Connected(), Event.WifiBack))
    }

    @Test fun aRefusalStaysUntilAnotherTvOrRepair() {
        val s = run(Status.Connecting, Event.Refused(forgotten = true), Event.Failed, Event.WifiLost, Event.WakeStarted)
        assertEquals(Status.Refused(forgotten = true), s)
        assertEquals(Status.Connecting, run(s, Event.Reset))
    }
}
