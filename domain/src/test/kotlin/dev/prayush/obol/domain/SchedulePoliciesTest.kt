package dev.prayush.obol.domain

import dev.prayush.obol.domain.policy.AlarmSchedulePolicy
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.policy.GraceNightPolicy
import dev.prayush.obol.domain.policy.PauseState
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.policy.PaymentDestinationState
import dev.prayush.obol.domain.time.FakeObolClock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchedulePoliciesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    // Tue 17 Nov 2026 22:47 IST = 17:17 UTC
    private val tueNight = Instant.parse("2026-11-17T17:17:00Z")

    @Test
    fun `all alarm edits earlier, later, on, or off apply immediately without 24h lock`() {
        val clock = FakeObolClock(initialInstant = tueNight, initialZone = zone)
        val slots = AlarmSchedulePolicy.defaultSlots()
        val wedSlot = slots.first { it.dayOfWeek == DayOfWeek.WEDNESDAY }
        val sunSlot = slots.first { it.dayOfWeek == DayOfWeek.SUNDAY }

        // Moving Wednesday earlier (06:10 -> 05:45) applies immediately
        val earlier = AlarmSchedulePolicy.applyEdit(
            slot = wedSlot,
            desired = AlarmSlotConfig(enabled = true, time = LocalTime.of(5, 45)),
            point = clock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        assertEquals(LocalTime.of(5, 45), earlier.active.time)
        assertNull(earlier.pending)

        // Enabling disabled Sunday slot applies immediately
        val enabledSun = AlarmSchedulePolicy.applyEdit(
            slot = sunSlot,
            desired = AlarmSlotConfig(enabled = true, time = LocalTime.of(9, 0)),
            point = clock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        assertTrue(enabledSun.active.enabled)
        assertNull(enabledSun.pending)

        // Moving Wednesday later (06:10 -> 07:00) applies immediately with no pending lock
        val later = AlarmSchedulePolicy.applyEdit(
            slot = wedSlot,
            desired = AlarmSlotConfig(enabled = true, time = LocalTime.of(7, 0)),
            point = clock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        assertEquals(LocalTime.of(7, 0), later.active.time)
        assertNull(later.pending)

        // Tomorrow morning immediately shifts to Wed 07:00
        val updatedSlots = slots.map { if (it.dayOfWeek == DayOfWeek.WEDNESDAY) later else it }
        val next = AlarmSchedulePolicy.nextScheduledAlarm(updatedSlots, clock.timePoint(), zone)
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 11, 18), next!!.calendarDate)
        assertEquals(LocalTime.of(7, 0), next.localTime)

        // Disabling Wednesday applies immediately -> nextScheduledAlarm advances to Thursday 06:10
        val disabledWed = AlarmSchedulePolicy.applyEdit(
            slot = later,
            desired = AlarmSlotConfig(enabled = false, time = LocalTime.of(7, 0)),
            point = clock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        assertFalse(disabledWed.active.enabled)
        assertNull(disabledWed.pending)
        val slotsWithoutWed = slots.map { if (it.dayOfWeek == DayOfWeek.WEDNESDAY) disabledWed else it }
        val nextAfterOff = AlarmSchedulePolicy.nextScheduledAlarm(slotsWithoutWed, clock.timePoint(), zone)!!
        assertEquals(LocalDate.of(2026, 11, 19), nextAfterOff.calendarDate)
        assertEquals(DayOfWeek.THURSDAY, nextAfterOff.dayOfWeek)
    }

    @Test
    fun `same-day alarm edit applies immediately and pre-existing pending lock matures cleanly`() {
        // Sun 22 Nov 2026 21:53 IST = 16:23 UTC
        val sunNight = Instant.parse("2026-11-22T16:23:00Z")
        val clock = FakeObolClock(initialInstant = sunNight, initialZone = zone)
        var slots = AlarmSchedulePolicy.defaultSlots()

        val sunDefault = slots.first { it.dayOfWeek == DayOfWeek.SUNDAY }
        val sunTonight = AlarmSchedulePolicy.applyEdit(
            slot = sunDefault,
            desired = AlarmSlotConfig(enabled = true, time = LocalTime.of(21, 55)),
            point = clock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        assertTrue(sunTonight.active.enabled)
        assertEquals(LocalTime.of(21, 55), sunTonight.active.time)
        assertNull(sunTonight.pending)

        slots = slots.map { if (it.dayOfWeek == DayOfWeek.SUNDAY) sunTonight else it }
        val next = AlarmSchedulePolicy.nextScheduledAlarm(slots, clock.timePoint(), zone)
        assertNotNull(next)
        assertEquals(LocalDate.of(2026, 11, 22), next!!.calendarDate)
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(LocalTime.of(21, 55), next.localTime)

        // Changing today's upcoming 06:10 alarm at 05:30 to 07:45 applies immediately
        val wed530am = Instant.parse("2026-11-18T00:00:00Z") // Wed 05:30 IST
        val amClock = FakeObolClock(initialInstant = wed530am, initialZone = zone)
        val wedSlot = AlarmSchedulePolicy.defaultSlots().first { it.dayOfWeek == DayOfWeek.WEDNESDAY }
        val movedLaterSameMorning = AlarmSchedulePolicy.applyEdit(
            slot = wedSlot,
            desired = AlarmSlotConfig(enabled = true, time = LocalTime.of(7, 45)),
            point = amClock.timePoint(),
            isOnboarding = false,
            zone = zone,
        )
        assertEquals(LocalTime.of(7, 45), movedLaterSameMorning.active.time)
        assertNull(movedLaterSameMorning.pending)
        val slotsAfterMove = AlarmSchedulePolicy.defaultSlots().map {
            if (it.dayOfWeek == DayOfWeek.WEDNESDAY) movedLaterSameMorning else it
        }
        val nextToday = AlarmSchedulePolicy.nextScheduledAlarm(slotsAfterMove, amClock.timePoint(), zone)!!
        assertEquals(LocalDate.of(2026, 11, 18), nextToday.calendarDate)
        assertEquals(LocalTime.of(7, 45), nextToday.localTime)

        // Legacy pending lock from an older version still matures without double-ringing or bogus missed alarms
        val tue700am = Instant.parse("2026-11-17T01:30:00Z") // Tue 07:00 IST
        val lockClock = FakeObolClock(initialInstant = tue700am, initialZone = zone)
        val wedLegacyLocked = wedSlot.copy(
            pending = dev.prayush.obol.domain.policy.PendingLock.create(
                value = AlarmSlotConfig(enabled = true, time = LocalTime.of(7, 30)),
                point = lockClock.timePoint(),
                delayHours = 24L,
            ),
        )
        val fullDelayedSlots = AlarmSchedulePolicy.defaultSlots().map {
            if (it.dayOfWeek == DayOfWeek.WEDNESDAY) wedLegacyLocked else it
        }
        val wed610Instant = Instant.parse("2026-11-18T00:40:00Z") // Wed 06:10 IST

        lockClock.advanceByMs(Duration.between(tue700am, wed610Instant.plusSeconds(60L)).toMillis())
        val nextAt611 = AlarmSchedulePolicy.nextScheduledAlarm(
            slots = fullDelayedSlots,
            point = lockClock.timePoint(),
            zone = zone,
            alreadyHandledSchedules = setOf(wed610Instant),
        )!!
        assertEquals(LocalDate.of(2026, 11, 19), nextAt611.calendarDate)

        lockClock.advanceMinutes(169L) // Wed 09:00 IST
        val missedAt900 = AlarmSchedulePolicy.findMissedSchedules(
            slots = fullDelayedSlots,
            sinceInstant = tue700am,
            nowPoint = lockClock.timePoint(),
            zone = zone,
            pauseState = PauseState(),
            existingScheduledInstants = setOf(wed610Instant),
        )
        assertTrue(missedAt900.isEmpty())
    }

    @Test
    fun `AlarmSoundChoice serializes built-in chimes and custom system alarm URIs`() {
        val json = Json { classDiscriminator = "type"; encodeDefaults = true; ignoreUnknownKeys = true }
        for (chime in BuiltInChime.entries) {
            val choice: AlarmSoundChoice = AlarmSoundChoice.BuiltIn(chime)
            val encoded = json.encodeToString(choice)
            assertEquals(choice, json.decodeFromString<AlarmSoundChoice>(encoded))
        }
        val sys: AlarmSoundChoice = AlarmSoundChoice.SystemAlarm("content://media/internal/audio/media/42", "Bright Morning")
        val sysJson = json.encodeToString(sys)
        assertEquals(sys, json.decodeFromString<AlarmSoundChoice>(sysJson))
        assertEquals("Bright Morning", sys.label)
    }

    @Test
    fun `pause and resume both apply immediately`() {
        val clock = FakeObolClock(initialInstant = tueNight, initialZone = zone)
        var pause = PauseState().requestPause(clock.timePoint())
        assertTrue(pause.isPaused)
        assertEquals(tueNight, pause.pausedSince)
        assertNull(pause.pendingPause)
        assertNull(AlarmSchedulePolicy.nextScheduledAlarm(AlarmSchedulePolicy.defaultSlots(), clock.timePoint(), zone, pause))

        // Resume is also instant
        pause = pause.resumeNow()
        assertFalse(pause.isPaused)
        assertNull(pause.pausedSince)
        assertNotNull(AlarmSchedulePolicy.nextScheduledAlarm(AlarmSchedulePolicy.defaultSlots(), clock.timePoint(), zone, pause))
    }

    @Test
    fun `destination applies immediately from Unset then locks changes for 24h`() {
        val clock = FakeObolClock(initialInstant = tueNight, initialZone = zone)
        assertThrows(IllegalArgumentException::class.java) {
            PaymentDestination.Upi("not-a-vpa", "Charity")
        }

        val upi = PaymentDestination.Upi("donate@upi", "GiveIndia")
        var state = PaymentDestinationState().requestChange(upi, clock.timePoint())
        assertEquals(upi, state.active)
        assertNull(state.pending)

        // Subsequent change to Donorbox locks for +24h
        state = state.requestChange(PaymentDestination.DonorboxOppia, clock.timePoint())
        assertEquals(upi, state.resolvedAt(clock.timePoint()).active)
        assertNotNull(state.pending)

        clock.advanceHours(24)
        state = state.resolvedAt(clock.timePoint())
        assertEquals(PaymentDestination.DonorboxOppia, state.active)
        assertNull(state.pending)
    }

    @Test
    fun `grace night enforces 3 per month quota, 2h minimum lead time, and pre-fire undo`() {
        val clock = FakeObolClock(initialInstant = tueNight, initialZone = zone)
        val slots = AlarmSchedulePolicy.defaultSlots()
        val nextAlarm = AlarmSchedulePolicy.nextScheduledAlarm(slots, clock.timePoint(), zone)!!

        // At 22:47 (7h23m before 06:10), allowed when < 3 used
        assertTrue(GraceNightPolicy.canDeclareFor(nextAlarm.scheduledAt, clock.now(), usedInTargetMonth = 1, alreadyDeclaredForNext = false))
        assertFalse(GraceNightPolicy.canDeclareFor(nextAlarm.scheduledAt, clock.now(), usedInTargetMonth = 3, alreadyDeclaredForNext = false))
        assertFalse(GraceNightPolicy.canDeclareFor(nextAlarm.scheduledAt, clock.now(), usedInTargetMonth = 1, alreadyDeclaredForNext = true))

        // Undo is allowed when declared and strictly before scheduledAt, including inside 2h pre-alarm window
        assertFalse(GraceNightPolicy.canUndoFor(nextAlarm.scheduledAt, clock.now(), alreadyDeclaredForNext = false))
        assertTrue(GraceNightPolicy.canUndoFor(nextAlarm.scheduledAt, clock.now(), alreadyDeclaredForNext = true))
        assertTrue(GraceNightPolicy.canUndoFor(nextAlarm.scheduledAt, nextAlarm.scheduledAt.minusSeconds(30L), alreadyDeclaredForNext = true))
        assertFalse(GraceNightPolicy.canUndoFor(nextAlarm.scheduledAt, nextAlarm.scheduledAt, alreadyDeclaredForNext = true))
        assertFalse(GraceNightPolicy.canUndoFor(null, clock.now(), alreadyDeclaredForNext = true))

        // 1h59m59s before 06:10 -> declare rejected; exact 2h -> declare allowed
        val exact2hBefore = nextAlarm.scheduledAt.minusSeconds(7_200L)
        assertTrue(GraceNightPolicy.canDeclareFor(nextAlarm.scheduledAt, exact2hBefore, usedInTargetMonth = 0, alreadyDeclaredForNext = false))
        assertFalse(GraceNightPolicy.canDeclareFor(nextAlarm.scheduledAt, exact2hBefore.plusSeconds(1L), usedInTargetMonth = 0, alreadyDeclaredForNext = false))
    }

    @Test
    fun `missed-schedule finder detects unfired alarms while ignoring paused windows`() {
        // Advance past Wed 06:10 to Wed 09:00 without firing -> missed alarm detected
        val clock = FakeObolClock(initialInstant = Instant.parse("2026-11-18T03:30:00Z"), initialZone = zone)
        val missed = AlarmSchedulePolicy.findMissedSchedules(
            slots = AlarmSchedulePolicy.defaultSlots(),
            sinceInstant = tueNight,
            nowPoint = clock.timePoint(),
            zone = zone,
            pauseState = PauseState(),
            existingScheduledInstants = emptySet(),
        )
        assertEquals(1, missed.size)
        assertEquals(LocalDate.of(2026, 11, 18), missed[0].calendarDate)

        // If Pause activated at Wed 08:00 IST (after Wed 06:10 missed, before Thu 06:10),
        // evaluating on Thu 09:00 catches Wed 06:10 while ignoring paused Thu 06:10!
        val wed8am = Instant.parse("2026-11-18T02:30:00Z")
        val thu9amClock = FakeObolClock(initialInstant = Instant.parse("2026-11-19T03:30:00Z"), initialZone = zone)
        val activePauseSinceWed8 = PauseState(isPaused = true, pausedSince = wed8am)
        val missedAcrossPause = AlarmSchedulePolicy.findMissedSchedules(
            slots = AlarmSchedulePolicy.defaultSlots(),
            sinceInstant = tueNight,
            nowPoint = thu9amClock.timePoint(),
            zone = zone,
            pauseState = activePauseSinceWed8,
            existingScheduledInstants = emptySet(),
        )
        assertEquals(1, missedAcrossPause.size)
        assertEquals(LocalDate.of(2026, 11, 18), missedAcrossPause[0].calendarDate)
    }
}
