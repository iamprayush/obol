package dev.prayush.obol.domain.engine

import dev.prayush.obol.domain.model.BillRow
import dev.prayush.obol.domain.model.BillRowKind
import dev.prayush.obol.domain.model.DerivedMorning
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningEventRecord
import dev.prayush.obol.domain.model.MorningHeader
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.model.WakeDeadline
import dev.prayush.obol.domain.model.WakeDeadlineKind
import dev.prayush.obol.domain.time.TimePoint
import dev.prayush.obol.domain.time.deltaMs
import java.time.Instant
import kotlin.math.max

object MorningDerivation {

    fun evaluate(
        header: MorningHeader,
        events: List<MorningEventRecord>,
        evalPoint: TimePoint,
        priorMorningsUnpaidPaise: Paise = Paise.ZERO,
    ): DerivedMorning {
        val sorted = events.sortedBy { it.seq }
        val monthlyRemaining = header.monthlyCapPaise.minusAtLeastZero(header.priorMonthChargedPaise)
        val effectiveCap = Paise.minOf(header.morningCapPaise, monthlyRemaining)

        if (sorted.isEmpty()) {
            return DerivedMorning(
                header = header,
                phase = MorningPhase.IDLE_PRE_FIRE,
                displayPaise = Paise.ZERO,
                morningChargedPaise = Paise.ZERO,
                paidThisMorningPaise = Paise.ZERO,
                morningUnpaidPaise = Paise.ZERO,
                priorMorningsUnpaidPaise = priorMorningsUnpaidPaise,
                totalPayableNowPaise = priorMorningsUnpaidPaise,
                effectiveCapPaise = effectiveCap,
                skyHeatFraction = 0f,
                countdownSeconds = null,
                isOverrideUnlocked = false,
                nextWakeDeadline = null,
                billRows = emptyList(),
                firedAt = null,
                brushStartedAt = null,
                brushCompletedAt = null,
                firstPaidAt = null,
                snoozeCycle = 0,
                snoozeEndAt = null,
                lastNapCostPaise = Paise.ZERO,
                reRingCount = 0,
                isBrushingSeen = false,
                isInOverrideScreen = false,
                isPaymentFailed = false,
                pendingSubmittedRef = null,
                pendingSubmittedPaise = Paise.ZERO,
                capRingUntilAt = null,
                isSummaryDismissed = false,
                upDurationMinutes = null,
                verifiedBrushMs = 0L,
                isWithinFreeWindow = false,
                eventCount = 0,
                evaluatedAtPoint = evalPoint,
                freeWindowRemainingMs = 0L,
            )
        }

        val relMs = LongArray(sorted.size)
        relMs[0] = 0L
        for (i in 1 until sorted.size) {
            relMs[i] = relMs[i - 1] + deltaMs(sorted[i - 1].point, sorted[i].point)
        }
        val evalRelMs = relMs[sorted.lastIndex] + deltaMs(sorted.last().point, evalPoint)

        fun instantAtRel(targetRel: Long): Instant {
            if (targetRel >= evalRelMs) {
                return evalPoint.at.plusMillis(targetRel - evalRelMs)
            }
            if (targetRel <= 0L) {
                return sorted[0].point.at.plusMillis(targetRel)
            }
            var anchorIdx = 0
            for (i in sorted.indices) {
                if (relMs[i] <= targetRel) {
                    anchorIdx = i
                } else {
                    break
                }
            }
            return sorted[anchorIdx].point.at.plusMillis(targetRel - relMs[anchorIdx])
        }

        val freeWindowMs = if (header.hadPriorDebt) 0L else ObolConstants.FREE_WINDOW_SECONDS * 1_000L

        var firedRelMs: Long? = null
        var firedAt: Instant? = null
        var freeEndRelMs: Long = Long.MAX_VALUE

        var cancelled = false
        var graceNightDismissed = false
        var ringTimedOutEpisode: Int? = null
        var summaryDismissed = false

        var cameraOpen = false
        var brushingPaused = false
        var activeBrushPauseStartPoint: TimePoint? = null
        var verifiedBrushMs = 0L
        var dropoutGraceUntilRelMs: Long? = null
        var nonBrushingCameraStartRelMs: Long? = null
        var overrideEverUnlocked = false
        var inOverrideScreen = false

        fun closeActiveBrushPause(endPoint: TimePoint) {
            val startPt = activeBrushPauseStartPoint
            if (brushingPaused && startPt != null) {
                val gained = deltaMs(startPt, endPoint)
                verifiedBrushMs = (verifiedBrushMs + gained).coerceAtMost(ObolConstants.BRUSH_TARGET_MS - 1_000L)
            }
            brushingPaused = false
            activeBrushPauseStartPoint = null
        }

        var firstBrushPausedRelMs: Long? = null
        var firstBrushPausedAt: Instant? = null
        var phase1CompleteRelMs: Long? = null
        var phase1CompleteAt: Instant? = null
        var usedOverride = false
        var overridePaise = Paise.ZERO

        var snoozedUntilRelMs: Long? = null
        var snoozeCycle = 0
        var lastSnoozeStartPaise = Paise.ZERO
        var reRingCount = 0

        var postPayAdvanced = false
        var paymentFailed = false
        var paidThisMorningPaise = Paise.ZERO
        var firstPaidAt: Instant? = null
        var pendingSubmittedRef: String? = null
        var pendingSubmittedPaise = Paise.ZERO

        var phase1BillableMs = 0L
        var cap1ReachedRelMs: Long? = null

        val ratePaisePerMin = header.ratePaisePerMin

        fun computeMeterPaise(billableMs: Long): Paise {
            if (ratePaisePerMin <= 0L) return Paise.ZERO
            val wholeRupees = ((billableMs * ratePaisePerMin) / 60_000L) / 100L
            return Paise(wholeRupees * 100L)
        }

        fun currentDisplayPaise(): Paise {
            if (header.isGraceNight || cancelled) return Paise.ZERO
            val raw = computeMeterPaise(phase1BillableMs) + overridePaise
            return Paise.minOf(effectiveCap, raw)
        }

        val billRows = mutableListOf<BillRow>()
        var freeEndBillRowEmitted = false

        var cursorRelMs = 0L
        var eventIdx = 0

        while (true) {
            while (eventIdx < sorted.size && relMs[eventIdx] == cursorRelMs) {
                val rec = sorted[eventIdx]
                val ev = rec.event
                val evAt = rec.point.at

                if (firedRelMs != null &&
                    !header.isGraceNight &&
                    !cancelled &&
                    phase1CompleteRelMs == null &&
                    freeWindowMs > 0L &&
                    !freeEndBillRowEmitted &&
                    cursorRelMs >= freeEndRelMs
                ) {
                    freeEndBillRowEmitted = true
                    billRows.add(BillRow(instantAtRel(freeEndRelMs), BillRowKind.FREE_WINDOW_ENDED, Paise.ZERO))
                }

                when (ev) {
                    MorningEvent.AlarmFired -> {
                        if (firedRelMs == null) {
                            firedRelMs = cursorRelMs
                            firedAt = evAt
                            freeEndRelMs = cursorRelMs + freeWindowMs
                            if (!header.isGraceNight && effectiveCap.isZero) {
                                cap1ReachedRelMs = cursorRelMs
                            }
                            billRows.add(BillRow(evAt, BillRowKind.ALARM_RANG))
                        }
                    }

                    is MorningEvent.Snoozed -> {
                        if (phase1CompleteRelMs == null && brushingPaused) {
                            closeActiveBrushPause(rec.point)
                        }
                        snoozeCycle = ev.cycle
                        lastSnoozeStartPaise = ev.startDisplayPaise
                        snoozedUntilRelMs = cursorRelMs + ObolConstants.SNOOZE_DURATION_SECONDS * 1_000L
                        cameraOpen = false
                        inOverrideScreen = false
                        val prev = billRows.lastOrNull()
                        if (prev != null &&
                            prev.kind == BillRowKind.RANG_AGAIN &&
                            evAt.toEpochMilli() - prev.at.toEpochMilli() < 60_000L
                        ) {
                            billRows[billRows.lastIndex] = BillRow(
                                at = prev.at,
                                kind = BillRowKind.RANG_AGAIN_AND_SNOOZED,
                                amountPaise = prev.amountPaise,
                            )
                        } else {
                            val amt = currentDisplayPaise().takeIf { it.isPositive }
                            billRows.add(BillRow(evAt, BillRowKind.SNOOZED, amt))
                        }
                    }

                    is MorningEvent.ReRang -> {
                        if (snoozedUntilRelMs != null && phase1CompleteRelMs == null && ringTimedOutEpisode == null) {
                            snoozedUntilRelMs = null
                            reRingCount += 1
                            billRows.add(BillRow(evAt, BillRowKind.RANG_AGAIN, currentDisplayPaise()))
                        }
                    }

                    MorningEvent.GraceNightDismissed -> {
                        if (header.isGraceNight && !graceNightDismissed) {
                            graceNightDismissed = true
                            snoozedUntilRelMs = null
                            billRows.add(BillRow(evAt, BillRowKind.GRACE_NIGHT_DISMISSED))
                        }
                    }

                    MorningEvent.CameraOpened -> {
                        snoozedUntilRelMs = null
                        if (phase1CompleteRelMs == null) {
                            cameraOpen = true
                            inOverrideScreen = false
                            if (!brushingPaused && nonBrushingCameraStartRelMs == null) {
                                nonBrushingCameraStartRelMs = cursorRelMs
                            }
                        }
                    }

                    MorningEvent.CameraClosed -> {
                        if (phase1CompleteRelMs == null) {
                            if (nonBrushingCameraStartRelMs != null &&
                                cursorRelMs - nonBrushingCameraStartRelMs!! >= ObolConstants.OVERRIDE_UNLOCK_MS
                            ) {
                                overrideEverUnlocked = true
                            }
                            closeActiveBrushPause(rec.point)
                            cameraOpen = false
                            dropoutGraceUntilRelMs = null
                            nonBrushingCameraStartRelMs = null
                            inOverrideScreen = false
                        }
                    }

                    MorningEvent.BrushingPausedMeter -> {
                        if (phase1CompleteRelMs == null) {
                            snoozedUntilRelMs = null
                            cameraOpen = true
                            inOverrideScreen = false
                            if (nonBrushingCameraStartRelMs != null &&
                                cursorRelMs - nonBrushingCameraStartRelMs!! >= ObolConstants.OVERRIDE_UNLOCK_MS
                            ) {
                                overrideEverUnlocked = true
                            }
                            nonBrushingCameraStartRelMs = null
                            dropoutGraceUntilRelMs = null
                            if (!brushingPaused) {
                                brushingPaused = true
                                activeBrushPauseStartPoint = rec.point
                                if (firstBrushPausedRelMs == null) {
                                    firstBrushPausedRelMs = cursorRelMs
                                    firstBrushPausedAt = evAt
                                }
                                billRows.add(BillRow(evAt, BillRowKind.BRUSHING_PAUSED_METER, currentDisplayPaise()))
                            }
                        }
                    }

                    MorningEvent.BrushingSightLost -> {
                        if (phase1CompleteRelMs == null && brushingPaused) {
                            closeActiveBrushPause(rec.point)
                            if (cameraOpen) {
                                dropoutGraceUntilRelMs = cursorRelMs + ObolConstants.BRUSH_DROPOUT_GRACE_MS
                                nonBrushingCameraStartRelMs = cursorRelMs
                            }
                        }
                    }

                    MorningEvent.BrushCompleted -> {
                        if (phase1CompleteRelMs == null) {
                            phase1CompleteRelMs = cursorRelMs
                            phase1CompleteAt = evAt
                            cameraOpen = false
                            brushingPaused = false
                            activeBrushPauseStartPoint = null
                            verifiedBrushMs = ObolConstants.BRUSH_TARGET_MS
                            dropoutGraceUntilRelMs = null
                            nonBrushingCameraStartRelMs = null
                            inOverrideScreen = false
                            snoozedUntilRelMs = null
                            postPayAdvanced = false
                            paymentFailed = false
                            billRows.add(BillRow(evAt, BillRowKind.BRUSH_COMPLETED, currentDisplayPaise()))
                        }
                    }

                    MorningEvent.OverrideOpened -> {
                        if (phase1CompleteRelMs == null) {
                            cameraOpen = true
                            inOverrideScreen = true
                            if (brushingPaused) {
                                closeActiveBrushPause(rec.point)
                                dropoutGraceUntilRelMs = cursorRelMs + ObolConstants.BRUSH_DROPOUT_GRACE_MS
                            }
                            if (nonBrushingCameraStartRelMs == null) {
                                nonBrushingCameraStartRelMs = cursorRelMs
                            }
                        }
                    }

                    MorningEvent.OverrideClosed -> {
                        inOverrideScreen = false
                    }

                    is MorningEvent.OverrideAccepted -> {
                        if (phase1CompleteRelMs == null) {
                            closeActiveBrushPause(rec.point)
                            usedOverride = true
                            overridePaise += ev.feePaise
                            phase1CompleteRelMs = cursorRelMs
                            phase1CompleteAt = evAt
                            cameraOpen = false
                            dropoutGraceUntilRelMs = null
                            nonBrushingCameraStartRelMs = null
                            inOverrideScreen = false
                            snoozedUntilRelMs = null
                            postPayAdvanced = false
                            paymentFailed = false
                            billRows.add(BillRow(evAt, BillRowKind.OVERRIDE_ACCEPTED, currentDisplayPaise()))
                        }
                    }

                    is MorningEvent.StayUpScheduled,
                    is MorningEvent.StayUpEarlyOpened,
                    is MorningEvent.StayUpRang,
                    -> {
                        postPayAdvanced = true
                        paymentFailed = false
                    }

                    is MorningEvent.StayUpEarlyCancelled -> Unit

                    is MorningEvent.StayUpFacePassed -> {
                        postPayAdvanced = true
                        paymentFailed = false
                        billRows.add(BillRow(evAt, BillRowKind.STAY_UP_PASSED))
                    }

                    is MorningEvent.RingTimedOut -> {
                        if (ringTimedOutEpisode == null) {
                            closeActiveBrushPause(rec.point)
                            ringTimedOutEpisode = ev.episode
                            snoozedUntilRelMs = null
                            cameraOpen = false
                            billRows.add(BillRow(evAt, BillRowKind.RING_TIMED_OUT, currentDisplayPaise()))
                        }
                    }

                    is MorningEvent.PaymentRecorded -> {
                        paidThisMorningPaise += ev.amountPaise
                        if (firstPaidAt == null && ev.amountPaise.isPositive) {
                            firstPaidAt = evAt
                        }
                        pendingSubmittedRef = null
                        pendingSubmittedPaise = Paise.ZERO
                        paymentFailed = false
                        postPayAdvanced = true
                        billRows.add(BillRow(evAt, BillRowKind.PAID, ev.amountPaise))
                    }

                    is MorningEvent.PaymentPendingSubmitted -> {
                        pendingSubmittedRef = ev.ref
                        pendingSubmittedPaise = ev.amountPaise
                        paymentFailed = false
                        postPayAdvanced = true
                        billRows.add(BillRow(evAt, BillRowKind.PAYMENT_SUBMITTED, ev.amountPaise))
                    }

                    is MorningEvent.PaymentFailed -> {
                        paymentFailed = true
                    }

                    MorningEvent.PaymentDeferred -> {
                        paymentFailed = false
                        postPayAdvanced = true
                    }

                    MorningEvent.MorningDismissed -> {
                        summaryDismissed = true
                        postPayAdvanced = true
                    }

                    is MorningEvent.Cancelled -> {
                        closeActiveBrushPause(rec.point)
                        cancelled = true
                        billRows.add(BillRow(evAt, BillRowKind.CANCELLED))
                    }
                }
                eventIdx += 1
            }

            if (cursorRelMs >= evalRelMs) {
                break
            }

            var nextRelMs = evalRelMs
            if (eventIdx < sorted.size && relMs[eventIdx] < nextRelMs) {
                nextRelMs = relMs[eventIdx]
            }
            if (firedRelMs != null && cursorRelMs < freeEndRelMs && freeEndRelMs < nextRelMs) {
                nextRelMs = freeEndRelMs
            }
            val graceEnd = dropoutGraceUntilRelMs
            if (graceEnd != null && cursorRelMs < graceEnd && graceEnd < nextRelMs) {
                nextRelMs = graceEnd
            }

            val dt = nextRelMs - cursorRelMs
            if (dt > 0L) {
                val isPhase1Billable = firedRelMs != null &&
                    !header.isGraceNight &&
                    !cancelled &&
                    ringTimedOutEpisode == null &&
                    phase1CompleteRelMs == null &&
                    cursorRelMs >= freeEndRelMs &&
                    !brushingPaused &&
                    !(cameraOpen && dropoutGraceUntilRelMs != null && cursorRelMs < dropoutGraceUntilRelMs!!)

                val neededMeterPaise = max(0L, effectiveCap.value - overridePaise.value)
                val neededRupees = neededMeterPaise / 100L
                val targetTotalBillableMs = when {
                    neededRupees <= 0L -> 0L
                    ratePaisePerMin <= 0L -> Long.MAX_VALUE
                    else -> (neededRupees * 60_000L * 100L + ratePaisePerMin - 1L) / ratePaisePerMin
                }

                if (isPhase1Billable) {
                    if (cap1ReachedRelMs == null) {
                        val prevTotal = phase1BillableMs
                        if (prevTotal >= targetTotalBillableMs) {
                            cap1ReachedRelMs = cursorRelMs
                        } else if (prevTotal + dt >= targetTotalBillableMs) {
                            cap1ReachedRelMs = cursorRelMs + (targetTotalBillableMs - prevTotal)
                        }
                    }
                    phase1BillableMs += dt
                }

                if (nonBrushingCameraStartRelMs != null &&
                    (nextRelMs - nonBrushingCameraStartRelMs!!) >= ObolConstants.OVERRIDE_UNLOCK_MS
                ) {
                    overrideEverUnlocked = true
                }

                if (firedRelMs != null &&
                    !header.isGraceNight &&
                    !cancelled &&
                    phase1CompleteRelMs == null &&
                    freeWindowMs > 0L &&
                    !freeEndBillRowEmitted &&
                    nextRelMs >= freeEndRelMs
                ) {
                    freeEndBillRowEmitted = true
                    billRows.add(BillRow(instantAtRel(freeEndRelMs), BillRowKind.FREE_WINDOW_ENDED, Paise.ZERO))
                }

                if (graceEnd != null &&
                    cameraOpen &&
                    !brushingPaused &&
                    phase1CompleteRelMs == null &&
                    cursorRelMs < graceEnd &&
                    nextRelMs >= graceEnd &&
                    nextRelMs >= freeEndRelMs &&
                    !header.isGraceNight
                ) {
                    billRows.add(BillRow(instantAtRel(graceEnd), BillRowKind.BRUSHING_RESUMED_METER, currentDisplayPaise()))
                }
            }

            cursorRelMs = nextRelMs
        }

        val displayPaise = if (header.isGraceNight || cancelled) {
            Paise.ZERO
        } else {
            Paise.minOf(effectiveCap, computeMeterPaise(phase1BillableMs) + overridePaise)
        }

        val morningChargedPaise = if (header.isGraceNight || cancelled) {
            Paise.ZERO
        } else {
            displayPaise
        }

        val morningUnpaidPaise = morningChargedPaise.minusAtLeastZero(paidThisMorningPaise)
        val totalPayableNowPaise = priorMorningsUnpaidPaise + morningUnpaidPaise
        val isPaymentDueNow = totalPayableNowPaise >= ObolConstants.MIN_DONATION_THRESHOLD_PAISE

        val isBrushingActivelySeen = brushingPaused
        val isOverrideUnlocked = overrideEverUnlocked ||
            (nonBrushingCameraStartRelMs != null &&
                (evalRelMs - nonBrushingCameraStartRelMs!!) >= ObolConstants.OVERRIDE_UNLOCK_MS)

        val phase: MorningPhase = when {
            cancelled -> MorningPhase.CANCELLED
            firedRelMs == null -> MorningPhase.IDLE_PRE_FIRE
            ringTimedOutEpisode != null -> {
                if (header.isGraceNight || ringTimedOutEpisode == 0 || morningUnpaidPaise.isZero) {
                    MorningPhase.CLOSED_DONE
                } else {
                    MorningPhase.CLOSED_UNPAID
                }
            }
            header.isGraceNight -> {
                if (graceNightDismissed) {
                    MorningPhase.CLOSED_DONE
                } else if (snoozedUntilRelMs != null) {
                    MorningPhase.SNOOZED
                } else {
                    MorningPhase.GRACE_NIGHT_RING
                }
            }
            phase1CompleteRelMs == null -> {
                when {
                    cameraOpen -> MorningPhase.BRUSHING
                    snoozedUntilRelMs != null -> MorningPhase.SNOOZED
                    evalRelMs < freeEndRelMs -> MorningPhase.FREE_WINDOW
                    displayPaise >= effectiveCap -> MorningPhase.CAPPED_RINGING
                    else -> MorningPhase.METERING
                }
            }
            isPaymentDueNow && !postPayAdvanced -> MorningPhase.POST_CHECK_PAY_OR_ZERO
            else -> MorningPhase.CLOSED_DONE
        }

        val isHotSky = when (phase) {
            MorningPhase.METERING,
            MorningPhase.SNOOZED,
            MorningPhase.CAPPED_RINGING,
            MorningPhase.BRUSHING,
            -> !header.isGraceNight
            MorningPhase.POST_CHECK_PAY_OR_ZERO -> isPaymentDueNow
            else -> false
        }

        val skyHeatFraction = if (!isHotSky) {
            0f
        } else if (effectiveCap.isZero) {
            1f
        } else {
            (displayPaise.value.toFloat() / effectiveCap.value.toFloat()).coerceIn(0f, 1f)
        }

        val capRingUntilRelMs: Long? = when {
            firedRelMs == null || cancelled -> null
            header.isGraceNight && !graceNightDismissed && ringTimedOutEpisode == null -> {
                firedRelMs!! + ObolConstants.GRACE_NIGHT_RING_LIMIT_SEC * 1_000L
            }
            phase1CompleteRelMs == null && displayPaise >= effectiveCap && ringTimedOutEpisode == null -> {
                val capRel = cap1ReachedRelMs ?: evalRelMs
                max(firedRelMs!!, capRel) + ObolConstants.POST_CAP_RING_LIMIT_SEC * 1_000L
            }
            else -> null
        }

        val countdownSeconds: Long? = when (phase) {
            MorningPhase.FREE_WINDOW -> ceilSeconds(freeEndRelMs - evalRelMs)
            MorningPhase.SNOOZED -> snoozedUntilRelMs?.let { ceilSeconds(it - evalRelMs) }
            else -> null
        }

        fun buildDeadline(relTarget: Long, kind: WakeDeadlineKind, index: Int): WakeDeadline {
            val due = evalRelMs >= relTarget
            val overdue = if (due) max(0L, evalRelMs - relTarget) else 0L
            return WakeDeadline(
                at = instantAtRel(relTarget),
                kind = kind,
                roundOrCycleOrEpisode = index,
                isDue = due,
                overdueMs = overdue,
            )
        }

        val nextWakeDeadline: WakeDeadline? = when (phase) {
            MorningPhase.GRACE_NIGHT_RING -> capRingUntilRelMs?.let {
                buildDeadline(it, WakeDeadlineKind.RING_TIMEOUT, 0)
            }
            MorningPhase.SNOOZED -> {
                val snoozeRel = snoozedUntilRelMs
                if (snoozeRel != null && (capRingUntilRelMs == null || snoozeRel <= capRingUntilRelMs)) {
                    buildDeadline(snoozeRel, WakeDeadlineKind.SNOOZE_RE_RING, snoozeCycle)
                } else {
                    capRingUntilRelMs?.let {
                        buildDeadline(it, WakeDeadlineKind.RING_TIMEOUT, if (header.isGraceNight) 0 else 1)
                    }
                }
            }
            MorningPhase.CAPPED_RINGING, MorningPhase.BRUSHING -> capRingUntilRelMs?.let {
                buildDeadline(it, WakeDeadlineKind.RING_TIMEOUT, 1)
            }
            else -> null
        }

        val upDurationMinutes: Long? = if (firedRelMs != null && phase1CompleteRelMs != null) {
            val anchorEndRel = when {
                firstBrushPausedRelMs != null -> firstBrushPausedRelMs!!
                usedOverride -> phase1CompleteRelMs!!
                else -> max(firedRelMs!!, phase1CompleteRelMs!! - ObolConstants.BRUSH_TARGET_MS)
            }
            max(0L, anchorEndRel - firedRelMs!!) / 60_000L
        } else {
            null
        }

        val resolvedBrushStartAt: Instant? = when {
            usedOverride -> null
            firstBrushPausedAt != null -> firstBrushPausedAt
            phase1CompleteAt != null ->
                phase1CompleteAt!!.minusMillis(ObolConstants.BRUSH_TARGET_MS)
            else -> null
        }

        val isWithinFreeWindow = firedRelMs != null &&
            !cancelled &&
            ringTimedOutEpisode == null &&
            phase1CompleteRelMs == null &&
            (header.isGraceNight || evalRelMs < freeEndRelMs)

        val freeWindowRemainingMs = when {
            !isWithinFreeWindow -> 0L
            header.isGraceNight -> Long.MAX_VALUE
            else -> max(0L, freeEndRelMs - evalRelMs)
        }

        return DerivedMorning(
            header = header,
            phase = phase,
            displayPaise = displayPaise,
            morningChargedPaise = morningChargedPaise,
            paidThisMorningPaise = paidThisMorningPaise,
            morningUnpaidPaise = morningUnpaidPaise,
            priorMorningsUnpaidPaise = priorMorningsUnpaidPaise,
            totalPayableNowPaise = totalPayableNowPaise,
            effectiveCapPaise = effectiveCap,
            skyHeatFraction = skyHeatFraction,
            countdownSeconds = countdownSeconds,
            isOverrideUnlocked = isOverrideUnlocked,
            nextWakeDeadline = nextWakeDeadline,
            billRows = billRows,
            firedAt = firedAt,
            brushStartedAt = resolvedBrushStartAt,
            brushCompletedAt = phase1CompleteAt,
            firstPaidAt = firstPaidAt,
            snoozeCycle = snoozeCycle,
            snoozeEndAt = snoozedUntilRelMs?.let { instantAtRel(it) },
            lastNapCostPaise = displayPaise.minusAtLeastZero(lastSnoozeStartPaise),
            reRingCount = reRingCount,
            isBrushingSeen = isBrushingActivelySeen,
            isInOverrideScreen = inOverrideScreen,
            isPaymentFailed = paymentFailed,
            pendingSubmittedRef = pendingSubmittedRef,
            pendingSubmittedPaise = pendingSubmittedPaise,
            capRingUntilAt = capRingUntilRelMs?.let { instantAtRel(it) },
            isSummaryDismissed = summaryDismissed,
            upDurationMinutes = upDurationMinutes,
            verifiedBrushMs = verifiedBrushMs,
            isWithinFreeWindow = isWithinFreeWindow,
            eventCount = sorted.size,
            evaluatedAtPoint = evalPoint,
            freeWindowRemainingMs = freeWindowRemainingMs,
        )
    }

