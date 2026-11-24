package dev.prayush.obol.domain

import dev.prayush.obol.domain.engine.MorningDerivation
import dev.prayush.obol.domain.model.BillRowKind
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningEventRecord
import dev.prayush.obol.domain.model.MorningHeader
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.time.FakeObolClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MorningDerivationTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val baseAlarmInstant = Instant.parse("2026-11-18T00:40:00Z") // Wed 06:10 IST

    private fun makeHeader(
        isTrialDay: Boolean = false,
        isGraceNight: Boolean = false,
        hadPriorDebt: Boolean = false,
        priorMonthChargedPaise: Paise = Paise.ZERO,
    ) = MorningHeader(
        morningId = "2026-11-18",
        calendarDate = LocalDate.of(2026, 11, 18),
        scheduledAt = baseAlarmInstant,
        isTrialDay = isTrialDay,
        isGraceNight = isGraceNight,
        hadPriorDebt = hadPriorDebt,
        priorMonthChargedPaise = priorMonthChargedPaise,
    )

    private class EventLogBuilder(val clock: FakeObolClock) {
        private var seq = 0
        val records = mutableListOf<MorningEventRecord>()

        fun emit(event: MorningEvent) {
            seq += 1
            records.add(MorningEventRecord(seq = seq, point = clock.timePoint(), event = event))
        }
    }

    @Test
    fun `cuj 1 - clean morning finishes 10s brushing and closes immediately free`() {
        assertEquals(10_000L, ObolConstants.BRUSH_TARGET_MS)

        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        log.emit(MorningEvent.AlarmFired)
        val atFire = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.FREE_WINDOW, atFire.phase)
        assertEquals(300L, atFire.countdownSeconds)
        assertEquals(0f, atFire.skyHeatFraction)
        assertTrue(atFire.requiresAlarmService)

        // 06:14 -> start brushing inside free window
        clock.advanceMinutes(4)
        log.emit(MorningEvent.CameraOpened)
        log.emit(MorningEvent.BrushingPausedMeter)
        val brushing = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.BRUSHING, brushing.phase)
        assertTrue(brushing.isBrushingSeen)

        // 06:14:10 -> 10s brushing done -> closes immediately into CLOSED_DONE with no stay-up check!
        clock.advanceSeconds(10)
        log.emit(MorningEvent.BrushCompleted)
        val closed = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CLOSED_DONE, closed.phase)
        assertFalse(closed.isActivePostFire)
        assertFalse(closed.requiresAlarmService)
        assertEquals(Paise.ZERO, closed.morningChargedPaise)
        assertEquals(Paise.ZERO, closed.totalPayableNowPaise)
        assertEquals(4L, closed.upDurationMinutes)
        assertNull(closed.nextWakeDeadline)

        // Advancing 30m more triggers zero system events (no 20-minute stay-up check)
        clock.advanceMinutes(30)
        assertNull(MorningDerivation.nextDueSystemEvent(header, log.records, clock.timePoint()))
    }

    @Test
    fun `cuj 2 - snoozed paid morning stops meter after 10s brushing and closes right after payment once at 300 threshold`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        // 06:10 Alarm rings
        log.emit(MorningEvent.AlarmFired)

        // 06:13 Snooze inside free window (₹0)
        clock.advanceMinutes(3)
        log.emit(MorningEvent.Snoozed(cycle = 1, startDisplayPaise = Paise.ZERO))

        // 06:18 Re-ring (3m past 06:15 at ₹20/min -> ₹60), snooze again immediately
        clock.advanceMinutes(5)
        log.emit(MorningEvent.ReRang(cycle = 1))
        val at618 = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(Paise.fromRupees(60), at618.displayPaise)
        assertEquals(Paise.fromRupees(60), at618.lastNapCostPaise)
        log.emit(MorningEvent.Snoozed(cycle = 2, startDisplayPaise = at618.displayPaise))

        // 06:20 Mid-snooze check (5m billed -> ₹100)
        clock.advanceMinutes(2)
        val at620 = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.SNOOZED, at620.phase)
        assertEquals(Paise.fromRupees(100), at620.displayPaise)
        assertEquals(180L, at620.countdownSeconds)

        // 06:23 Re-ring (8m billed -> ₹160, nap cost ₹100)
        clock.advanceMinutes(3)
        log.emit(MorningEvent.ReRang(cycle = 2))
        val at623 = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.METERING, at623.phase)
        assertEquals(Paise.fromRupees(160), at623.displayPaise)
        assertEquals(Paise.fromRupees(100), at623.lastNapCostPaise)

        // 06:30 Brushing seen (15m billed -> meter pauses at ₹300, hitting minimum donation threshold)
        clock.advanceMinutes(7)
        log.emit(MorningEvent.CameraOpened)
        log.emit(MorningEvent.BrushingPausedMeter)
        val at630 = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(Paise.fromRupees(300), at630.displayPaise)

        // +10s -> 10s brushing done -> meter stops at ₹300, awaits payment with no alarm service needed
        clock.advanceSeconds(10)
        log.emit(MorningEvent.BrushCompleted)
        val postBrush = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, postBrush.phase)
        assertTrue(postBrush.isActivePostFire)
        assertFalse(postBrush.requiresAlarmService)
        assertEquals(Paise.fromRupees(300), postBrush.morningUnpaidPaise)
        assertEquals(20L, postBrush.upDurationMinutes)
        assertNull(postBrush.nextWakeDeadline)

        // Pay ₹300 -> closes directly into CLOSED_DONE!
        clock.advanceSeconds(50)
        log.emit(MorningEvent.PaymentRecorded(Paise.fromRupees(300), "donorbox_oppia", "ref-1"))
        val closed = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CLOSED_DONE, closed.phase)
        assertEquals(Paise.fromRupees(300), closed.morningChargedPaise)
        assertEquals(Paise.ZERO, closed.morningUnpaidPaise)
        assertNull(closed.nextWakeDeadline)

        val kinds = closed.billRows.map { it.kind }
        assertEquals(
            listOf(
                BillRowKind.ALARM_RANG,
                BillRowKind.SNOOZED,
                BillRowKind.FREE_WINDOW_ENDED,
                BillRowKind.RANG_AGAIN_AND_SNOOZED,
                BillRowKind.RANG_AGAIN,
                BillRowKind.BRUSHING_PAUSED_METER,
                BillRowKind.BRUSH_COMPLETED,
                BillRowKind.PAID,
            ),
            kinds,
        )
    }

    @Test
    fun `running tab below 300 closes without checkout and triggers checkout once combined unpaid reaches 300`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader(hadPriorDebt = false)

        // 06:10 Alarm rings -> 5m free + 7m billed = ₹140 (< ₹300 threshold)
        log.emit(MorningEvent.AlarmFired)
        clock.advanceMinutes(12)
        log.emit(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(10)
        log.emit(MorningEvent.BrushCompleted)

        // Alone (₹140 < ₹300): goes straight to CLOSED_DONE on tab (no broken checkout!)
        val onTabAlone = MorningDerivation.evaluate(
            header = header,
            events = log.records,
            evalPoint = clock.timePoint(),
            priorMorningsUnpaidPaise = Paise.ZERO,
        )
        assertEquals(MorningPhase.CLOSED_DONE, onTabAlone.phase)
        assertEquals(Paise.fromRupees(140), onTabAlone.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(140), onTabAlone.totalPayableNowPaise)

        // With ₹159 prior tab (total ₹299 < ₹300): still on tab!
        val stillUnder = MorningDerivation.evaluate(
            header = header,
            events = log.records,
            evalPoint = clock.timePoint(),
            priorMorningsUnpaidPaise = Paise.fromRupees(159),
        )
        assertEquals(MorningPhase.CLOSED_DONE, stillUnder.phase)
        assertEquals(Paise.fromRupees(299), stillUnder.totalPayableNowPaise)

        // With ₹160 prior tab (total ₹300 >= ₹300): checkout opens!
        val hitsThreshold = MorningDerivation.evaluate(
            header = header,
            events = log.records,
            evalPoint = clock.timePoint(),
            priorMorningsUnpaidPaise = Paise.fromRupees(160),
        )
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, hitsThreshold.phase)
        assertEquals(Paise.fromRupees(140), hitsThreshold.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(300), hitsThreshold.totalPayableNowPaise)
    }

    @Test
    fun `cuj 3 - prior debt removes free window and deferring payment closes morning with unpaid debt`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader(hadPriorDebt = true)
        val priorUnpaid = Paise.fromRupees(300)

        log.emit(MorningEvent.AlarmFired)
        // Immediately metering with zero free window (5m at ₹20/min = ₹100)
        clock.advanceMinutes(5)
        log.emit(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(10)
        log.emit(MorningEvent.BrushCompleted)

        val derived = MorningDerivation.evaluate(
            header = header,
            events = log.records,
            evalPoint = clock.timePoint(),
            priorMorningsUnpaidPaise = priorUnpaid,
        )
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, derived.phase)
        assertEquals(Paise.fromRupees(100), derived.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(400), derived.totalPayableNowPaise)

        // Tapping Later (PaymentDeferred) closes the morning immediately while preserving debt
        log.emit(MorningEvent.PaymentDeferred)
        val deferred = MorningDerivation.evaluate(
            header = header,
            events = log.records,
            evalPoint = clock.timePoint(),
            priorMorningsUnpaidPaise = priorUnpaid,
        )
        assertEquals(MorningPhase.CLOSED_DONE, deferred.phase)
        assertEquals(Paise.fromRupees(100), deferred.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(400), deferred.totalPayableNowPaise)
    }

    @Test
    fun `legacy StayUp events in historical logs replay cleanly to CLOSED_DONE`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        log.emit(MorningEvent.AlarmFired)
        clock.advanceMinutes(7) // 2m billed at ₹20/min = ₹40
        log.emit(MorningEvent.BrushCompleted)
        log.emit(MorningEvent.PaymentRecorded(Paise.fromRupees(40), "upi", "tx-legacy"))
        log.emit(MorningEvent.StayUpScheduled(1, clock.now().plusSeconds(1_200L)))
        clock.advanceMinutes(20)
        log.emit(MorningEvent.StayUpRang(1))
        log.emit(MorningEvent.StayUpFacePassed(1))

        val replayed = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CLOSED_DONE, replayed.phase)
        assertEquals(Paise.fromRupees(40), replayed.morningChargedPaise)
        assertEquals(Paise.ZERO, replayed.morningUnpaidPaise)
    }

    @Test
    fun `cuj 5 and clock tamper - reboot recovery and same-boot wall-clock shift resistance`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        log.emit(MorningEvent.AlarmFired) // 06:10

        // Same-boot wall-clock tamper: rewind wall clock by 3 hours while 6 monotonic minutes pass (1m billed -> ₹20)
        clock.advanceMinutes(6)
        clock.shiftWallClockOnly(-3 * 3_600_000L)
        val tampered = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.METERING, tampered.phase)
        assertEquals(Paise.fromRupees(20), tampered.displayPaise)

        // Restore wall clock and simulate dangling BrushingPausedMeter at 06:20 closed at start, then reboot to 06:22 (7m billed -> ₹140)
        clock.shiftWallClockOnly(3 * 3_600_000L)
        clock.advanceMinutes(4) // 06:20
        log.emit(MorningEvent.BrushingPausedMeter)
        log.emit(MorningEvent.BrushingSightLost)
        log.emit(MorningEvent.CameraClosed)

        clock.reboot(downtimeMs = 2 * 60_000L) // 06:22
        val postReboot = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.METERING, postReboot.phase)
        assertEquals(Paise.fromRupees(140), postReboot.displayPaise)
    }

    @Test
    fun `10s brushing dropout grace is non-billed and 60s override unlocks after brief pass`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader(hadPriorDebt = true)

        log.emit(MorningEvent.AlarmFired)
        clock.advanceSeconds(3) // ₹1 at ₹20/min
        log.emit(MorningEvent.CameraOpened)
        log.emit(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(4)
        log.emit(MorningEvent.BrushingSightLost)

        // 10.0s lost -> still ₹1 (zero billing inside 10s grace)
        clock.advanceSeconds(10)
        val at10s = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(Paise.fromRupees(1), at10s.displayPaise)
        assertFalse(at10s.isOverrideUnlocked)

        // +3.0s more (13s total since sight lost) -> +₹1 = ₹2
        clock.advanceSeconds(3)
        assertEquals(Paise.fromRupees(2), MorningDerivation.evaluate(header, log.records, clock.timePoint()).displayPaise)

        // +47s more -> 60s continuous non-brushing since BrushingSightLost -> override unlocked!
        // Total billable = 3s + 50s = 53s -> floor(53/3) = ₹17
        clock.advanceSeconds(47)
        val at60s = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertTrue(at60s.isOverrideUnlocked)

        // Accept ₹20 override -> +₹20 added to ₹17 = ₹37 (< ₹300, rolls onto running tab -> CLOSED_DONE), brushStartedAt is null
        log.emit(MorningEvent.OverrideOpened)
        log.emit(MorningEvent.OverrideAccepted())
        val afterOverride = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CLOSED_DONE, afterOverride.phase)
        assertEquals(Paise.fromRupees(37), afterOverride.displayPaise)
        assertEquals(Paise.fromRupees(37), afterOverride.morningUnpaidPaise)
        assertNull(afterOverride.brushStartedAt)
    }

    @Test
    fun `capped snooze cycle, per-episode 60m timeout, no-trial real charging invariant, and grace night`() {
        // 1. Capped <-> Snoozed + 60m post-cap timeout
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        log.emit(MorningEvent.AlarmFired)
        clock.advanceMinutes(35) // 5m free + 30m billed at ₹20/min = ₹600 cap at 06:45
        val capped = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CAPPED_RINGING, capped.phase)
        assertEquals(ObolConstants.MORNING_CAP_PAISE, capped.displayPaise)
        assertEquals(Paise.fromRupees(600), capped.displayPaise)
        assertEquals(clock.now().plusSeconds(3_600L), capped.capRingUntilAt)

        // Snooze while capped at 06:50 -> re-ring at 06:55 -> still CAPPED_RINGING, timeout still 07:45
        clock.advanceMinutes(5)
        log.emit(MorningEvent.Snoozed(cycle = 1, startDisplayPaise = capped.displayPaise))
        assertEquals(MorningPhase.SNOOZED, MorningDerivation.evaluate(header, log.records, clock.timePoint()).phase)
        clock.advanceMinutes(5)
        log.emit(MorningEvent.ReRang(cycle = 1))
        val cappedAgain = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CAPPED_RINGING, cappedAgain.phase)

        // Advance 50m to 07:45 -> RingTimedOut(1) due -> CLOSED_UNPAID
        clock.advanceMinutes(50)
        val dueTimeout = MorningDerivation.nextDueSystemEvent(header, log.records, clock.timePoint())
        assertEquals(MorningEvent.RingTimedOut(1), dueTimeout)
        log.emit(dueTimeout!!)
        val closedUnpaid = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CLOSED_UNPAID, closedUnpaid.phase)
        assertEquals(Paise.fromRupees(600), closedUnpaid.morningUnpaidPaise)

        // Monthly cap enforcement at ₹6,000 (₹5,800 already charged -> effective cap is ₹200)
        val nearMonthlyCapHeader = makeHeader(priorMonthChargedPaise = Paise.fromRupees(5_800))
        val clockMonthly = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val logMonthly = EventLogBuilder(clockMonthly)
        logMonthly.emit(MorningEvent.AlarmFired)
        clockMonthly.advanceMinutes(20) // 15m billed -> would be ₹300, capped at ₹200 remaining of ₹6,000
        val monthlyCapped = MorningDerivation.evaluate(nearMonthlyCapHeader, logMonthly.records, clockMonthly.timePoint())
        assertEquals(MorningPhase.CAPPED_RINGING, monthlyCapped.phase)
        assertEquals(Paise.fromRupees(200), monthlyCapped.effectiveCapPaise)
        assertEquals(Paise.fromRupees(200), monthlyCapped.displayPaise)

        // 2. Sleeping on 17-pay past +20m does NOT trigger any stay-up ring
        val clock2 = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log2 = EventLogBuilder(clock2)
        log2.emit(MorningEvent.AlarmFired)
        clock2.advanceMinutes(20) // 15m billed = ₹300 -> POST_CHECK_PAY_OR_ZERO
        log2.emit(MorningEvent.BrushCompleted)
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, MorningDerivation.evaluate(header, log2.records, clock2.timePoint()).phase)
        clock2.advanceMinutes(25)
        assertNull(MorningDerivation.nextDueSystemEvent(header, log2.records, clock2.timePoint()))

        // 3. No trial week: money is ALWAYS counted (even if legacy isTrialDay = true), requiring settlement at ≥ ₹300
        val legacyTrialHeader = makeHeader(isTrialDay = true)
        val clock3 = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log3 = EventLogBuilder(clock3)
        log3.emit(MorningEvent.AlarmFired)
        clock3.advanceMinutes(20) // 15m billed at ₹20/min = ₹300 real charge
        val activeDay1 = MorningDerivation.evaluate(legacyTrialHeader, log3.records, clock3.timePoint())
        assertEquals(Paise.fromRupees(300), activeDay1.displayPaise)
        assertEquals(Paise.fromRupees(300), activeDay1.morningChargedPaise)
        assertEquals(Paise.fromRupees(300), activeDay1.morningUnpaidPaise)
        assertTrue(activeDay1.skyHeatFraction > 0f)
        log3.emit(MorningEvent.BrushCompleted)
        val postBrushDay1 = MorningDerivation.evaluate(legacyTrialHeader, log3.records, clock3.timePoint())
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, postBrushDay1.phase)
        assertEquals(Paise.fromRupees(300), postBrushDay1.morningChargedPaise)
        assertEquals(Paise.fromRupees(300), postBrushDay1.totalPayableNowPaise)
        log3.emit(MorningEvent.MorningDismissed)
        val dismissedLegacy = MorningDerivation.evaluate(legacyTrialHeader, log3.records, clock3.timePoint())
        assertEquals(MorningPhase.CLOSED_DONE, dismissedLegacy.phase)
        assertTrue(dismissedLegacy.isSummaryDismissed)
        assertEquals(Paise.fromRupees(300), dismissedLegacy.morningUnpaidPaise)

        // 4. Grace night: ₹0 always, 60m ring timeout -> CLOSED_DONE
        val graceHeader = makeHeader(isGraceNight = true)
        val clock4 = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log4 = EventLogBuilder(clock4)
        log4.emit(MorningEvent.AlarmFired)
        clock4.advanceMinutes(60)
        val graceTimeout = MorningDerivation.nextDueSystemEvent(graceHeader, log4.records, clock4.timePoint())
        assertEquals(MorningEvent.RingTimedOut(0), graceTimeout)
        log4.emit(graceTimeout!!)
        val graceClosed = MorningDerivation.evaluate(graceHeader, log4.records, clock4.timePoint())
        assertEquals(MorningPhase.CLOSED_DONE, graceClosed.phase)
        assertEquals(Paise.ZERO, graceClosed.morningChargedPaise)
    }

    @Test
    fun `system transitions are idempotent and overdue cap timeout deadlines cannot be dodged by reboot`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        log.emit(MorningEvent.AlarmFired) // 06:10
        clock.advanceMinutes(3)
        log.emit(MorningEvent.Snoozed(cycle = 1, startDisplayPaise = Paise.ZERO))
        clock.advanceMinutes(5) // 06:18
        log.emit(MorningEvent.ReRang(cycle = 1))
        log.emit(MorningEvent.ReRang(cycle = 1)) // Duplicate ReRang race must be ignored
        val afterDupReRing = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(1, afterDupReRing.reRingCount)
        assertEquals(1, afterDupReRing.billRows.count { it.kind == BillRowKind.RANG_AGAIN })

        // Power off phone for 3h while ringing uncapped -> reboot at 09:18 triggers RingTimedOut(1) stamped at 07:45 (06:45 cap + 60m)
        clock.reboot(downtimeMs = 180 * 60_000L)
        val t1 = MorningDerivation.nextDueSystemTransition(header, log.records, clock.timePoint())!!
        assertEquals(MorningEvent.RingTimedOut(1), t1.first)
        assertEquals(baseAlarmInstant.plusSeconds(95 * 60L), t1.second.at)
        log.records.add(MorningEventRecord(seq = log.records.size + 1, point = t1.second, event = t1.first))

        val postPowerOff = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(MorningPhase.CLOSED_UNPAID, postPowerOff.phase)
        assertEquals(Paise.fromRupees(600), postPowerOff.morningChargedPaise)
        assertEquals(Paise.fromRupees(600), postPowerOff.morningUnpaidPaise)
    }

    @Test
    fun `persisted verifiedBrushMs accumulates closed brushing intervals across dropouts and reboots for 10s target`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val header = makeHeader()

        log.emit(MorningEvent.AlarmFired) // 06:10
        assertTrue(MorningDerivation.evaluate(header, log.records, clock.timePoint()).isWithinFreeWindow)

        // First brushing bout: 4s inside free window, then sight lost
        clock.advanceMinutes(1)
        log.emit(MorningEvent.CameraOpened)
        log.emit(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(4)
        log.emit(MorningEvent.BrushingSightLost)
        log.emit(MorningEvent.CameraClosed)

        val afterBout1 = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(4_000L, afterBout1.verifiedBrushMs)
        assertTrue(afterBout1.isWithinFreeWindow)

        // Cross free-window boundary into METERING (06:16:00)
        clock.advanceSeconds(296)
        val inMetering = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertFalse(inMetering.isWithinFreeWindow)
        assertEquals(4_000L, inMetering.verifiedBrushMs)

        // Second bout: 3s, closed by BrushingSightLost -> 7s total
        log.emit(MorningEvent.CameraOpened)
        log.emit(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(3)
        assertEquals(4_000L, MorningDerivation.evaluate(header, log.records, clock.timePoint()).verifiedBrushMs)
        log.emit(MorningEvent.BrushingSightLost)
        assertEquals(7_000L, MorningDerivation.evaluate(header, log.records, clock.timePoint()).verifiedBrushMs)

        // Crash at start of third bout + reboot -> 7s preserved
        clock.advanceSeconds(5)
        log.emit(MorningEvent.BrushingPausedMeter)
        log.emit(MorningEvent.BrushingSightLost)
        log.emit(MorningEvent.CameraClosed)
        clock.reboot(downtimeMs = 60_000L)

        val postReboot = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(7_000L, postReboot.verifiedBrushMs)

        // Final 3s bout finishes BrushCompleted -> exact 10_000ms
        log.emit(MorningEvent.CameraOpened)
        log.emit(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(3)
        log.emit(MorningEvent.BrushCompleted)
        val completed = MorningDerivation.evaluate(header, log.records, clock.timePoint())
        assertEquals(ObolConstants.BRUSH_TARGET_MS, completed.verifiedBrushMs)
        assertFalse(completed.isWithinFreeWindow)
    }

    @Test
    fun `isWithinFreeWindowAt tracks exact monotonic boundary across stale snapshots, wall-clock tamper, debt, and grace night`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        log.emit(MorningEvent.AlarmFired) // 06:10:00 -> free window ends at +300_000ms (06:15:00)

        clock.advanceSeconds(299) // 06:14:59 -> 1_000ms left
        val snapAt299 = MorningDerivation.evaluate(makeHeader(), log.records, clock.timePoint())
        assertTrue(snapAt299.isWithinFreeWindow)
        assertEquals(1_000L, snapAt299.freeWindowRemainingMs)
        assertTrue(snapAt299.isWithinFreeWindowAt(clock.timePoint()))

        clock.advanceByMs(999L) // +299.999s -> still inside free window
        assertTrue(snapAt299.isWithinFreeWindowAt(clock.timePoint()))

        // Same-boot wall-clock rewind cannot keep free window alive at +300.000s monotonic
        clock.shiftWallClockOnly(-3_600_000L)
        clock.advanceByMs(1L) // +300.000s monotonic -> expired!
        assertFalse(snapAt299.isWithinFreeWindowAt(clock.timePoint()))
        clock.shiftWallClockOnly(3_600_000L)

        // Prior debt morning has 0ms free window from the first millisecond
        val debtClock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val debtLog = EventLogBuilder(debtClock)
        debtLog.emit(MorningEvent.AlarmFired)
        val debtDerived = MorningDerivation.evaluate(makeHeader(hadPriorDebt = true), debtLog.records, debtClock.timePoint())
        assertFalse(debtDerived.isWithinFreeWindow)
        assertFalse(debtDerived.isWithinFreeWindowAt(debtClock.timePoint()))

        // Grace night stays in free window past 5 minutes
        val graceClock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val graceLog = EventLogBuilder(graceClock)
        graceLog.emit(MorningEvent.AlarmFired)
        val graceDerived = MorningDerivation.evaluate(makeHeader(isGraceNight = true), graceLog.records, graceClock.timePoint())
        graceClock.advanceMinutes(10)
        assertTrue(graceDerived.isWithinFreeWindowAt(graceClock.timePoint()))
    }

    @Test
    fun `historical morning preserves its snapshotted rate and caps when constants change`() {
        val clock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val log = EventLogBuilder(clock)
        val oldRateHeader = MorningHeader(
            morningId = "2026-10-15",
            calendarDate = LocalDate.of(2026, 10, 15),
            scheduledAt = baseAlarmInstant,
            isTrialDay = false,
            isGraceNight = false,
            hadPriorDebt = false,
            priorMonthChargedPaise = Paise.ZERO,
            ratePaisePerMin = 1_000L, // historical ₹10/min
            morningCapPaise = Paise.fromRupees(300),
            monthlyCapPaise = Paise.fromRupees(3_000),
        )

        log.emit(MorningEvent.AlarmFired)
        clock.advanceMinutes(15) // 5m free + 10m billed -> ₹100 at historical ₹10/min (NOT ₹200!)
        log.emit(MorningEvent.BrushCompleted)

        val derived = MorningDerivation.evaluate(oldRateHeader, log.records, clock.timePoint())
        assertEquals(Paise.fromRupees(100), derived.morningChargedPaise)
        assertEquals(Paise.fromRupees(300), derived.effectiveCapPaise)

        // Even at 40m (35m billed), old cap of ₹300 holds (NOT ₹600!)
        val cappedClock = FakeObolClock(initialInstant = baseAlarmInstant, initialZone = zone)
        val cappedLog = EventLogBuilder(cappedClock)
        cappedLog.emit(MorningEvent.AlarmFired)
        cappedClock.advanceMinutes(40)
        val capped = MorningDerivation.evaluate(oldRateHeader, cappedLog.records, cappedClock.timePoint())
        assertEquals(MorningPhase.CAPPED_RINGING, capped.phase)
        assertEquals(Paise.fromRupees(300), capped.displayPaise)
    }
}
