package com.maestro.app.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the gesture state machine. This is the safety-critical core
 * (accidental page turns are the whole risk), so the dwell / cooldown / re-arm
 * behaviour and the debounced blink tracking are pinned down here.
 * No Android dependencies => runs on the JVM.
 */
class GestureEngineTest {

    private val cfg = GestureConfig() // next = HEAD_RIGHT, prev = HEAD_LEFT, dwell 350, cooldown 1200

    private fun neutral() = FaceSignals(present = true, yaw = 0f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
    private fun headRight() = FaceSignals(present = true, yaw = 30f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
    private fun headLeft() = FaceSignals(present = true, yaw = -30f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
    private fun eyes(l: Float, r: Float, yaw: Float = 0f, pitch: Float = 0f) =
        FaceSignals(present = true, yaw = yaw, pitch = pitch, leftEyeOpen = l, rightEyeOpen = r)

    /** Feeds [signals] every [step] ms over [from, until) and returns (time, action) per fire. */
    private fun drive(
        e: GestureEngine,
        c: GestureConfig,
        from: Long,
        until: Long,
        signals: FaceSignals,
        step: Long = 33
    ): List<Pair<Long, PageAction>> {
        val fired = mutableListOf<Pair<Long, PageAction>>()
        var t = from
        while (t < until) {
            e.onFrame(signals, c, t).fired?.let { fired.add(t to it) }
            t += step
        }
        return fired
    }

    // ------------------------------------------------------------------ poses

    @Test
    fun noFace_reportsNoFace_andNeverFires() {
        val e = GestureEngine()
        val out = e.onFrame(FaceSignals(present = false), cfg, 0L)
        assertEquals(EngineStatus.NO_FACE, out.status)
        assertNull(out.fired)
    }

    @Test
    fun neutralFace_isReady_andNeverFires() {
        val e = GestureEngine()
        val out = e.onFrame(neutral(), cfg, 0L)
        assertEquals(EngineStatus.READY, out.status)
        assertNull(out.fired)
    }

    @Test
    fun holdingHeadRightPastDwell_firesNextExactlyOnce() {
        val e = GestureEngine()
        assertEquals(EngineStatus.ARMING, e.onFrame(headRight(), cfg, 0L).status)
        assertNull(e.onFrame(headRight(), cfg, 200L).fired)
        assertNull(e.onFrame(headRight(), cfg, 349L).fired)         // just under dwell
        assertEquals(PageAction.NEXT, e.onFrame(headRight(), cfg, 360L).fired) // crosses dwell
        // Still held during cooldown: must NOT fire again.
        assertNull(e.onFrame(headRight(), cfg, 500L).fired)
        assertNull(e.onFrame(headRight(), cfg, 1000L).fired)
    }

    @Test
    fun briefHeadTurn_shorterThanDwell_doesNotFire() {
        val e = GestureEngine()
        assertNull(e.onFrame(headRight(), cfg, 0L).fired)
        assertNull(e.onFrame(headRight(), cfg, 100L).fired)
        // Released before dwell elapsed: within the grace window the hold survives…
        assertEquals(EngineStatus.ARMING, e.onFrame(neutral(), cfg, 150L).status)
        // …but once grace expires the candidate clears, and nothing ever fires.
        assertEquals(EngineStatus.READY, e.onFrame(neutral(), cfg, 320L).status)
        assertNull(e.onFrame(neutral(), cfg, 1000L).fired)
    }

    @Test
    fun graceWindow_keepsDwellAliveThroughBriefDropout() {
        val e = GestureEngine()
        var fired: PageAction? = null
        // Hold head right 0..198ms.
        var t = 0L
        while (t < 200L) { e.onFrame(headRight(), cfg, t); t += 33 }
        // Two dropped frames (signal reads neutral) within grace.
        e.onFrame(neutral(), cfg, 231L)
        e.onFrame(neutral(), cfg, 264L)
        // Resume the hold: dwell measured from the original start -> fires ~363ms.
        t = 297L
        while (t < 500L && fired == null) { fired = e.onFrame(headRight(), cfg, t).fired; t += 33 }
        assertEquals(PageAction.NEXT, fired)
        assertTrue("fired too late: $t", t <= 400L)
    }

    @Test
    fun heldGestureDoesNotRepeat_untilNeutralAndCooldownPass() {
        val e = GestureEngine()
        e.onFrame(headRight(), cfg, 0L)
        assertEquals(PageAction.NEXT, e.onFrame(headRight(), cfg, 360L).fired)

        // Keep holding well past the cooldown window: still no second fire (needs neutral).
        assertNull(e.onFrame(headRight(), cfg, 1700L).fired)
        assertNull(e.onFrame(headRight(), cfg, 3000L).fired)

        // Return to neutral to re-arm (past the grace window of the held pose).
        assertEquals(EngineStatus.READY, e.onFrame(neutral(), cfg, 3200L).status)

        // Turn again and hold past dwell -> fires again.
        e.onFrame(headRight(), cfg, 3300L)
        assertEquals(PageAction.NEXT, e.onFrame(headRight(), cfg, 3700L).fired)
    }

    @Test
    fun gestureDuringCooldown_isBlocked_evenIfReleasedAndRepeated() {
        val e = GestureEngine()
        e.onFrame(headRight(), cfg, 0L)
        assertEquals(PageAction.NEXT, e.onFrame(headRight(), cfg, 360L).fired)

        // Release, then try a brand-new full gesture entirely inside the cooldown window.
        e.onFrame(neutral(), cfg, 400L)
        e.onFrame(headRight(), cfg, 450L)
        val out = e.onFrame(headRight(), cfg, 900L) // 900 - 360 = 540 < 1200 cooldown
        assertEquals(EngineStatus.COOLDOWN, out.status)
        assertNull(out.fired)
    }

    @Test
    fun headLeft_firesPrevious() {
        val e = GestureEngine()
        e.onFrame(headLeft(), cfg, 0L)
        assertEquals(PageAction.PREVIOUS, e.onFrame(headLeft(), cfg, 400L).fired)
    }

    @Test
    fun yawThreshold_isRespected() {
        val e = GestureEngine()
        val justUnder = FaceSignals(present = true, yaw = 21f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
        assertEquals(EngineStatus.READY, e.onFrame(justUnder, cfg, 0L).status)
        assertNull(e.onFrame(justUnder, cfg, 1000L).fired)
    }

    @Test
    fun winkMapping_firesConfiguredAction() {
        val e = GestureEngine()
        val winkCfg = GestureConfig(
            nextGesture = GestureType.WINK_LEFT,
            prevGesture = GestureType.WINK_RIGHT
        )
        val winkLeft = eyes(l = 0.1f, r = 0.9f)
        e.onFrame(winkLeft, winkCfg, 0L)
        assertEquals(PageAction.NEXT, e.onFrame(winkLeft, winkCfg, 400L).fired)
    }

    @Test
    fun bothEyesClosed_isNotMistakenForWink() {
        val e = GestureEngine()
        val winkCfg = GestureConfig(
            nextGesture = GestureType.WINK_LEFT,
            prevGesture = GestureType.WINK_RIGHT
        )
        val blink = eyes(l = 0.1f, r = 0.1f)
        e.onFrame(blink, winkCfg, 0L)
        assertNull(e.onFrame(blink, winkCfg, 400L).fired)
    }

    @Test
    fun unknownEyeProbabilities_doNotTriggerEyeGestures() {
        val e = GestureEngine()
        val winkCfg = GestureConfig(nextGesture = GestureType.WINK_LEFT)
        val unknownEyes = eyes(l = -1f, r = -1f)
        e.onFrame(unknownEyes, winkCfg, 0L)
        assertNull(e.onFrame(unknownEyes, winkCfg, 500L).fired)
    }

    @Test
    fun ambiguousMapping_sameGestureBothDirections_firesNothing() {
        val e = GestureEngine()
        val ambiguous = GestureConfig(
            nextGesture = GestureType.HEAD_RIGHT,
            prevGesture = GestureType.HEAD_RIGHT
        )
        e.onFrame(headRight(), ambiguous, 0L)
        assertNull(e.onFrame(headRight(), ambiguous, 500L).fired)
    }

    @Test
    fun noneGesture_isNeverActive() {
        val e = GestureEngine()
        val offCfg = GestureConfig(nextGesture = GestureType.NONE, prevGesture = GestureType.NONE)
        e.onFrame(headRight(), offCfg, 0L)
        assertNull(e.onFrame(headRight(), offCfg, 500L).fired)
    }

    @Test
    fun swappedMapping_reversesDirection() {
        val e = GestureEngine()
        val swapped = GestureConfig(
            nextGesture = GestureType.HEAD_LEFT,
            prevGesture = GestureType.HEAD_RIGHT
        )
        e.onFrame(headRight(), swapped, 0L)
        assertEquals(PageAction.PREVIOUS, e.onFrame(headRight(), swapped, 400L).fired)
    }

    @Test
    fun changingDirectionMidHold_restartsDwellTimer() {
        val e = GestureEngine()
        e.onFrame(headRight(), cfg, 0L)
        e.onFrame(headRight(), cfg, 300L)
        assertNull(e.onFrame(headLeft(), cfg, 400L).fired)
        assertEquals(PageAction.PREVIOUS, e.onFrame(headLeft(), cfg, 760L).fired)
    }

    @Test
    fun smile_firesAfterDwell() {
        val e = GestureEngine()
        val c = GestureConfig(nextGesture = GestureType.SMILE, prevGesture = GestureType.NONE)
        val smiling = FaceSignals(present = true, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f, smiling = 0.9f)
        e.onFrame(smiling, c, 0L)
        assertEquals(PageAction.NEXT, e.onFrame(smiling, c, 400L).fired)
    }

    @Test
    fun smile_belowThreshold_doesNotFire() {
        val e = GestureEngine()
        val c = GestureConfig(nextGesture = GestureType.SMILE, prevGesture = GestureType.NONE)
        val mild = FaceSignals(present = true, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f, smiling = 0.5f)
        e.onFrame(mild, c, 0L)
        assertNull(e.onFrame(mild, c, 600L).fired)
    }

    // ------------------------------------------------------- long blink events

    private val longCfg = GestureConfig(nextGesture = GestureType.BLINK_BOTH, prevGesture = GestureType.NONE)
    private fun open() = eyes(l = 0.95f, r = 0.95f)
    private fun closed() = eyes(l = 0.05f, r = 0.05f)

    @Test
    fun naturalQuickBlink_neverFiresLongBlink() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        fired += drive(e, longCfg, 0L, 500L, open())
        fired += drive(e, longCfg, 500L, 680L, closed())   // 180ms: a normal blink
        fired += drive(e, longCfg, 680L, 2000L, open())
        assertTrue("unexpected fires: $fired", fired.isEmpty())
    }

    @Test
    fun longBlink_firesAtConfiguredDuration_onceOnly_thenAgainAfterReopen() {
        val e = GestureEngine()
        drive(e, longCfg, 0L, 500L, open())
        // Mid-closure the status should read ARMING (long blink mapped, eyes closed).
        var fired = drive(e, longCfg, 500L, 1000L, closed())
        assertTrue(fired.isEmpty())
        assertEquals(EngineStatus.ARMING, e.onFrame(closed(), longCfg, 1000L).status)
        // Crosses the 700ms threshold at ~1200 -> exactly one fire, while still closed.
        fired = drive(e, longCfg, 1033L, 1400L, closed())
        assertEquals(1, fired.size)
        assertEquals(PageAction.NEXT, fired[0].second)
        assertTrue("fired at ${fired[0].first}", fired[0].first in 1200L..1300L)
        // Keep holding closed long past cooldown: no repeat.
        assertTrue(drive(e, longCfg, 1400L, 3000L, closed()).isEmpty())
        // Reopen (re-arms), close again -> second fire ~700ms into the new closure.
        assertTrue(drive(e, longCfg, 3000L, 3400L, open()).isEmpty())
        fired = drive(e, longCfg, 3400L, 4400L, closed())
        assertEquals(1, fired.size)
        assertTrue("2nd fire at ${fired[0].first}", fired[0].first in 4100L..4200L)
    }

    @Test
    fun longBlinkSlider_isRespected() {
        val slow = longCfg.copy(longBlinkMs = 1000L)
        val e = GestureEngine()
        drive(e, slow, 0L, 700L, open())
        // 800ms closure: longer than a blink, shorter than the configured 1000ms -> nothing.
        assertTrue(drive(e, slow, 700L, 1500L, closed()).isEmpty())
        drive(e, slow, 1500L, 2000L, open())
        // 1200ms closure: fires at ~closureStart + 1000.
        val fired = drive(e, slow, 2000L, 3200L, closed())
        assertEquals(1, fired.size)
        assertTrue("fired at ${fired[0].first}", fired[0].first in 3000L..3100L)
    }

    @Test
    fun mediumClosure_betweenQuickAndLong_firesNothing() {
        val e = GestureEngine()
        drive(e, longCfg, 0L, 500L, open())
        drive(e, longCfg, 500L, 1050L, closed())   // 550ms: too long for quick, short of 700
        val fired = drive(e, longCfg, 1050L, 2500L, open())
        assertTrue(fired.isEmpty())
    }

    // ----------------------------------------------------- double blink events

    private val dblCfg = GestureConfig(nextGesture = GestureType.DOUBLE_BLINK, prevGesture = GestureType.NONE)

    @Test
    fun deliberateDoubleBlink_fires() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        fired += drive(e, dblCfg, 0L, 400L, open())      // pre-open
        fired += drive(e, dblCfg, 400L, 600L, closed())  // blink 1 (200ms)
        fired += drive(e, dblCfg, 600L, 800L, open())    // clean reopen
        fired += drive(e, dblCfg, 800L, 1000L, closed()) // blink 2 (200ms)
        fired += drive(e, dblCfg, 1000L, 1500L, open())
        assertEquals(1, fired.size)
        assertEquals(PageAction.NEXT, fired[0].second)
    }

    @Test
    fun blinksTooFarApart_doNotPair() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        fired += drive(e, dblCfg, 0L, 400L, open())
        fired += drive(e, dblCfg, 400L, 600L, closed())   // blink 1 ends at 600
        fired += drive(e, dblCfg, 600L, 1200L, open())    // 600ms gap > 450ms limit
        fired += drive(e, dblCfg, 1200L, 1400L, closed()) // blink 2
        fired += drive(e, dblCfg, 1400L, 2000L, open())
        assertTrue("unexpected fires: $fired", fired.isEmpty())
    }

    @Test
    fun rapidBlinkFlutter_isRejectedByPreOpenRequirement() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        // Continuous flutter: 120ms closures with only ~120ms open between them.
        fired += drive(e, dblCfg, 0L, 120L, open())
        var t = 120L
        repeat(4) {
            fired += drive(e, dblCfg, t, t + 120L, closed()); t += 120L
            fired += drive(e, dblCfg, t, t + 120L, open()); t += 120L
        }
        fired += drive(e, dblCfg, t, t + 800L, open())
        assertTrue("flutter fired: $fired", fired.isEmpty())
    }

