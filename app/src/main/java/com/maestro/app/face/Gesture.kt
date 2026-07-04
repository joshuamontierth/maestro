package com.maestro.app.face

import kotlin.math.abs

/**
 * A facial gesture the app can recognise. The user maps these onto page actions
 * in Settings, so the meaning of "left"/"right" can be swapped freely to match
 * whatever feels natural with their device's front camera (mirroring varies).
 */
enum class GestureType(val label: String) {
    NONE("None (disable this direction)"),
    HEAD_LEFT("Turn head left"),
    HEAD_RIGHT("Turn head right"),
    WINK_LEFT("Wink left eye"),
    WINK_RIGHT("Wink right eye"),
    BLINK_BOTH("Long blink (both eyes)"),
    DOUBLE_BLINK("Double blink"),
    SMILE("Smile");

    companion object {
        fun fromName(name: String?): GestureType =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** Which way a recognised gesture should move the document. */
enum class PageAction { NEXT, PREVIOUS }

/**
 * A single frame's worth of facial measurements distilled from ML Kit.
 * Eye-open/smile values are 0f..1f, or -1f when the detector could not classify them.
 */
data class FaceSignals(
    val present: Boolean,
    val yaw: Float = 0f,           // headEulerAngleY in degrees (+/- = turned to a side)
    val pitch: Float = 0f,         // headEulerAngleX in degrees (+/- = tilted up/down)
    val leftEyeOpen: Float = -1f,  // probability the subject's left eye is open
    val rightEyeOpen: Float = -1f,
    val smiling: Float = -1f       // probability the subject is smiling, or -1f if unknown
)

/** User-tunable detection parameters, persisted via SettingsStore. */
data class GestureConfig(
    val nextGesture: GestureType = GestureType.HEAD_RIGHT,
    val prevGesture: GestureType = GestureType.HEAD_LEFT,
    val yawThresholdDeg: Float = 22f,
    val eyeClosedThreshold: Float = 0.25f, // below this an eye counts as closed
    val eyeOpenThreshold: Float = 0.55f,   // above this an eye counts as open
    val smileThreshold: Float = 0.7f,      // above this the subject counts as smiling
    val dwellMs: Long = 350L,              // held poses (head turn / wink / smile) fire after this
    val cooldownMs: Long = 1200L,          // ignore new triggers this long after firing
    val longBlinkMs: Long = 700L,          // eyes closed this long = a deliberate long blink
    val enabledByDefault: Boolean = true
)

/** Coarse state of the recogniser, surfaced to the UI as a status dot. */
enum class EngineStatus { NO_FACE, READY, ARMING, COOLDOWN }

/** Result of feeding one frame to [GestureEngine]. */
data class GestureOutput(val status: EngineStatus, val fired: PageAction?)

/** Discrete blink events produced by [BlinkTracker] (at most one per closure). */
data class BlinkEvents(val doubleBlink: Boolean, val longBlink: Boolean)

/**
 * Debounced both-eyes blink tracker. Raw per-frame eye probabilities are noisy
 * (single-frame dropouts, wobble around thresholds), so the eyes-closed state is
 * derived with three layers of filtering before any event is emitted:
 *
 *  - **Hysteresis:** the state flips to CLOSED only when *both* eyes drop below the
 *    closed threshold, and back to OPEN only when *either* eye rises above the open
 *    threshold. Readings between the two thresholds never flip the state, so wobble
 *    around a single cutoff can't split one blink into two.
 *  - **Debounce:** a state change must persist for [STATE_DEBOUNCE_MS] *and*
 *    [MIN_STATE_SAMPLES] consecutive frames before it is accepted; a lone
 *    misclassified frame can neither start, end, nor split a closure.
 *  - **Duration classes:** a completed closure counts as a *quick blink* only at
 *    80–400 ms (natural blink territory ends ~400 ms; shorter is noise). Eyes held
 *    closed for the user-set long-blink duration fire a *long blink* the moment the
 *    threshold is crossed. Anything in between is discarded entirely.
 *
 * A double blink requires two quick blinks with a clean debounced reopen between
 * them, at most [DOUBLE_BLINK_GAP_MS] apart, and the eyes must have been open for
 * [PRE_OPEN_MS] before the first — so bursts of rapid natural blinking (dry eyes,
 * concentration flutter) never chain into a trigger.
 */
class BlinkTracker {
    companion object {
        const val STATE_DEBOUNCE_MS = 70L
        const val MIN_STATE_SAMPLES = 2
        const val MIN_BLINK_MS = 80L
        const val MAX_QUICK_BLINK_MS = 400L
        const val DOUBLE_BLINK_GAP_MS = 450L
        const val PRE_OPEN_MS = 200L
        private const val NEVER = Long.MIN_VALUE / 2
    }

    private enum class State { UNKNOWN, OPEN, CLOSED }

    private var confirmed = State.UNKNOWN
    private var pending: State? = null
    private var pendingSince = 0L
    private var pendingSamples = 0

    private var openSince = NEVER          // start of the current confirmed OPEN period
    private var closedStart = NEVER        // start of the current confirmed CLOSED period
    private var openBeforeClosureMs = -1L  // how long the eyes were open before this closure
    private var longConsumed = false       // long blink already emitted for this closure
    private var firstQuickBlinkEnd = NEVER // end time of a valid first blink of a pair

    private var doubleBlinkReady = false
    private var longBlinkReady = false

    val isClosed: Boolean get() = confirmed == State.CLOSED

    fun reset() {
        confirmed = State.UNKNOWN
        pending = null
        pendingSamples = 0
        openSince = NEVER
        closedStart = NEVER
        openBeforeClosureMs = -1L
        longConsumed = false
        firstQuickBlinkEnd = NEVER
        doubleBlinkReady = false
        longBlinkReady = false
    }

    /** Returns pending events exactly once; the caller decides whether to act on them. */
    fun poll(): BlinkEvents {
        val events = BlinkEvents(doubleBlinkReady, longBlinkReady)
        doubleBlinkReady = false
        longBlinkReady = false
        return events
    }

    fun update(leftOpen: Float, rightOpen: Float, config: GestureConfig, now: Long) {
        if (leftOpen < 0f || rightOpen < 0f) {
            reset()
            return
        }
        val raw = when {
            leftOpen < config.eyeClosedThreshold && rightOpen < config.eyeClosedThreshold -> State.CLOSED
            leftOpen > config.eyeOpenThreshold || rightOpen > config.eyeOpenThreshold -> State.OPEN
            else -> null // inside the hysteresis band: keep the current state
        }
        if (raw != null) advance(raw, now)

        // Fire the long blink as soon as the closure crosses the configured duration,
        // while the eyes are still closed — responsive, and it can't re-fire because
        // the closure is marked consumed.
        if (confirmed == State.CLOSED && !longConsumed && now - closedStart >= config.longBlinkMs) {
            longBlinkReady = true
            longConsumed = true
            firstQuickBlinkEnd = NEVER
        }
    }

    private fun advance(raw: State, now: Long) {
        if (raw == confirmed) {
            pending = null
            pendingSamples = 0
            return
        }
        if (raw == pending) {
            pendingSamples++
        } else {
            // A different reading interrupts an in-flight transition. Under
            // sample-and-hold the pending state persisted until this frame, so commit
            // it retroactively if it already met the debounce bar; otherwise it was
            // chatter and is dropped.
            val p = pending
            if (p != null && pendingSamples >= MIN_STATE_SAMPLES && now - pendingSince >= STATE_DEBOUNCE_MS) {
                commit(p, pendingSince)
            }
            pending = raw
            pendingSince = now
            pendingSamples = 1
        }
        if (pendingSamples >= MIN_STATE_SAMPLES && now - pendingSince >= STATE_DEBOUNCE_MS) {
            commit(pending ?: return, pendingSince)
            pending = null
            pendingSamples = 0
        }
    }

    private fun commit(state: State, at: Long) {
        val previous = confirmed
        confirmed = state
        when (state) {
            State.CLOSED -> {
                closedStart = at
                longConsumed = false
                openBeforeClosureMs =
                    if (previous == State.OPEN && openSince != NEVER) at - openSince else -1L
            }
            State.OPEN -> {
                if (previous == State.CLOSED && closedStart != NEVER) classifyClosure(closedStart, at)
                openSince = at
                closedStart = NEVER
            }
            State.UNKNOWN -> Unit
        }
    }

    private fun classifyClosure(start: Long, end: Long) {
        if (longConsumed) {
            // This closure already fired a long blink; it cannot also count as a quick one.
            firstQuickBlinkEnd = NEVER
            return
        }
        val duration = end - start
        if (duration in MIN_BLINK_MS..MAX_QUICK_BLINK_MS) {
            if (firstQuickBlinkEnd != NEVER && start - firstQuickBlinkEnd <= DOUBLE_BLINK_GAP_MS) {
                doubleBlinkReady = true
                firstQuickBlinkEnd = NEVER
            } else {
                // Only a blink preceded by comfortably-open eyes may start a pair,
                // so flutter bursts can't chain into a double blink.
                firstQuickBlinkEnd = if (openBeforeClosureMs >= PRE_OPEN_MS) end else NEVER
            }
        } else {
            // Medium-length or sub-threshold closures are neither quick nor long:
            // discard them and break any pair in progress.
            firstQuickBlinkEnd = NEVER
        }
    }
}

/**
 * Turns a stream of [FaceSignals] into discrete [PageAction]s.
 *
 * Held poses (head turn, wink, smile) go through a dwell timer: the pose must be held
 * [GestureConfig.dwellMs] before firing, with a short grace window so a single dropped
 * frame doesn't reset the hold. Blink gestures are *events* from [BlinkTracker].
 *
 * Safeguards against accidental turns:
 *  - **Head-pose gate:** eye and smile signals are ignored whenever the head is turned
 *    or pitched away from the camera (beyond [YAW_GATE_DEG]/[PITCH_GATE_DEG]) — ML Kit's
 *    eye probabilities are unreliable off-frontal, e.g. when glancing down at the keys.
 *  - **Cooldown + re-arm:** after firing, new triggers are blocked for
 *    [GestureConfig.cooldownMs], and the face must return to neutral (no pose active,
 *    eyes open) before another gesture is accepted.
 *
 * Not thread-safe: it is only ever driven from the single camera-analysis callback.
 */
class GestureEngine {
    private companion object {
        // Sentinel far in the past so a freshly constructed engine is never "in cooldown"
        // regardless of the clock's starting value. Halved to avoid subtraction overflow.
        const val NEVER_FIRED = Long.MIN_VALUE / 2
        // A held pose survives signal dropouts shorter than this without resetting dwell.
        const val GRACE_MS = 150L
        // Eye/smile gestures only count while the face is roughly camera-frontal.
        const val YAW_GATE_DEG = 20f
        const val PITCH_GATE_DEG = 25f
    }

    private val tracker = BlinkTracker()
    private var candidate: PageAction? = null
    private var candidateSince = 0L
    private var lastPoseActiveAt = NEVER_FIRED
    private var lastFire = NEVER_FIRED
    private var armed = true

    fun reset() {
        candidate = null
        candidateSince = 0L
        lastPoseActiveAt = NEVER_FIRED
        lastFire = NEVER_FIRED
        armed = true
        tracker.reset()
    }

    fun onFrame(signals: FaceSignals, config: GestureConfig, nowMs: Long): GestureOutput {
        if (!signals.present) {
            candidate = null
            tracker.reset()
            return GestureOutput(EngineStatus.NO_FACE, null)
        }

        val frontal = abs(signals.yaw) <= YAW_GATE_DEG && abs(signals.pitch) <= PITCH_GATE_DEG
        if (frontal) {
            tracker.update(signals.leftEyeOpen, signals.rightEyeOpen, config, nowMs)
        } else {
            tracker.reset()
        }
        val events = tracker.poll()

        if (nowMs - lastFire < config.cooldownMs) {
            candidate = null
            return GestureOutput(EngineStatus.COOLDOWN, null) // events discarded during cooldown
        }

        if (armed) {
            val eventAction = resolveEvent(events, config)
            if (eventAction != null) return fire(eventAction, nowMs)
        }

        val action = resolveAction(activeGestures(signals, config, frontal), config)

        if (action == null) {
            if (armed && candidate != null && nowMs - lastPoseActiveAt <= GRACE_MS) {
                // Brief signal dropout mid-hold: keep the dwell alive.
                return GestureOutput(EngineStatus.ARMING, null)
            }
            candidate = null
            if (!tracker.isClosed) armed = true // neutral requires open eyes to re-arm
            val status = when {
                !armed -> EngineStatus.COOLDOWN
                tracker.isClosed && mapsGesture(config, GestureType.BLINK_BOTH) -> EngineStatus.ARMING
                else -> EngineStatus.READY
            }
            return GestureOutput(status, null)
        }

        lastPoseActiveAt = nowMs
        if (!armed) {
            // A pose is still being held from a previous fire; wait for neutral.
            candidate = action
            return GestureOutput(EngineStatus.COOLDOWN, null)
        }
        if (action != candidate) {
            candidate = action
            candidateSince = nowMs
        }
        return if (nowMs - candidateSince >= config.dwellMs) {
            fire(action, nowMs)
        } else {
            GestureOutput(EngineStatus.ARMING, null)
        }
    }

    private fun fire(action: PageAction, now: Long): GestureOutput {
        lastFire = now
        armed = false
        candidate = null
        return GestureOutput(EngineStatus.COOLDOWN, action)
    }

    private fun resolveEvent(events: BlinkEvents, config: GestureConfig): PageAction? {
        if (events.longBlink) mappingFor(config, GestureType.BLINK_BOTH)?.let { return it }
        if (events.doubleBlink) mappingFor(config, GestureType.DOUBLE_BLINK)?.let { return it }
        return null
    }

    private fun mappingFor(c: GestureConfig, gesture: GestureType): PageAction? = when {
        c.nextGesture == gesture && c.prevGesture != gesture -> PageAction.NEXT
        c.prevGesture == gesture && c.nextGesture != gesture -> PageAction.PREVIOUS
        else -> null // unmapped, or ambiguously mapped to both directions
    }

    private fun mapsGesture(c: GestureConfig, gesture: GestureType): Boolean =
        c.nextGesture == gesture || c.prevGesture == gesture

    private fun resolveAction(active: Set<GestureType>, config: GestureConfig): PageAction? {
        val wantsNext = config.nextGesture != GestureType.NONE && active.contains(config.nextGesture)
        val wantsPrev = config.prevGesture != GestureType.NONE && active.contains(config.prevGesture)
        return when {
            wantsNext && !wantsPrev -> PageAction.NEXT
            wantsPrev && !wantsNext -> PageAction.PREVIOUS
            else -> null // nothing, or ambiguous (both at once) -> ignore
        }
    }

    /** Held poses only. Blink gestures are events emitted by [BlinkTracker]. */
    private fun activeGestures(s: FaceSignals, c: GestureConfig, frontal: Boolean): Set<GestureType> {
        val set = mutableSetOf<GestureType>()

        if (s.yaw >= c.yawThresholdDeg) set.add(GestureType.HEAD_RIGHT)
        if (s.yaw <= -c.yawThresholdDeg) set.add(GestureType.HEAD_LEFT)

        if (frontal) {
            val l = s.leftEyeOpen
            val r = s.rightEyeOpen
            if (l >= 0f && r >= 0f) {
                if (l < c.eyeClosedThreshold && r > c.eyeOpenThreshold) set.add(GestureType.WINK_LEFT)
                if (r < c.eyeClosedThreshold && l > c.eyeOpenThreshold) set.add(GestureType.WINK_RIGHT)
            }
            if (s.smiling >= 0f && s.smiling > c.smileThreshold) set.add(GestureType.SMILE)
        }

        return set
    }
}
