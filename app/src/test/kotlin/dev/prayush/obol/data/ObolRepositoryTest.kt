package dev.prayush.obol.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.time.FakeObolClock
import dev.prayush.obol.payment.PaymentController
import dev.prayush.obol.payment.UpiParseResult
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObolRepositoryTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    // Mon 09 Nov 2026 22:00 IST (past 7-day trial by Nov 17/18)
    private val postTrialSetupInstant = Instant.parse("2026-11-09T16:30:00Z")

    private lateinit var db: ObolDatabase
    private lateinit var prefsScope: CoroutineScope
    private lateinit var prefsFile: File
    private lateinit var preferences: ObolPreferences
    private lateinit var clock: FakeObolClock
    private lateinit var repository: ObolRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
            .createDeviceProtectedStorageContext()
        assertTrue(context.isDeviceProtectedStorage)
        db = ObolDatabase.create(context, inMemory = true)
        prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        prefsFile = File(context.cacheDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        preferences = ObolPreferences.createForFile(prefsFile, prefsScope)
        clock = FakeObolClock(
            initialInstant = Instant.parse("2026-11-17T00:40:00Z"), // Tue 17 Nov 06:10 IST
            initialZone = zone,
        )
        repository = ObolRepository(
            dao = db.obolDao(),
            preferences = preferences,
            clock = clock,
            scope = prefsScope,
        )
        runBlocking {
            repository.ensureDefaultScheduleSeeded()
            preferences.markSetupCompleted(postTrialSetupInstant)
        }
    }

    @After
    fun tearDown() {
        db.close()
        prefsScope.cancel()
        prefsFile.delete()
    }

    @Test
    fun `missed alarm reconciles to 24-not-rung Cancelled with zero charge`() = runBlocking {
        val tueNight = Instant.parse("2026-11-17T17:17:00Z")
        preferences.updateLastReconciledAt(tueNight)

        clock.reboot(downtimeMs = 0L)
        val wedMorningLate = Instant.parse("2026-11-18T03:00:00Z")
        clock.setInstantPreservingBoot(wedMorningLate, 36_000_000L)

        val result = repository.reconcile()
        assertFalse(result.needsImmediateAlarmResume)
        assertNotNull(result.activeMorning)
        assertEquals(MorningPhase.CANCELLED, result.activeMorning!!.phase)
        assertEquals(Paise.ZERO, result.activeMorning!!.morningChargedPaise)
    }

    @Test
    fun `post-fire reboot closes dangling BrushingPausedMeter at start and flags immediate resume`() = runBlocking {
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        preferences.updateLastReconciledAt(wedAlarm.minusSeconds(3_600L))

        val fired = repository.onAlarmFired(wedAlarm)
        assertNotNull(fired)
        assertEquals(MorningPhase.FREE_WINDOW, fired!!.phase)

        clock.advanceMinutes(10)
        repository.appendActiveEvent(MorningEvent.CameraOpened)
        repository.appendActiveEvent(MorningEvent.BrushingPausedMeter)
        assertTrue(repository.computeLiveSnapshot().activeMorning!!.isBrushingSeen)

        clock.reboot(downtimeMs = 2 * 60_000L)
        val result = repository.reconcile()

        assertTrue(result.needsImmediateAlarmResume)
        assertNotNull(result.activeMorning)
        assertEquals(MorningPhase.METERING, result.activeMorning!!.phase)
        assertEquals(Paise.fromRupees(140), result.activeMorning!!.displayPaise)
    }

    @Test
    fun `markPaidThrough settles multi-morning debt atomically and closes morning right after payment`() = runBlocking {
        // Morning 1 (Tue 17 Nov 06:10 IST): accrues ₹300 (5m free + 15m at ₹20/min), user taps Later -> closes with ₹300 debt
        val tueAlarm = Instant.parse("2026-11-17T00:40:00Z")
        preferences.updateLastReconciledAt(tueAlarm.minusSeconds(3_600L))
        repository.onAlarmFired(tueAlarm)
        clock.advanceMinutes(20) // 5m free + 15m billed = ₹300
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.appendActiveEvent(MorningEvent.PaymentDeferred)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        val afterDay1 = repository.computeLiveSnapshot()
        assertEquals(Paise.fromRupees(300), afterDay1.totalUnpaidPaise)
        assertNull(afterDay1.activeMorning)

        // Morning 2 (Wed 18 Nov 06:10 IST): fires with hadPriorDebt = true (0 free window), accrues ₹100
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        val deltaToWed = wedAlarm.toEpochMilli() - clock.now().toEpochMilli()
        clock.advanceByMs(deltaToWed)

        val day2Fired = repository.onAlarmFired(wedAlarm)!!
        assertTrue(day2Fired.header.hadPriorDebt)

        clock.advanceMinutes(5) // 5m billed at ₹20/min = ₹100
        val day2PostBrush = repository.appendActiveEvent(MorningEvent.BrushCompleted)!!
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, day2PostBrush.phase)
        assertEquals(Paise.fromRupees(100), day2PostBrush.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(400), day2PostBrush.totalPayableNowPaise)

        // Settle combined ₹400 via markPaidThrough -> closes directly into CLOSED_DONE!
        val settled = repository.markPaidThrough(
            expectedTotalPaise = Paise.fromRupees(400),
            railId = "upi",
            ref = "upi-tx-400",
        )
        assertEquals(Paise.fromRupees(400), settled)

        val finalSnap = repository.computeLiveSnapshot()
        assertEquals(Paise.ZERO, finalSnap.totalUnpaidPaise)
        assertEquals(2, finalSnap.mornings.size)
        assertEquals(Paise.ZERO, finalSnap.mornings[0].morningUnpaidPaise)
        assertEquals(Paise.ZERO, finalSnap.mornings[1].morningUnpaidPaise)
        assertEquals(MorningPhase.CLOSED_DONE, finalSnap.mornings[1].phase)
    }

    @Test
    fun `running tab below 300 keeps tomorrow free window and opens combined checkout once tab hits 300`() = runBlocking {
        // Day 1 (Tue 17 Nov): 5m free + 8m billed at ₹20/min = ₹160 (< ₹300 threshold)
        val tueAlarm = Instant.parse("2026-11-17T00:40:00Z")
        preferences.updateLastReconciledAt(tueAlarm.minusSeconds(3_600L))
        repository.onAlarmFired(tueAlarm)
        clock.advanceMinutes(13) // ₹160
        val day1AfterBrush = repository.appendActiveEvent(MorningEvent.BrushCompleted)!!
        assertEquals(MorningPhase.CLOSED_DONE, day1AfterBrush.phase)
        assertEquals(Paise.fromRupees(160), day1AfterBrush.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(160), day1AfterBrush.totalPayableNowPaise)

        repository.appendActiveEvent(MorningEvent.MorningDismissed)
        val snapAfterDay1 = repository.computeLiveSnapshot()
        assertEquals(Paise.fromRupees(160), snapAfterDay1.totalUnpaidPaise)
        val homeTab = dev.prayush.obol.ui.ObolCopy.resolveScreen(
            snapshot = snapAfterDay1,
            overlay = dev.prayush.obol.ui.CalmOverlay.None,
            permissions = dev.prayush.obol.ui.PermissionGrantState(),
            vision = dev.prayush.obol.ui.LiveVisionUiState(),
        ) as dev.prayush.obol.ui.ObolScreenModel.Home
        assertEquals("₹160", homeTab.unpaidAmountText)
        assertEquals(" on tab", homeTab.unpaidSuffix)
        assertNull("Below-threshold tab must not expose Pay button", homeTab.primaryButton)

        // Day 2 (Wed 18 Nov): tab ₹160 < ₹300, so Day 2 KEEPS its 5-minute free window!
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.advanceByMs(wedAlarm.toEpochMilli() - clock.now().toEpochMilli())
        val day2Fired = repository.onAlarmFired(wedAlarm)!!
        assertFalse("Sub-300 running tab must not strip tomorrow's free window", day2Fired.header.hadPriorDebt)
        assertEquals(MorningPhase.FREE_WINDOW, day2Fired.phase)

        // Stay 5m free + 7m billed = ₹140 -> combined tab = ₹300 -> checkout triggers!
        clock.advanceMinutes(12)
        val day2PostBrush = repository.appendActiveEvent(MorningEvent.BrushCompleted)!!
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, day2PostBrush.phase)
        assertEquals(Paise.fromRupees(140), day2PostBrush.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(300), day2PostBrush.totalPayableNowPaise)

        // Deferring once >= ₹300 triggers collateral on Home and strips Day 3's free window
        repository.appendActiveEvent(MorningEvent.PaymentDeferred)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)
        val snapAfterDefer = repository.computeLiveSnapshot()
        val homeDue = dev.prayush.obol.ui.ObolCopy.resolveScreen(
            snapshot = snapAfterDefer,
            overlay = dev.prayush.obol.ui.CalmOverlay.None,
            permissions = dev.prayush.obol.ui.PermissionGrantState(),
            vision = dev.prayush.obol.ui.LiveVisionUiState(),
        ) as dev.prayush.obol.ui.ObolScreenModel.Home
        assertEquals("₹300", homeDue.unpaidAmountText)
        assertEquals(" unpaid", homeDue.unpaidSuffix)
        assertEquals("Pay ₹300", homeDue.primaryButton)

        // Day 3 (Thu 19 Nov): prior unpaid ₹300 >= ₹300 -> hadPriorDebt = true (0 free window)!
        val thuAlarm = Instant.parse("2026-11-19T00:40:00Z")
        clock.advanceByMs(thuAlarm.toEpochMilli() - clock.now().toEpochMilli())
        val day3Fired = repository.onAlarmFired(thuAlarm)!!
        assertTrue("Deferred >= ₹300 debt must strip free window", day3Fired.header.hadPriorDebt)
        assertEquals(MorningPhase.METERING, day3Fired.phase)

        // Paying the ₹300 clears both Day 1 and Day 2 atomically
        val paid = repository.markPaidThrough(Paise.fromRupees(300), "donorbox_oppia", "db-tab-300")
        assertEquals(Paise.fromRupees(300), paid)
        val settledSnap = repository.computeLiveSnapshot()
        assertEquals(Paise.ZERO, settledSnap.mornings[0].morningUnpaidPaise)
        assertEquals(Paise.ZERO, settledSnap.mornings[1].morningUnpaidPaise)
    }

    @Test
    fun `lingering POST_CHECK_PAY_OR_ZERO does not restart alarm on reboot and auto-defers when next alarm fires`() = runBlocking {
        val tueAlarm = Instant.parse("2026-11-17T00:40:00Z")
        preferences.updateLastReconciledAt(tueAlarm.minusSeconds(3_600L))
        repository.onAlarmFired(tueAlarm)
        clock.advanceMinutes(20) // 5m free + 15m billed at ₹20/min = ₹300 -> POST_CHECK_PAY_OR_ZERO
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, repository.computeLiveSnapshot().activeMorning!!.phase)

        // Reboot while sitting on 17-pay -> must NOT request alarm ringing resume!
        clock.reboot(downtimeMs = 10 * 60_000L)
        val bootRes = repository.reconcile(isProcessOrBootRestart = true)
        assertFalse("Post-brush settle screen must not restart ringing service on reboot", bootRes.needsImmediateAlarmResume)

        // Next morning's alarm fires while user never tapped Later -> auto-closes Day 1 and fires Day 2 with prior debt!
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.advanceByMs(wedAlarm.toEpochMilli() - clock.now().toEpochMilli())
        val day2 = repository.onAlarmFired(wedAlarm)!!
        assertEquals("2026-11-18", day2.header.morningId)
        assertTrue("Unsettled Day 1 debt must carry into Day 2", day2.header.hadPriorDebt)
        assertEquals(MorningPhase.METERING, day2.phase)
    }

    @Test
    fun `Sunday night tonight-alarm edit updates nextAlarm immediately and AlarmSoundChoice persists`() = runBlocking {
        // Sun 22 Nov 2026 21:53 IST = 16:23 UTC
        val sunNight = Instant.parse("2026-11-22T16:23:00Z")
        clock.setInstantPreservingBoot(sunNight, 50_000L)

        // Initially Sunday is Off -> nextAlarm is Tomorrow Monday 06:10
        val before = repository.computeLiveSnapshot()
        assertEquals(DayOfWeek.MONDAY, before.nextAlarm!!.dayOfWeek)
        assertEquals(AlarmSoundChoice.Default, before.alarmSound)

        // User toggles Sunday ON (08:00) then picks 21:55 tonight
        repository.updateAlarmSlot(DayOfWeek.SUNDAY, AlarmSlotConfig(enabled = true, time = LocalTime.of(8, 0)))
        repository.updateAlarmSlot(DayOfWeek.SUNDAY, AlarmSlotConfig(enabled = true, time = LocalTime.of(21, 55)))
        val customSys = AlarmSoundChoice.SystemAlarm("content://media/internal/audio/media/77", "Bright Morning")
        repository.updateAlarmSound(customSys)
        repository.updateAlarmSound(AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL))

        val after = repository.computeLiveSnapshot()
        assertEquals(LocalDate.of(2026, 11, 22), after.nextAlarm!!.calendarDate)
        assertEquals(DayOfWeek.SUNDAY, after.nextAlarm!!.dayOfWeek)
        assertEquals(LocalTime.of(21, 55), after.nextAlarm!!.localTime)
        assertEquals(AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL), after.alarmSound)
        assertEquals("Previously picked system alarm must remain available for comparison", customSys, after.lastSystemAlarm)
    }

    @Test
    fun `delaying an alarm updates nextAlarm immediately and never creates bogus missed alarms`() = runBlocking {
        // Sun 22 Nov 08:00 IST = 02:30 UTC: user moves Monday 06:10 -> 07:30 (applies immediately!)
        val sun8am = Instant.parse("2026-11-22T02:30:00Z")
        clock.setInstantPreservingBoot(sun8am, 10_000L)
        preferences.updateLastReconciledAt(sun8am)
        repository.updateAlarmSlot(DayOfWeek.MONDAY, AlarmSlotConfig(enabled = true, time = LocalTime.of(7, 30)))

        val afterEdit = repository.computeLiveSnapshot()
        assertEquals(DayOfWeek.MONDAY, afterEdit.nextAlarm!!.dayOfWeek)
        assertEquals(LocalTime.of(7, 30), afterEdit.nextAlarm!!.localTime)
        assertNull(afterEdit.alarmSlots.first { it.dayOfWeek == DayOfWeek.MONDAY }.pending)

        // Mon 23 Nov 07:30 IST = 02:00 UTC: Monday 07:30 fires and user completes 10s brushing inside free window
        val mon730 = Instant.parse("2026-11-23T02:00:00Z")
        clock.advanceByMs(mon730.toEpochMilli() - clock.now().toEpochMilli())
        repository.onAlarmFired(mon730)
        clock.advanceSeconds(15L)
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        // Advance to Mon 09:00 IST -> reconcile() must emit 0 bogus missed alarms and arm Tuesday 06:10
        clock.advanceMinutes(90L)
        val mon9amRes = repository.reconcile(isProcessOrBootRestart = false)
        assertNull(mon9amRes.activeMorning)
        val snapMon9am = repository.computeLiveSnapshot()
        assertEquals(1, snapMon9am.mornings.size)
        assertEquals(DayOfWeek.TUESDAY, snapMon9am.nextAlarm!!.dayOfWeek)
    }

    @Test
    fun `UPI pending submission on zero-today morning with prior debt closes morning and preserves Home banner`() = runBlocking {
        // Day 1 accrues ₹300 debt (5m free + 15m at ₹20/min)
        val tueAlarm = Instant.parse("2026-11-17T00:40:00Z")
        preferences.updateLastReconciledAt(tueAlarm.minusSeconds(3_600L))
        repository.onAlarmFired(tueAlarm)
        clock.advanceMinutes(20) // ₹300
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.appendActiveEvent(MorningEvent.PaymentDeferred)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        // Day 2: user wakes in 2s (<3s -> ₹0 today, ₹300 from earlier) and submits UPI for ₹300
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.advanceByMs(wedAlarm.toEpochMilli() - clock.now().toEpochMilli())
        repository.onAlarmFired(wedAlarm)
        clock.advanceSeconds(2L)
        repository.appendActiveEvent(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(10L)
        val postBrush = repository.appendActiveEvent(MorningEvent.BrushCompleted)!!
        assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, postBrush.phase)
        assertEquals(Paise.ZERO, postBrush.morningUnpaidPaise)
        assertEquals(Paise.fromRupees(300), postBrush.totalPayableNowPaise)

        repository.recordPendingSubmission(Paise.fromRupees(300), "TX-PRIOR-300")
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        val snap = repository.computeLiveSnapshot()
        assertNull(snap.activeMorning)
        assertNotNull("Pending UPI banner for prior debt must remain visible on Home", snap.pendingUpiSubmission)
        assertEquals(Paise.fromRupees(300), snap.pendingUpiSubmission!!.amountPaise)
    }

    @Test
    fun `PaymentController builds UPI and Donorbox URIs without float math and parses UPI status case-insensitively`() {
        val upiDest = PaymentDestination.Upi(vpa = "giveindia@axis", payeeName = "GiveIndia")
        val upiUri = PaymentController.buildUpiPayUri(upiDest, Paise.fromRupees(130), "2026-11-18")
        assertEquals("upi", upiUri.scheme)
        assertEquals("pay", upiUri.authority)
        assertEquals("giveindia@axis", upiUri.getQueryParameter("pa"))
        assertEquals("130.00", upiUri.getQueryParameter("am"))
        assertEquals("INR", upiUri.getQueryParameter("cu"))
        assertEquals("2026-11-18", upiUri.getQueryParameter("tr"))

        val donorboxUri = PaymentController.buildDonorboxUri(Paise.fromRupees(100))
        assertEquals("100", donorboxUri.getQueryParameter("amount"))
        assertEquals("inr", donorboxUri.getQueryParameter("currency"))
        assertEquals("o", donorboxUri.getQueryParameter("default_interval"))

        val okIntent = Intent().putExtra("response", "txnId=TX99&Status=Success&ApprovalRefNo=APR777")
        val okRes = PaymentController.parseUpiResult(Activity.RESULT_OK, okIntent)
        assertEquals(UpiParseResult.Success("APR777"), okRes)

        val subIntent = Intent().putExtra("response", "txnId=TX88&status=submitted")
        val subRes = PaymentController.parseUpiResult(Activity.RESULT_OK, subIntent)
        assertEquals(UpiParseResult.Submitted("TX88"), subRes)

        val failIntent = Intent().putExtra("Status", "FAILURE")
        val failRes = PaymentController.parseUpiResult(Activity.RESULT_OK, failIntent)
        assertTrue(failRes is UpiParseResult.Failed)

        val cancelRes = PaymentController.parseUpiResult(Activity.RESULT_CANCELED, null)
        assertTrue(cancelRes is UpiParseResult.Failed)
    }

    @Test
    fun `submitted UPI followed by second unpaid morning settles only submitted amount and preserves new debt`() = runBlocking {
        val tueAlarm = Instant.parse("2026-11-17T00:40:00Z")
        preferences.updateLastReconciledAt(tueAlarm.minusSeconds(3_600L))
        repository.onAlarmFired(tueAlarm)
        clock.advanceMinutes(20) // ₹300 (5m free + 15m billed at ₹20/min)
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.recordPendingSubmission(Paise.fromRupees(300), "TX-M1")
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.advanceByMs(wedAlarm.toEpochMilli() - clock.now().toEpochMilli())
        repository.onAlarmFired(wedAlarm)
        clock.advanceMinutes(5) // ₹100 (hadPriorDebt = true -> 0 free window)
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.appendActiveEvent(MorningEvent.PaymentDeferred)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        val homeSnap = repository.computeLiveSnapshot()
        assertEquals(Paise.fromRupees(400), homeSnap.totalUnpaidPaise)
        assertNotNull(homeSnap.pendingUpiSubmission)
        assertEquals(Paise.fromRupees(300), homeSnap.pendingUpiSubmission!!.amountPaise)

        repository.markPaidThrough(homeSnap.pendingUpiSubmission!!.amountPaise, "upi", homeSnap.pendingUpiSubmission!!.ref)
        val afterConfirm = repository.computeLiveSnapshot()
        assertEquals(Paise.fromRupees(100), afterConfirm.totalUnpaidPaise)
        assertEquals(Paise.ZERO, afterConfirm.mornings[0].morningUnpaidPaise)
        assertEquals(Paise.fromRupees(100), afterConfirm.mornings[1].morningUnpaidPaise)
        assertNull(afterConfirm.pendingUpiSubmission)
    }

    @Test
    fun `camera-open reboot closes camera to metering and resuming pause skips paused schedules`() = runBlocking {
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        preferences.updateLastReconciledAt(wedAlarm.minusSeconds(3_600L))
        repository.onAlarmFired(wedAlarm)
        clock.advanceMinutes(8) // 3m billed at ₹20/min = ₹60
        repository.appendActiveEvent(MorningEvent.CameraOpened)
        assertEquals(MorningPhase.BRUSHING, repository.computeLiveSnapshot().activeMorning!!.phase)

        clock.reboot(downtimeMs = 2 * 60_000L)
        val bootRes = repository.reconcile(isProcessOrBootRestart = true)
        assertEquals(MorningPhase.METERING, bootRes.activeMorning!!.phase)
        assertEquals(Paise.fromRupees(100), bootRes.activeMorning!!.displayPaise)

        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.markPaidThrough(Paise.fromRupees(100), "upi", "ok")
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        repository.setPauseRequested(true)
        clock.reboot(downtimeMs = 72 * 3_600_000L)
        repository.setPauseRequested(false)
        val postResume = repository.reconcile(isProcessOrBootRestart = false)
        assertEquals(1, repository.computeLiveSnapshot().mornings.size)
        assertNull(postResume.activeMorning)
    }

    @Test
    fun `multiple missed mornings surface only latest 24-not-rung summary once and never resurrect after newer morning`() = runBlocking {
        val monNight = Instant.parse("2026-11-16T17:17:00Z")
        preferences.updateLastReconciledAt(monNight)
        val thuLate = Instant.parse("2026-11-19T03:30:00Z")
        clock.reboot(downtimeMs = 0L)
        clock.setInstantPreservingBoot(thuLate, 50_000L)

        val res = repository.reconcile(isProcessOrBootRestart = true)
        val snapAfterMiss = repository.computeLiveSnapshot()
        assertEquals(3, snapAfterMiss.mornings.size)
        assertEquals(MorningPhase.CANCELLED, res.activeMorning!!.phase)
        assertEquals("2026-11-19", res.activeMorning!!.header.morningId)

        repository.appendActiveEvent(MorningEvent.MorningDismissed)
        assertNull(repository.computeLiveSnapshot().activeMorning)
    }

    @Test
    fun `day 1 immediately after onboarding counts real money with zero trial week`() = runBlocking {
        // Fresh onboarding completed at Tue 17 Nov 2026 05:30 IST (40m before the very first 06:10 alarm!)
        val freshFile = File(
            ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext().cacheDir,
            "fresh_day1_${System.nanoTime()}.preferences_pb",
        )
        val freshPrefs = ObolPreferences.createForFile(freshFile, prefsScope)
        val freshDb = ObolDatabase.create(
            ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext(),
            inMemory = true,
        )
        val setupTime = Instant.parse("2026-11-17T00:00:00Z") // Tue 05:30 IST
        val day1Clock = FakeObolClock(initialInstant = setupTime, initialZone = zone)
        val freshRepo = ObolRepository(
            dao = freshDb.obolDao(),
            preferences = freshPrefs,
            clock = day1Clock,
            scope = prefsScope,
        )
        try {
            freshRepo.acceptWelcome()
            freshRepo.acceptPermissions()
            freshRepo.finishSetup()

            // First morning fires 40 minutes after setup (Day 1!)
            val firstAlarm = Instant.parse("2026-11-17T00:40:00Z") // Tue 06:10 IST
            day1Clock.advanceMinutes(40)
            val fired = freshRepo.onAlarmFired(firstAlarm)!!
            assertFalse("isTrialDay must never be set on new mornings", fired.header.isTrialDay)

            // Snooze across free window -> 15m billed at ₹20/min = ₹300 real money (not pretend!)
            day1Clock.advanceMinutes(20)
            val metering = freshRepo.computeLiveSnapshot().activeMorning!!
            assertEquals(MorningPhase.METERING, metering.phase)
            assertEquals(Paise.fromRupees(300), metering.displayPaise)
            assertEquals(Paise.fromRupees(300), metering.morningChargedPaise)
            assertEquals(Paise.fromRupees(300), metering.morningUnpaidPaise)

            // Finish 10s brushing -> requires real payment on POST_CHECK_PAY_OR_ZERO (`17-pay`)
            val afterBrush = freshRepo.appendActiveEvent(MorningEvent.BrushCompleted)!!
            assertEquals(MorningPhase.POST_CHECK_PAY_OR_ZERO, afterBrush.phase)
            assertEquals(Paise.fromRupees(300), afterBrush.totalPayableNowPaise)

            // Deferring payment carries real ₹300 debt to Home
            freshRepo.appendActiveEvent(MorningEvent.PaymentDeferred)
            freshRepo.appendActiveEvent(MorningEvent.MorningDismissed)
            val homeSnap = freshRepo.computeLiveSnapshot()
            assertEquals(Paise.fromRupees(300), homeSnap.totalUnpaidPaise)
            assertEquals(Paise.fromRupees(300), homeSnap.currentMonthChargedPaise)

            // Also verify ₹20 typed override inside free window on Day 2 counts as real money (goes on tab < ₹300)
            val settled = freshRepo.markPaidThrough(Paise.fromRupees(300), "donorbox_oppia", "db-1")
            assertEquals(Paise.fromRupees(300), settled)
            val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
            day1Clock.advanceByMs(wedAlarm.toEpochMilli() - day1Clock.now().toEpochMilli())
            freshRepo.onAlarmFired(wedAlarm)
            day1Clock.advanceMinutes(2) // still inside 5m free window, but user accepts ₹20 override
            val afterOverride = freshRepo.appendActiveEvent(MorningEvent.OverrideAccepted())!!
            assertEquals(MorningPhase.CLOSED_DONE, afterOverride.phase)
            assertEquals(Paise.fromRupees(20), afterOverride.morningChargedPaise)
            assertEquals(Paise.fromRupees(20), afterOverride.totalPayableNowPaise)
        } finally {
            freshDb.close()
            freshFile.delete()
        }
    }

    @Test
    fun `declaring and undoing grace night restores quota and re-arms real money stakes`() = runBlocking {
        // Tue 17 Nov 2026 22:00 IST (8h10m before Wed 18 Nov 06:10 IST)
        val tueNight = Instant.parse("2026-11-17T16:30:00Z")
        clock.setInstantPreservingBoot(tueNight, 10_000L)

        val initial = repository.computeLiveSnapshot()
        assertEquals(3, initial.graceNightsRemainingThisMonth)
        assertFalse(initial.isNextAlarmGraceNight)
        assertTrue(initial.canDeclareGraceNightForNext)
        assertFalse(initial.canUndoGraceNightForNext)

        // User accidentally taps "Take a night off"
        assertTrue(repository.declareGraceNightForNext())
        val afterDeclare = repository.computeLiveSnapshot()
        assertEquals(2, afterDeclare.graceNightsRemainingThisMonth)
        assertTrue(afterDeclare.isNextAlarmGraceNight)
        assertFalse(afterDeclare.canDeclareGraceNightForNext)
        assertTrue(afterDeclare.canUndoGraceNightForNext)

        // User taps "Undo night off" -> quota refunded to 3, morning re-armed as normal billable morning
        assertTrue(repository.undoGraceNightForNext())
        val afterUndo = repository.computeLiveSnapshot()
        assertEquals(3, afterUndo.graceNightsRemainingThisMonth)
        assertFalse(afterUndo.isNextAlarmGraceNight)
        assertTrue(afterUndo.canDeclareGraceNightForNext)
        assertFalse(afterUndo.canUndoGraceNightForNext)

        // Also verify moving an alarm earlier after declaring a grace night prunes the orphaned timestamp cleanly
        assertTrue(repository.declareGraceNightForNext())
        assertEquals(2, repository.computeLiveSnapshot().graceNightsRemainingThisMonth)
        repository.updateAlarmSlot(DayOfWeek.WEDNESDAY, AlarmSlotConfig(enabled = true, time = LocalTime.of(6, 0)))
        val afterSlotMove = repository.computeLiveSnapshot()
        assertEquals("Orphaned future grace night must be refunded when slot moves earlier", 3, afterSlotMove.graceNightsRemainingThisMonth)
        assertFalse(afterSlotMove.isNextAlarmGraceNight)

        // When Wed 06:00 fires after undo, it is a real billable morning (NOT a grace night!)
        val wed6am = Instant.parse("2026-11-18T00:30:00Z")
        clock.advanceByMs(wed6am.toEpochMilli() - clock.now().toEpochMilli())
        val fired = repository.onAlarmFired(wed6am)!!
        assertFalse(fired.header.isGraceNight)
        assertEquals(MorningPhase.FREE_WINDOW, fired.phase)
    }

    @Test
    fun `instant pause cancels next alarm immediately and refunds future grace night`() = runBlocking {
        val tueNight = Instant.parse("2026-11-17T16:30:00Z") // Tue 22:00 IST
        clock.setInstantPreservingBoot(tueNight, 10_000L)

        assertTrue(repository.declareGraceNightForNext())
        assertEquals(2, repository.computeLiveSnapshot().graceNightsRemainingThisMonth)

        repository.setPauseRequested(true)
        val paused = repository.computeLiveSnapshot()
        assertTrue(paused.pauseState.isPaused)
        assertNull(paused.pauseState.pendingPause)
        assertNull("Next alarm must be cleared immediately when paused", paused.nextAlarm)
        assertEquals("Future grace night must be refunded on instant pause", 3, paused.graceNightsRemainingThisMonth)

        repository.setPauseRequested(false)
        val resumed = repository.computeLiveSnapshot()
        assertFalse(resumed.pauseState.isPaused)
        assertNotNull(resumed.nextAlarm)
    }

    @Test
    fun `history all-time view totals spend across months, formats month dates, computes wake lateness, and preserves Earlier on bill back`() = runBlocking {
        // Seed an October morning (Oct 15: 15m -> 10m billed = ₹200) and a November morning (Nov 17: 9m -> 4m billed = ₹80)
        val octAlarm = Instant.parse("2026-10-15T00:40:00Z")
        clock.setInstantPreservingBoot(octAlarm, 10_000L)
        repository.onAlarmFired(octAlarm)
        clock.advanceMinutes(15)
        repository.appendActiveEvent(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(10)
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        val novAlarm = Instant.parse("2026-11-17T00:40:00Z")
        clock.advanceByMs(novAlarm.toEpochMilli() - clock.now().toEpochMilli())
        repository.onAlarmFired(novAlarm)
        clock.advanceMinutes(9)
        repository.appendActiveEvent(MorningEvent.BrushingPausedMeter)
        clock.advanceSeconds(10)
        repository.appendActiveEvent(MorningEvent.BrushCompleted)
        repository.appendActiveEvent(MorningEvent.MorningDismissed)

        val snap = repository.computeLiveSnapshot()

        // Default current-month History view (November)
        val novHistory = dev.prayush.obol.ui.ObolCopy.resolveScreen(
            snapshot = snap,
            overlay = dev.prayush.obol.ui.CalmOverlay.History(showEarlierMonths = false),
            permissions = dev.prayush.obol.ui.PermissionGrantState(),
            vision = dev.prayush.obol.ui.LiveVisionUiState(),
        ) as dev.prayush.obol.ui.ObolScreenModel.HistoryList
        assertEquals("November", novHistory.monthTitle)
        assertEquals("₹80", novHistory.monthTotalText)
        assertEquals("Up in 9 min.", novHistory.dryLine)
        assertEquals(1, novHistory.rows.size)
        assertEquals("Tue 17", novHistory.rows[0].dateShort)
        assertTrue(novHistory.hasEarlier)
        assertFalse(novHistory.showingAllTime)

        // Expanded All-time History view (Earlier)
        val allTimeHistory = dev.prayush.obol.ui.ObolCopy.resolveScreen(
            snapshot = snap,
            overlay = dev.prayush.obol.ui.CalmOverlay.History(showEarlierMonths = true),
            permissions = dev.prayush.obol.ui.PermissionGrantState(),
            vision = dev.prayush.obol.ui.LiveVisionUiState(),
        ) as dev.prayush.obol.ui.ObolScreenModel.HistoryList
        assertEquals("All time", allTimeHistory.monthTitle)
        assertEquals("₹280", allTimeHistory.monthTotalText)
        assertEquals("2 mornings · 2 paid", allTimeHistory.capSubtext)
        assertEquals("Avg 12 min · worst 15 min.", allTimeHistory.dryLine)
        assertEquals(2, allTimeHistory.rows.size)
        assertEquals("17 Nov", allTimeHistory.rows[0].dateShort)
        assertEquals("15 Oct", allTimeHistory.rows[1].dateShort)
        assertTrue(allTimeHistory.showingAllTime)

        // Opening FullBill from All time labels back button "‹ All time"
        val billFromAllTime = dev.prayush.obol.ui.ObolCopy.resolveScreen(
            snapshot = snap,
            overlay = dev.prayush.obol.ui.CalmOverlay.FullBill("2026-10-15", returnToEarlier = true),
            permissions = dev.prayush.obol.ui.PermissionGrantState(),
            vision = dev.prayush.obol.ui.LiveVisionUiState(),
        ) as dev.prayush.obol.ui.ObolScreenModel.FullBill
        assertEquals("‹ All time", billFromAllTime.backLabel)
    }
}
