package dev.unsubscribed.controlled

/** Why the remote is waiting for its TV: just not reachable, or a Turn on that never connected. */
enum class Why { Unreachable, WakeFailed }

/**
 * The remote's link to its TV, one value for everything the status line shows. Every change goes through [next], so the
 * states can't contradict each other (the 'TV didn't wake' hint after turning a woken TV off came from loose flags).
 */
sealed interface Status {
    /** Trying, with nothing to report yet. */
    data object Connecting : Status
    /** Connected; [asleep] when the TV is in standby or its screen is off (Quick Start+ keeps the socket open). */
    data class Connected(val asleep: Boolean = false) : Status
    /** Turn on was pressed: wake shots sent, attempts every second for a while. */
    data object Waking : Status
    /** Not reachable and still retrying; [searching] while the first look for a new address runs. */
    data class Waiting(val why: Why = Why.Unreachable, val searching: Boolean = false) : Status
    data object NoWifi : Status
    /** The TV refused this phone: its certificate changed, or ([forgotten]) it was reset and dropped our key. */
    data class Refused(val forgotten: Boolean = false) : Status
}

sealed interface Event {
    /** Start over: another TV, or the app was away long enough to close the socket. */
    data object Reset : Event
    /** An attempt failed (the loop skips the first, so a quick reconnect never reads as waiting). */
    data object Failed : Event
    data object Connected : Event
    data class Asleep(val asleep: Boolean) : Event
    /** A live session closed: the TV was turned off, or the network dropped. */
    data object Ended : Event
    data object WakeStarted : Event
    /** The wake window passed without connecting. */
    data object WakeTimedOut : Event
    /** A look for the TV at a new address started; [shown] for the first one only, so the text doesn't flip every 15 s. */
    data class Searching(val shown: Boolean) : Event
    data object SearchDone : Event
    data object WifiLost : Event
    data object WifiBack : Event
    data class Refused(val forgotten: Boolean) : Event
}

fun next(s: Status, e: Event): Status = when (e) {
    Event.Reset -> Status.Connecting
    Event.Connected -> Status.Connected()
    is Event.Asleep -> if (s is Status.Connected) Status.Connected(e.asleep) else s
    Event.Ended -> if (s is Status.Connected) Status.Connecting else s
    Event.Failed -> when (s) {
        Status.Connecting -> Status.Waiting()
        // Connected: a late failure from an older attempt, never this session's (that one ends with Ended). Waking keeps
        // waking; Waiting keeps its reason; no Wi-Fi and Refused stay until resolved.
        else -> s
    }
    Event.WakeStarted -> if (s is Status.NoWifi || s is Status.Refused) s else Status.Waking
    Event.WakeTimedOut -> if (s is Status.Waking) Status.Waiting(Why.WakeFailed) else s
    is Event.Searching -> if (s is Status.Waiting) s.copy(searching = e.shown) else s
    Event.SearchDone -> if (s is Status.Waiting) s.copy(searching = false) else s
    Event.WifiLost -> if (s is Status.Refused) s else Status.NoWifi
    Event.WifiBack -> if (s is Status.NoWifi) Status.Connecting else s
    is Event.Refused -> Status.Refused(e.forgotten)
}