    fun nextDueSystemTransition(
        header: MorningHeader,
        events: List<MorningEventRecord>,
        evalPoint: TimePoint,
    ): Pair<MorningEvent, TimePoint>? {
        val derived = evaluate(header, events, evalPoint)
        val deadline = derived.nextWakeDeadline ?: return null
        if (!deadline.isDue) {
            return null
        }
        val dueEvent = when (deadline.kind) {
            WakeDeadlineKind.SNOOZE_RE_RING -> MorningEvent.ReRang(deadline.roundOrCycleOrEpisode)
            WakeDeadlineKind.RING_TIMEOUT -> MorningEvent.RingTimedOut(deadline.roundOrCycleOrEpisode)
        }
        val overdue = deadline.overdueMs
        val lastPoint = events.maxByOrNull { it.seq }?.point
        val deadlinePoint = when {
            overdue <= 0L -> evalPoint
            lastPoint != null && lastPoint.bootCount == evalPoint.bootCount -> TimePoint(
                at = evalPoint.at.minusMillis(overdue),
                bootCount = evalPoint.bootCount,
                elapsedMs = max(0L, evalPoint.elapsedMs - overdue),
            )
            overdue <= evalPoint.elapsedMs -> TimePoint(
                at = evalPoint.at.minusMillis(overdue),
                bootCount = evalPoint.bootCount,
                elapsedMs = evalPoint.elapsedMs - overdue,
            )
            else -> TimePoint(
                at = evalPoint.at.minusMillis(overdue),
                bootCount = -(events.size.toLong() + 1L),
                elapsedMs = 0L,
            )
        }
        return dueEvent to deadlinePoint
    }

    fun nextDueSystemEvent(
        header: MorningHeader,
        events: List<MorningEventRecord>,
        evalPoint: TimePoint,
    ): MorningEvent? = nextDueSystemTransition(header, events, evalPoint)?.first

    private fun ceilSeconds(remainingMs: Long): Long {
        if (remainingMs <= 0L) return 0L
        return (remainingMs + 999L) / 1_000L
    }
}