    @Test
    fun doubleBlinkCompletedDuringCooldown_isDiscarded() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        // Pair 1 fires (~1070).
        fired += drive(e, dblCfg, 0L, 400L, open())
        fired += drive(e, dblCfg, 400L, 600L, closed())
        fired += drive(e, dblCfg, 600L, 800L, open())
        fired += drive(e, dblCfg, 800L, 1000L, closed())
        fired += drive(e, dblCfg, 1000L, 1200L, open())
        assertEquals(1, fired.size)
        val firstFireAt = fired[0].first
        // Pair 2 entirely inside the cooldown window -> discarded.
        fired += drive(e, dblCfg, 1200L, 1400L, closed())
        fired += drive(e, dblCfg, 1400L, 1600L, open())
        fired += drive(e, dblCfg, 1600L, 1800L, closed())
        fired += drive(e, dblCfg, 1800L, firstFireAt + 1200L, open())
        assertEquals("pair inside cooldown must not fire", 1, fired.size)
        // Pair 3 after cooldown -> fires.
        fired += drive(e, dblCfg, firstFireAt + 1200L, firstFireAt + 1600L, open())
        fired += drive(e, dblCfg, firstFireAt + 1600L, firstFireAt + 1800L, closed())
        fired += drive(e, dblCfg, firstFireAt + 1800L, firstFireAt + 2000L, open())
        fired += drive(e, dblCfg, firstFireAt + 2000L, firstFireAt + 2200L, closed())
        fired += drive(e, dblCfg, firstFireAt + 2200L, firstFireAt + 2700L, open())
        assertEquals(2, fired.size)
    }

    // ------------------------------------------------- noise-robustness cases

    @Test
    fun singleNoisyClosedFrame_doesNotCountAsBlink() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        var t = 0L
        while (t < 1500L) {
            val s = if (t == 495L) closed() else open() // one bogus frame
            e.onFrame(s, dblCfg, t).fired?.let { fired.add(t to it) }
            t += 33
        }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun singleNoisyOpenFrame_doesNotSplitALongBlink() {
        val e = GestureEngine()
        drive(e, longCfg, 0L, 500L, open())
        val fired = mutableListOf<Pair<Long, PageAction>>()
        var t = 500L
        while (t < 1500L) {
            val s = if (t == 896L) open() else closed() // one bogus frame mid-closure
            e.onFrame(s, longCfg, t).fired?.let { fired.add(t to it) }
            t += 33
        }
        // The closure survives the dropout and still fires at ~1200.
        assertEquals(1, fired.size)
        assertTrue("fired at ${fired[0].first}", fired[0].first in 1200L..1300L)
    }

    @Test
    fun wobbleInsideHysteresisBand_staysOneBlink_noPhantomDouble() {
        val e = GestureEngine()
        val fired = mutableListOf<Pair<Long, PageAction>>()
        val band = eyes(l = 0.35f, r = 0.35f) // between closed(0.25) and open(0.55) thresholds
        fired += drive(e, dblCfg, 0L, 500L, open())
        fired += drive(e, dblCfg, 500L, 680L, closed())
        fired += drive(e, dblCfg, 680L, 740L, band)      // wobble mid-blink
        fired += drive(e, dblCfg, 740L, 860L, closed())
        fired += drive(e, dblCfg, 860L, 1600L, open())
        // With naive single-threshold logic this reads as two blinks -> double-blink.
        assertTrue("wobble produced a phantom double blink: $fired", fired.isEmpty())
    }

    @Test
    fun headTurnedAway_suppressesEyeGestures() {
        val e = GestureEngine()
        // Eyes read closed while the head is turned 30° (profile view = junk eye data).
        val turnedClosed = eyes(l = 0.05f, r = 0.05f, yaw = 30f)
        val fired = drive(e, longCfg, 0L, 1500L, turnedClosed)
        assertTrue("fired despite head turn: $fired", fired.isEmpty())
    }

    @Test
    fun pitchedDown_suppressesEyeGestures() {
        val e = GestureEngine()
        // Looking down (e.g. at the keys): lids droop, eye probabilities crater.
        val downClosed = eyes(l = 0.05f, r = 0.05f, pitch = -30f)
        val fired = drive(e, longCfg, 0L, 1500L, downClosed)
        assertTrue("fired despite downward pitch: $fired", fired.isEmpty())
    }

    @Test
    fun pitchedDown_suppressesWinkAndSmilePoses() {
        val e = GestureEngine()
        val c = GestureConfig(nextGesture = GestureType.SMILE, prevGesture = GestureType.WINK_LEFT)
        val downSmiling = FaceSignals(
            present = true, pitch = -30f,
            leftEyeOpen = 0.9f, rightEyeOpen = 0.9f, smiling = 0.95f
        )
        val fired = drive(e, c, 0L, 1000L, downSmiling)
        assertTrue(fired.isEmpty())
    }
}
