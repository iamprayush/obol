package dev.prayush.obol.vision

import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.time.TimePoint
import dev.prayush.obol.domain.time.deltaMs
import dev.prayush.obol.ui.VisionFailureReason
import kotlin.math.hypot
import kotlin.math.max

data class EmittedDomainEvent(
    val event: MorningEvent,
    val atPoint: TimePoint,
)

data class BrushingStepOutcome(
    val accumulatedBrushMs: Long,
    val isWindowPassing: Boolean,
    val isMeterPaused: Boolean,
    val failureReason: VisionFailureReason,
    val isOverrideUnlocked: Boolean,
    val isCompleted: Boolean,
    val domainEvents: List<EmittedDomainEvent>,
)

class CrossingCoordinator(
    initialAccumulatedBrushMs: Long = 0L,
    private var startedDuringFreeWindow: Boolean = false,
) {
    var accumulatedBrushMs: Long = initialAccumulatedBrushMs.coerceIn(0L, ObolConstants.BRUSH_TARGET_MS)
        private set

    val isSessionOpen: Boolean
        get() = sessionOpenedPoint != null && !brushCompletedEmitted && !inOverride

    val isInOverride: Boolean
        get() = inOverride

    val isInFreeWindow: Boolean
        get() = startedDuringFreeWindow

    val isAcquiring: Boolean
        get() = currentlyAcquiring

    private var sessionOpenedPoint: TimePoint? = null
    private var prevPassingFramePoint: TimePoint? = null
    private var lastPassingPoint: TimePoint? = null
    private var nonBrushingContinuousStartPoint: TimePoint? = null
    private var overrideEverUnlocked: Boolean = false
    private var cameraErrorOccurred: Boolean = false
    private var meterPausedInDomain: Boolean = false
    private var brushCompletedEmitted: Boolean = false
    private var inOverride: Boolean = false
    private var currentlyAcquiring: Boolean = false
    private var lastNonPassingReason: VisionFailureReason = VisionFailureReason.NO_TOOTHBRUSH

    private data class BrushSample(
        val point: TimePoint,
        val spatiallyValid: Boolean,
        val anchorXNorm: Float,
        val anchorYNorm: Float,
        val brushXNorm: Float?,
        val brushYNorm: Float?,
    )

    private val brushSamples = ArrayDeque<BrushSample>()

    fun onBrushingSessionOpened(
        point: TimePoint,
        inFreeWindow: Boolean = startedDuringFreeWindow,
        alreadyPausedInDomain: Boolean = false,
    ) {
        startedDuringFreeWindow = inFreeWindow
        inOverride = false
        sessionOpenedPoint = point
        if (nonBrushingContinuousStartPoint == null && !alreadyPausedInDomain) {
            nonBrushingContinuousStartPoint = point
        }
        meterPausedInDomain = alreadyPausedInDomain
        currentlyAcquiring = inFreeWindow && !alreadyPausedInDomain && !cameraErrorOccurred
        lastNonPassingReason = if (cameraErrorOccurred) {
            VisionFailureReason.CAMERA_ERROR
        } else {
            VisionFailureReason.NO_TOOTHBRUSH
        }
    }

    fun updateInFreeWindow(
        inFreeWindow: Boolean,
        point: TimePoint? = null,
    ): VisionFailureReason? {
        startedDuringFreeWindow = inFreeWindow
        if (currentlyAcquiring) {
            val graceExpired = !inFreeWindow || (
                point != null &&
                    sessionOpenedPoint != null &&
                    deltaMs(sessionOpenedPoint!!, point) >= INITIAL_ACQUIRE_GRACE_MS
                )
            if (graceExpired) {
                currentlyAcquiring = false
                return if (cameraErrorOccurred) {
                    VisionFailureReason.CAMERA_ERROR
                } else {
                    lastNonPassingReason
                }
            }
        }
        return null
    }

    fun onOverrideOpened() {
        inOverride = true
        currentlyAcquiring = false
        prevPassingFramePoint = null
        meterPausedInDomain = false
    }

    fun onOverrideClosed(point: TimePoint? = null) {
        inOverride = false
        if (sessionOpenedPoint == null && point != null) {
            sessionOpenedPoint = point
            if (nonBrushingContinuousStartPoint == null) {
                nonBrushingContinuousStartPoint = point
            }
        }
    }

    fun clearOpenSessionState() {
        prevPassingFramePoint = null
        lastPassingPoint = null
        nonBrushingContinuousStartPoint = null
        sessionOpenedPoint = null
        meterPausedInDomain = false
        inOverride = false
        currentlyAcquiring = false
        lastNonPassingReason = if (cameraErrorOccurred) {
            VisionFailureReason.CAMERA_ERROR
        } else {
            VisionFailureReason.NO_TOOTHBRUSH
        }
        brushSamples.clear()
    }

    fun syncPersistedAccumulatedBrushMs(persistedMs: Long) {
        val clamped = persistedMs.coerceIn(0L, ObolConstants.BRUSH_TARGET_MS)
        if (clamped > accumulatedBrushMs) {
            accumulatedBrushMs = clamped
        }
    }

    fun onCameraError(point: TimePoint? = null): BrushingStepOutcome {
        cameraErrorOccurred = true
        overrideEverUnlocked = true
        currentlyAcquiring = false
        lastNonPassingReason = VisionFailureReason.CAMERA_ERROR
        prevPassingFramePoint = null
        val emitted = mutableListOf<EmittedDomainEvent>()
        if (meterPausedInDomain && point != null) {
            val sightLostPoint = lastPassingPoint ?: point
            emitted.add(EmittedDomainEvent(MorningEvent.BrushingSightLost, sightLostPoint))
            meterPausedInDomain = false
        }
        return BrushingStepOutcome(
            accumulatedBrushMs = accumulatedBrushMs,
            isWindowPassing = false,
            isMeterPaused = false,
            failureReason = VisionFailureReason.CAMERA_ERROR,
            isOverrideUnlocked = true,
            isCompleted = brushCompletedEmitted,
            domainEvents = emitted,
        )
    }

    fun onTimeTick(
        point: TimePoint,
        inFreeWindow: Boolean = startedDuringFreeWindow,
    ): BrushingStepOutcome? {
        if (!isSessionOpen || brushCompletedEmitted) return null

        startedDuringFreeWindow = inFreeWindow
        var changed = false
        val emitted = mutableListOf<EmittedDomainEvent>()

        if (currentlyAcquiring) {
            val acquireExpired = !startedDuringFreeWindow || (
                sessionOpenedPoint != null &&
                    deltaMs(sessionOpenedPoint!!, point) >= INITIAL_ACQUIRE_GRACE_MS
                )
            if (acquireExpired) {
                currentlyAcquiring = false
                changed = true
            }
        }

        val lastPass = lastPassingPoint
        if (lastPass != null && deltaMs(lastPass, point) > BRUSH_WINDOW_MS) {
            prevPassingFramePoint = null
            if (nonBrushingContinuousStartPoint == null) {
                nonBrushingContinuousStartPoint = lastPass
            }
        }

        val nonBrushStart = nonBrushingContinuousStartPoint
        if (!overrideEverUnlocked &&
            nonBrushStart != null &&
            deltaMs(nonBrushStart, point) >= ObolConstants.OVERRIDE_UNLOCK_MS
        ) {
            overrideEverUnlocked = true
            changed = true
        }

        if (meterPausedInDomain && lastPass != null) {
            if (deltaMs(lastPass, point) >= ObolConstants.BRUSH_DROPOUT_GRACE_MS) {
                meterPausedInDomain = false
                emitted.add(EmittedDomainEvent(MorningEvent.BrushingSightLost, lastPass))
                changed = true
            }
        }

        if (!changed && emitted.isEmpty()) return null

        val isMeterStillPaused = meterPausedInDomain &&
            lastPass != null &&
            deltaMs(lastPass, point) < ObolConstants.BRUSH_DROPOUT_GRACE_MS

        return BrushingStepOutcome(
            accumulatedBrushMs = accumulatedBrushMs,
            isWindowPassing = false,
            isMeterPaused = isMeterStillPaused,
            failureReason = when {
                cameraErrorOccurred -> VisionFailureReason.CAMERA_ERROR
                currentlyAcquiring -> VisionFailureReason.ACQUIRING
                else -> lastNonPassingReason
            },
            isOverrideUnlocked = overrideEverUnlocked || cameraErrorOccurred,
            isCompleted = false,
            domainEvents = emitted,
        )
    }

    fun onBrushingFrame(
        analysis: VisionFrameAnalysis,
        point: TimePoint,
        inFreeWindow: Boolean = startedDuringFreeWindow,
    ): BrushingStepOutcome {
        startedDuringFreeWindow = inFreeWindow
        inOverride = false
        if (sessionOpenedPoint == null) {
            sessionOpenedPoint = point
            nonBrushingContinuousStartPoint = point
        }
        if (brushCompletedEmitted) {
            return BrushingStepOutcome(
                accumulatedBrushMs = ObolConstants.BRUSH_TARGET_MS,
                isWindowPassing = true,
                isMeterPaused = true,
                failureReason = VisionFailureReason.NONE,
                isOverrideUnlocked = overrideEverUnlocked,
                isCompleted = true,
                domainEvents = emptyList(),
            )
        }

        val frameW = analysis.frameWidth.coerceAtLeast(1).toFloat()
        val frameH = analysis.frameHeight.coerceAtLeast(1).toFloat()

        // Priority order: TOO_DARK -> NO_FACE -> NO_TOOTHBRUSH -> BRUSH_AWAY_FROM_MOUTH
        val validFace = analysis.faces.firstOrNull { face ->
            face.bounds.width >= MIN_FACE_WIDTH_FRAC * frameW &&
                face.mouthMidX != null &&
                face.mouthMidY != null
        }

        val nearBrush = if (validFace != null && analysis.toothbrushBoxes.isNotEmpty()) {
            val mx = validFace.mouthMidX!!
            val my = validFace.mouthMidY!!
            val maxDist = MAX_BRUSH_MOUTH_DIST_FACE_WIDTHS * validFace.bounds.width
            analysis.toothbrushBoxes
                .minByOrNull { it.distanceToPoint(mx, my) }
                ?.takeIf { it.distanceToPoint(mx, my) <= maxDist }
        } else {
            null
        }

        val singleFrameReason: VisionFailureReason = when {
            analysis.meanLuma < MIN_LUMA -> VisionFailureReason.TOO_DARK
            validFace == null -> VisionFailureReason.NO_FACE
            analysis.toothbrushBoxes.isEmpty() -> VisionFailureReason.NO_TOOTHBRUSH
            nearBrush == null -> VisionFailureReason.BRUSH_AWAY_FROM_MOUTH
            else -> VisionFailureReason.NONE
        }

        val spatiallyValid = singleFrameReason == VisionFailureReason.NONE
        val anchorX = ((validFace?.noseX ?: validFace?.mouthMidX ?: 0f) / frameW)
        val anchorY = ((validFace?.noseY ?: validFace?.mouthMidY ?: 0f) / frameW)
        val brushX = nearBrush?.let { it.centerX / frameW }
        val brushY = nearBrush?.let { it.centerY / frameW }

        brushSamples.addLast(
            BrushSample(
                point = point,
                spatiallyValid = spatiallyValid,
                anchorXNorm = anchorX,
                anchorYNorm = anchorY,
                brushXNorm = brushX,
                brushYNorm = brushY,
            ),
        )
        while (brushSamples.isNotEmpty() && deltaMs(brushSamples.first().point, point) > MOTION_WINDOW_MS) {
            brushSamples.removeFirst()
        }

        val passingIn1s = brushSamples.filter {
            it.spatiallyValid && deltaMs(it.point, point) <= BRUSH_WINDOW_MS
        }
        val maxMotionNorm = computeMaxBrushMotion(brushSamples.filter { it.spatiallyValid })

        val windowPassing = passingIn1s.size >= MIN_PASSING_FRAMES_1S &&
            maxMotionNorm >= MIN_BRUSH_MICRO_MOTION_FRAC

        val emitted = mutableListOf<EmittedDomainEvent>()

        if (windowPassing) {
            val prevPass = prevPassingFramePoint
            if (prevPass != null) {
                val addMs = deltaMs(prevPass, point)
                accumulatedBrushMs = (accumulatedBrushMs + addMs).coerceAtMost(ObolConstants.BRUSH_TARGET_MS)
            }
            prevPassingFramePoint = point
            lastPassingPoint = point

            val nonBrushStart = nonBrushingContinuousStartPoint
            if (nonBrushStart != null && deltaMs(nonBrushStart, point) >= ObolConstants.OVERRIDE_UNLOCK_MS) {
                overrideEverUnlocked = true
            }
            nonBrushingContinuousStartPoint = null

            if (!meterPausedInDomain) {
                meterPausedInDomain = true
                emitted.add(EmittedDomainEvent(MorningEvent.BrushingPausedMeter, point))
            }

            if (accumulatedBrushMs >= ObolConstants.BRUSH_TARGET_MS && !brushCompletedEmitted) {
                brushCompletedEmitted = true
                emitted.add(EmittedDomainEvent(MorningEvent.BrushCompleted, point))
            }
        } else {
            // Freeze accumulator immediately on first non-passing check (zero free brushing credit)
            prevPassingFramePoint = null

            if (nonBrushingContinuousStartPoint == null) {
                nonBrushingContinuousStartPoint = lastPassingPoint ?: sessionOpenedPoint ?: point
            }
            if (deltaMs(nonBrushingContinuousStartPoint!!, point) >= ObolConstants.OVERRIDE_UNLOCK_MS) {
                overrideEverUnlocked = true
            }

            val lastPass = lastPassingPoint
            if (meterPausedInDomain && lastPass != null) {
                val lostElapsed = deltaMs(lastPass, point)
                if (lostElapsed >= ObolConstants.BRUSH_DROPOUT_GRACE_MS) {
                    meterPausedInDomain = false
                    // Stamp BrushingSightLost at lastPass so MorningDerivation's 10s grace ends at exact `point`
                    emitted.add(EmittedDomainEvent(MorningEvent.BrushingSightLost, lastPass))
                }
            }
        }

        val isMeterStillPaused = windowPassing || (
            meterPausedInDomain &&
                lastPassingPoint != null &&
                deltaMs(lastPassingPoint!!, point) < ObolConstants.BRUSH_DROPOUT_GRACE_MS
            )

        val rawNonPassingReason = when {
            windowPassing -> VisionFailureReason.NONE
            cameraErrorOccurred -> VisionFailureReason.CAMERA_ERROR
            singleFrameReason != VisionFailureReason.NONE -> singleFrameReason
            passingIn1s.size >= MIN_PASSING_FRAMES_1S && maxMotionNorm < MIN_BRUSH_MICRO_MOTION_FRAC ->
                VisionFailureReason.BRUSH_AWAY_FROM_MOUTH
            else -> VisionFailureReason.NO_TOOTHBRUSH
        }
        if (!windowPassing) {
            lastNonPassingReason = rawNonPassingReason
        }

        val isAcquiringNow = !windowPassing &&
            !cameraErrorOccurred &&
            startedDuringFreeWindow &&
            lastPassingPoint == null &&
            sessionOpenedPoint != null &&
            deltaMs(sessionOpenedPoint!!, point) < INITIAL_ACQUIRE_GRACE_MS
        currentlyAcquiring = isAcquiringNow

        val effectiveFailureReason = if (isAcquiringNow) {
            VisionFailureReason.ACQUIRING
        } else {
            rawNonPassingReason
        }

        return BrushingStepOutcome(
            accumulatedBrushMs = accumulatedBrushMs,
            isWindowPassing = windowPassing,
            isMeterPaused = isMeterStillPaused,
            failureReason = effectiveFailureReason,
            isOverrideUnlocked = overrideEverUnlocked || cameraErrorOccurred,
            isCompleted = brushCompletedEmitted,
            domainEvents = emitted,
        )
    }

    fun onBrushingSessionClosed(closePoint: TimePoint): List<EmittedDomainEvent> {
        val out = mutableListOf<EmittedDomainEvent>()
        val nonBrushStart = nonBrushingContinuousStartPoint
        if (nonBrushStart != null && deltaMs(nonBrushStart, closePoint) >= ObolConstants.OVERRIDE_UNLOCK_MS) {
            overrideEverUnlocked = true
        }
        if (!brushCompletedEmitted) {
            if (meterPausedInDomain) {
                val sightLostPoint = lastPassingPoint ?: closePoint
                out.add(EmittedDomainEvent(MorningEvent.BrushingSightLost, sightLostPoint))
                meterPausedInDomain = false
            }
            out.add(EmittedDomainEvent(MorningEvent.CameraClosed, closePoint))
        }
        prevPassingFramePoint = null
        lastPassingPoint = null
        nonBrushingContinuousStartPoint = null
        sessionOpenedPoint = null
        inOverride = false
        currentlyAcquiring = false
        lastNonPassingReason = if (cameraErrorOccurred) {
            VisionFailureReason.CAMERA_ERROR
        } else {
            VisionFailureReason.NO_TOOTHBRUSH
        }
        brushSamples.clear()
        return out
    }

    private fun computeMaxBrushMotion(samples: List<BrushSample>): Float {
        if (samples.size < 2) return 0f
        var maxDisp = 0f
        for (i in 0 until samples.size) {
            for (j in i + 1 until samples.size) {
                val a = samples[i]
                val b = samples[j]
                val faceDisp = hypot(b.anchorXNorm - a.anchorXNorm, b.anchorYNorm - a.anchorYNorm)
                maxDisp = max(maxDisp, faceDisp)
                if (a.brushXNorm != null && a.brushYNorm != null && b.brushXNorm != null && b.brushYNorm != null) {
                    val brushDisp = hypot(b.brushXNorm - a.brushXNorm, b.brushYNorm - a.brushYNorm)
                    maxDisp = max(maxDisp, brushDisp)
                }
            }
        }
        return maxDisp
    }

    companion object {
        const val MIN_LUMA = 40f
        const val MIN_FACE_WIDTH_FRAC = 0.22f
        const val MAX_BRUSH_MOUTH_DIST_FACE_WIDTHS = 0.85f

        const val BRUSH_WINDOW_MS = 1_000L
        const val MOTION_WINDOW_MS = 1_200L
        const val INITIAL_ACQUIRE_GRACE_MS = 3_000L
        const val MIN_PASSING_FRAMES_1S = 3
        const val MIN_BRUSH_MICRO_MOTION_FRAC = 0.006f
    }
}
