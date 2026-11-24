package dev.prayush.obol.vision

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.prayush.obol.ObolApplication
import dev.prayush.obol.data.ObolPreferences
import dev.prayush.obol.di.AppContainer
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.time.FakeObolClock
import dev.prayush.obol.ui.ObolScreenModel
import dev.prayush.obol.ui.ObolViewModel
import dev.prayush.obol.ui.VisionFailureReason
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossingCoordinatorTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val startInstant = Instant.parse("2026-11-18T00:55:00Z")

    private fun makePassingBrushFrame(
        shiftPx: Float,
        luma: Float = 120f,
    ): VisionFrameAnalysis {
        val face = DetectedFaceInfo(
            bounds = UprightBox(140f, 160f, 340f, 380f),
            mouthMidX = 240f + shiftPx * 0.5f,
            mouthMidY = 320f,
            noseX = 240f + shiftPx * 0.5f,
            noseY = 270f,
            leftEyeOpenProb = 0.85f,
            rightEyeOpenProb = 0.88f,
        )
        val brush = UprightBox(
            left = 220f + shiftPx,
            top = 305f,
            right = 310f + shiftPx,
            bottom = 335f,
        )
        return VisionFrameAnalysis(
            frameWidth = 480,
            frameHeight = 640,
            meanLuma = luma,
            faces = listOf(face),
            toothbrushBoxes = listOf(brush),
        )
    }

    @Test
    fun `priority failure banners, electric brush micro-motion, immediate freeze, and exact 10s meter grace`() {
        val clock = FakeObolClock(initialInstant = startInstant, initialZone = zone)
        val coord = CrossingCoordinator()
        coord.onBrushingSessionOpened(clock.timePoint())

        // 1. Dark room -> TOO_DARK takes priority even if face & brush missing
        val dark = coord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 20f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertEquals(VisionFailureReason.TOO_DARK, dark.failureReason)

        // 2. Lit room, no face -> NO_FACE
        clock.advanceByMs(125L)
        val noFace = coord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertEquals(VisionFailureReason.NO_FACE, noFace.failureReason)

        // 3. Static photo spoof (0px displacement across 4 frames) -> rejected with BRUSH_AWAY_FROM_MOUTH
        repeat(4) {
            clock.advanceByMs(200L)
            val spoof = coord.onBrushingFrame(makePassingBrushFrame(shiftPx = 0f), clock.timePoint())
            assertFalse("Static photo with zero micro-motion must not pass", spoof.isWindowPassing)
        }

        // 4. Electric brush micro-motion across 3 frames in 1.0s -> passes & emits BrushingPausedMeter!
        clock.advanceByMs(1_500L)
        coord.onBrushingFrame(makePassingBrushFrame(shiftPx = 0f), clock.timePoint())
        clock.advanceByMs(200L)
        coord.onBrushingFrame(makePassingBrushFrame(shiftPx = 2f), clock.timePoint())
        clock.advanceByMs(200L)
        val passStart = coord.onBrushingFrame(makePassingBrushFrame(shiftPx = 4f), clock.timePoint())
        assertTrue(passStart.isWindowPassing)
        assertTrue(passStart.isMeterPaused)
        assertEquals(listOf(MorningEvent.BrushingPausedMeter), passStart.domainEvents.map { it.event })

        // Advance 4s of passing frames (< 10s target) -> accumulatedBrushMs advances 1:1 to 4_000ms
        repeat(20) { i ->
            clock.advanceByMs(200L)
            val shift = if (i % 2 == 0) 0f else 4f
            coord.onBrushingFrame(makePassingBrushFrame(shiftPx = shift), clock.timePoint())
        }
        assertEquals(4_000L, coord.accumulatedBrushMs)
        val lastPassingInstant = clock.now()

        // 5. Sight lost: accumulator freezes immediately at 4_000ms while meter stays paused for 9.8s (< 10.0s)
        clock.advanceByMs(9_800L)
        val inGrace = coord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertEquals("Accumulator must freeze immediately on sight loss", 4_000L, inGrace.accumulatedBrushMs)
        assertTrue("Meter must stay paused inside 10s grace", inGrace.isMeterPaused)
        assertTrue("No SQLite event emitted inside 10s grace", inGrace.domainEvents.isEmpty())

        // At exact 10.0s (9.8s + 0.2s) -> meter resumes and emits BrushingSightLost stamped at lastPassingInstant!
        clock.advanceByMs(200L)
        val graceExpired = coord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertFalse(graceExpired.isMeterPaused)
        assertEquals(1, graceExpired.domainEvents.size)
        assertEquals(MorningEvent.BrushingSightLost, graceExpired.domainEvents[0].event)
        assertEquals(lastPassingInstant, graceExpired.domainEvents[0].atPoint.at)
    }

    @Test
    fun `60s override unlocks after brief initial pass and 10s brushing emits BrushCompleted`() {
        val clock = FakeObolClock(initialInstant = startInstant, initialZone = zone)
        val coord = CrossingCoordinator()
        coord.onBrushingSessionOpened(clock.timePoint())

        // Brief initial pass
        coord.onBrushingFrame(makePassingBrushFrame(0f), clock.timePoint())
        clock.advanceByMs(200L)
        coord.onBrushingFrame(makePassingBrushFrame(2f), clock.timePoint())
        clock.advanceByMs(200L)
        assertTrue(coord.onBrushingFrame(makePassingBrushFrame(4f), clock.timePoint()).isWindowPassing)

        // Now fail continuously for 59.9s -> not unlocked; at 60.0s -> unlocked!
        clock.advanceByMs(59_900L)
        val before60 = coord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertFalse(before60.isOverrideUnlocked)

        clock.advanceByMs(100L)
        val at60 = coord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertTrue("Override must unlock after 60s continuous non-brushing even after brief initial pass", at60.isOverrideUnlocked)

        // Complete 0:10 (10_000ms) of verified brushing -> emits BrushCompleted
        val coord2 = CrossingCoordinator()
        coord2.onBrushingSessionOpened(clock.timePoint())
        var completed = false
        repeat(55) { idx ->
            clock.advanceByMs(200L)
            val res = coord2.onBrushingFrame(
                makePassingBrushFrame(shiftPx = if (idx % 2 == 0) 0f else 4f),
                clock.timePoint(),
            )
            if (res.domainEvents.any { it.event == MorningEvent.BrushCompleted }) {
                completed = true
            }
        }
        assertTrue(completed)
        assertEquals(ObolConstants.BRUSH_TARGET_MS, coord2.accumulatedBrushMs)

        // Closing camera after 5s and sleeping 2m with camera closed must NOT unlock 60s override on reopen!
        val coord3 = CrossingCoordinator()
        coord3.onBrushingSessionOpened(clock.timePoint())
        clock.advanceSeconds(5L)
        coord3.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        coord3.onBrushingSessionClosed(clock.timePoint())
        clock.advanceMinutes(2L)
        coord3.onBrushingSessionOpened(clock.timePoint())
        val onReopen = coord3.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 110f, faces = emptyList(), toothbrushBoxes = emptyList()),
            clock.timePoint(),
        )
        assertFalse("Sleeping with camera closed must not count toward 60s continuous override unlock", onReopen.isOverrideUnlocked)
    }

    @Test
    fun `verifies efficientdet_lite0 tflite asset is packaged with TFL3 magic header`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bytes = context.assets.open(RealVisionFrameDetector.MODEL_ASSET_NAME).use { stream ->
            val header = ByteArray(8)
            val read = stream.read(header)
            assertEquals(8, read)
            var total = read.toLong()
            val buf = ByteArray(16_384)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
            }
            header to total
        }
        val magic = String(bytes.first, 4, 4, Charsets.US_ASCII)
        assertEquals("TFL3", magic)
        assertTrue("Expected efficientdet_lite0.tflite > 4 MB, got ${bytes.second}", bytes.second > 4_000_000L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ViewModel updates inFreeWindow when opening camera in METERING and preserves 10s brushing progress across restart`() {
        val app = ApplicationProvider.getApplicationContext<ObolApplication>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefsFile = File(app.cacheDir, "vision_vm_test_${System.nanoTime()}.preferences_pb")
        val clock = FakeObolClock(
            initialInstant = Instant.parse("2026-11-18T00:40:00Z"), // Wed 06:10 IST
            initialZone = zone,
        )
        val prefs = ObolPreferences.createForFile(prefsFile, scope)
        val container = AppContainer(
            context = app,
            customClock = clock,
            inMemoryDb = true,
            customPreferences = prefs,
        )
        app.container = container

        val store1 = ViewModelStore()
        val store2 = ViewModelStore()

        fun drainMainNow() {
            repeat(6) {
                ShadowLooper.runUiThreadTasks()
            }
        }

        fun <T> runBg(block: suspend () -> T): T {
            val deferred = scope.async { block() }
            val deadline = System.currentTimeMillis() + 30_000L
            while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                drainMainNow()
                Thread.sleep(10L)
            }
            drainMainNow()
            if (!deferred.isCompleted) {
                deferred.cancel()
                throw AssertionError("Timed out waiting for background operation after 30s")
            }
            return deferred.getCompleted()
        }

        fun awaitMainAndRoom(predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                drainMainNow()
                if (predicate()) {
                    drainMainNow()
                    return
                }
                Thread.sleep(10L)
            }
            drainMainNow()
        }

        try {
            runBg {
                container.repository.ensureDefaultScheduleSeeded()
                container.preferences.markSetupCompleted(Instant.parse("2026-11-09T16:30:00Z"))
                container.repository.onAlarmFired(clock.now())
            }
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.FREE_WINDOW }

            val vm1 = ViewModelProvider(store1, ObolViewModel.factory(container))[ObolViewModel::class.java]
            awaitMainAndRoom { vm1.screenModel.value.screenId == "08-alarm-free" }

            // Wait 6 minutes into METERING (06:16, ₹20 at ₹20/min) before tapping "I'm up"
            clock.advanceMinutes(6L)
            container.repository.notifyClockTick()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.METERING }

            vm1.onMorningPrimaryAction()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.BRUSHING }

            clock.advanceByMs(200L)
            vm1.onCameraFrameAnalyzed(
                VisionFrameAnalysis(480, 640, meanLuma = 120f, faces = emptyList(), toothbrushBoxes = emptyList()),
            )
            awaitMainAndRoom { vm1.screenModel.value is ObolScreenModel.BrushingCamera }
            val screenInMetering = vm1.screenModel.value as ObolScreenModel.BrushingCamera
            assertEquals("15-brushing-lost", screenInMetering.screenId)
            assertEquals("meter running", screenInMetering.statusRight)

            // Brush for 4.0s (20 frames * 200ms after initial 2 warmup frames) -> 0:06 remaining
            vm1.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm1.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm1.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isBrushingSeen == true }
            repeat(20) { idx ->
                clock.advanceByMs(200L)
                vm1.onCameraFrameAnalyzed(makePassingBrushFrame(if (idx % 2 == 0) 0f else 4f))
            }
            drainMainNow()
            assertEquals(4_000L, vm1.visionUi.value.accumulatedBrushMs)

            // Close camera via Back -> persists 4_000ms
            assertTrue(vm1.onSystemBackPressed())
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.METERING }
            assertEquals(4_000L, runBg { container.repository.computeLiveSnapshot() }.activeMorning!!.verifiedBrushMs)
            store1.clear()

            // Simulate process restart reconcile + brand new ObolViewModel reattaching to active morning
            runBg { container.repository.reconcile(isProcessOrBootRestart = true) }
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.verifiedBrushMs == 4_000L }
            val vm2 = ViewModelProvider(store2, ObolViewModel.factory(container))[ObolViewModel::class.java]
            awaitMainAndRoom { vm2.visionUi.value.accumulatedBrushMs == 4_000L }
            assertEquals(4_000L, vm2.visionUi.value.accumulatedBrushMs)

            // Reopen brushing camera on vm2 -> shows 0:06 remaining and completes after remaining 6s!
            vm2.onMorningPrimaryAction()
            awaitMainAndRoom { vm2.screenModel.value is ObolScreenModel.BrushingCamera }
            val resumedScreen = vm2.screenModel.value as ObolScreenModel.BrushingCamera
            assertEquals("0:06", resumedScreen.heroRemaining)

            vm2.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm2.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm2.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isBrushingSeen == true }
            repeat(30) { idx ->
                clock.advanceByMs(200L)
                vm2.onCameraFrameAnalyzed(makePassingBrushFrame(if (idx % 2 == 0) 0f else 4f))
            }
            awaitMainAndRoom {
                container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.CLOSED_DONE
            }
            val finishedMorning = runBg { container.repository.computeLiveSnapshot() }.activeMorning!!
            assertEquals(MorningPhase.CLOSED_DONE, finishedMorning.phase)
            assertEquals(dev.prayush.obol.domain.model.Paise.fromRupees(20), finishedMorning.morningUnpaidPaise)
        } finally {
            store1.clear()
            store2.clear()
            drainMainNow()
            container.audioController.stopAll()
            container.appScope.cancel()
            container.database.close()
            scope.cancel()
            prefsFile.delete()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `backgrounding camera, frame starvation, camera error, override back-out, free-window expiry, and occlusion zero-credit`() {
        val app = ApplicationProvider.getApplicationContext<ObolApplication>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefsFile = File(app.cacheDir, "vision_edge_test_${System.nanoTime()}.preferences_pb")
        val clock = FakeObolClock(
            initialInstant = Instant.parse("2026-11-18T00:40:00Z"), // Wed 06:10 IST
            initialZone = zone,
        )
        val prefs = ObolPreferences.createForFile(prefsFile, scope)
        val container = AppContainer(
            context = app,
            customClock = clock,
            inMemoryDb = true,
            customPreferences = prefs,
        )
        app.container = container
        val store = ViewModelStore()

        fun drainMainNow() {
            repeat(6) {
                ShadowLooper.runUiThreadTasks()
            }
        }

        fun <T> runBg(block: suspend () -> T): T {
            val deferred = scope.async { block() }
            val deadline = System.currentTimeMillis() + 30_000L
            while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                drainMainNow()
                Thread.sleep(10L)
            }
            drainMainNow()
            if (!deferred.isCompleted) {
                deferred.cancel()
                throw AssertionError("Timed out waiting for background operation after 30s")
            }
            return deferred.getCompleted()
        }

        fun awaitMainAndRoom(predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                drainMainNow()
                if (predicate()) {
                    drainMainNow()
                    return
                }
                Thread.sleep(10L)
            }
            drainMainNow()
        }

        try {
            // 1. Direct unit check on CrossingCoordinator.onTimeTick (0 fps starvation after passing frames)
            val tickClock = FakeObolClock(initialInstant = startInstant, initialZone = zone)
            val tickCoord = CrossingCoordinator()
            tickCoord.onBrushingSessionOpened(tickClock.timePoint(), inFreeWindow = false)
            tickCoord.onBrushingFrame(makePassingBrushFrame(0f), tickClock.timePoint())
            tickClock.advanceByMs(200L)
            tickCoord.onBrushingFrame(makePassingBrushFrame(2f), tickClock.timePoint())
            tickClock.advanceByMs(200L)
            assertTrue(tickCoord.onBrushingFrame(makePassingBrushFrame(4f), tickClock.timePoint()).isWindowPassing)
            val lastPassAt = tickClock.now()

            tickClock.advanceSeconds(10L)
            val starved = tickCoord.onTimeTick(tickClock.timePoint())!!
            assertFalse(starved.isMeterPaused)
            assertEquals(listOf(MorningEvent.BrushingSightLost), starved.domainEvents.map { it.event })
            assertEquals(lastPassAt, starved.domainEvents.first().atPoint.at)

            tickClock.advanceSeconds(50L)
            val starved60 = tickCoord.onTimeTick(tickClock.timePoint())!!
            assertTrue(starved60.isOverrideUnlocked)

            // 2. ViewModel edge-case checks
            runBg {
                container.repository.ensureDefaultScheduleSeeded()
                container.preferences.markSetupCompleted(Instant.parse("2026-11-09T16:30:00Z"))
                container.repository.onAlarmFired(clock.now())
            }
            val vm = ViewModelProvider(store, ObolViewModel.factory(container))[ObolViewModel::class.java]
            awaitMainAndRoom { vm.screenModel.value.screenId == "08-alarm-free" }

            // 2a. Open camera at 06:14:59, cross into 06:15:01 in dark room -> TOO_DARK!
            clock.advanceSeconds(299L)
            container.repository.notifyClockTick()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isWithinFreeWindow == true }
            vm.onMorningPrimaryAction()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.BRUSHING }

            clock.advanceSeconds(2L)
            container.repository.notifyClockTick()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isWithinFreeWindow == false }
            vm.onCameraFrameAnalyzed(
                VisionFrameAnalysis(480, 640, meanLuma = 12f, faces = emptyList(), toothbrushBoxes = emptyList()),
            )
            awaitMainAndRoom {
                (vm.screenModel.value as? ObolScreenModel.BrushingCamera)?.screenId == "15-brushing-lost"
            }
            val expiredFreeScreen = vm.screenModel.value as ObolScreenModel.BrushingCamera
            assertTrue(expiredFreeScreen.statusBanner.startsWith("Too dark."))

            // 2b. Brush 3s, pause 8s (< 10s grace), brush 3s -> live accumulator stays exact 6_000ms after sight loss!
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            repeat(15) { idx ->
                clock.advanceByMs(200L)
                vm.onCameraFrameAnalyzed(makePassingBrushFrame(if (idx % 2 == 0) 0f else 4f))
            }
            assertEquals(3_000L, vm.visionUi.value.accumulatedBrushMs)

            clock.advanceSeconds(8L)
            vm.onCameraFrameAnalyzed(
                VisionFrameAnalysis(480, 640, meanLuma = 120f, faces = emptyList(), toothbrushBoxes = emptyList()),
            )
            assertEquals(3_000L, vm.visionUi.value.accumulatedBrushMs)

            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            repeat(15) { idx ->
                clock.advanceByMs(200L)
                vm.onCameraFrameAnalyzed(makePassingBrushFrame(if (idx % 2 == 0) 0f else 4f))
            }
            assertEquals(6_000L, vm.visionUi.value.accumulatedBrushMs)

            clock.advanceSeconds(10L)
            vm.onCameraFrameAnalyzed(
                VisionFrameAnalysis(480, 640, meanLuma = 120f, faces = emptyList(), toothbrushBoxes = emptyList()),
            )
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isBrushingSeen == false }
            assertEquals(
                "Live ViewModel must not inflate accumulatedBrushMs with <10s occlusion gap",
                6_000L,
                vm.visionUi.value.accumulatedBrushMs,
            )

            // 2c. Camera hardware error while BrushingPausedMeter is active must immediately surface 15-brushing-lost + override link!
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isBrushingSeen == true }

            vm.onCameraHardwareError()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isBrushingSeen == false }
            val errScreen = vm.screenModel.value as ObolScreenModel.BrushingCamera
            assertEquals("15-brushing-lost", errScreen.screenId)
            assertTrue("Camera error must immediately show override link", errScreen.showOverrideLink)

            // 2d. Open 16-override, press Back (OverrideClosed), and resume brushing -> emits BrushingPausedMeter cleanly!
            vm.onOpenOverride()
            awaitMainAndRoom { vm.screenModel.value is ObolScreenModel.OverrideTyping }
            assertTrue(vm.onSystemBackPressed())
            awaitMainAndRoom { vm.screenModel.value is ObolScreenModel.BrushingCamera }

            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.isBrushingSeen == true }

            // 2e. Config change keeps BRUSHING open, whereas real Activity stop closes camera to METERING!
            vm.onActivityStopped(isChangingConfigurations = true)
            drainMainNow()
            assertEquals(MorningPhase.BRUSHING, container.repository.currentSnapshotNow().activeMorning?.phase)

            vm.onActivityStopped(isChangingConfigurations = false)
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.METERING }
            assertEquals(MorningPhase.METERING, container.repository.currentSnapshotNow().activeMorning?.phase)
        } finally {
            store.clear()
            drainMainNow()
            container.audioController.stopAll()
            container.appScope.cancel()
            container.database.close()
            scope.cancel()
            prefsFile.delete()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `free-window expiry emits TOO_DARK both before snapshot tick and before Main collector drains`() {
        val app = ApplicationProvider.getApplicationContext<ObolApplication>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefsFile1 = File(app.cacheDir, "vision_race1_${System.nanoTime()}.preferences_pb")
        val prefsFile2 = File(app.cacheDir, "vision_race2_${System.nanoTime()}.preferences_pb")

        fun drainMainNow() {
            repeat(6) {
                ShadowLooper.runUiThreadTasks()
            }
        }

        fun <T> runBg(block: suspend () -> T): T {
            val deferred = scope.async { block() }
            val deadline = System.currentTimeMillis() + 30_000L
            while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                drainMainNow()
                Thread.sleep(10L)
            }
            drainMainNow()
            if (!deferred.isCompleted) {
                deferred.cancel()
                throw AssertionError("Timed out waiting for background operation after 30s")
            }
            return deferred.getCompleted()
        }

        fun awaitMainAndRoom(predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                drainMainNow()
                if (predicate()) {
                    drainMainNow()
                    return
                }
                Thread.sleep(10L)
            }
            drainMainNow()
        }

        // Interleaving A: Camera frame arrives at 06:15:01 BEFORE repository.notifyClockTick() runs at all.
        try {
            val clock1 = FakeObolClock(initialInstant = Instant.parse("2026-11-18T00:40:00Z"), initialZone = zone)
            val container1 = AppContainer(
                context = app,
                customClock = clock1,
                inMemoryDb = true,
                customPreferences = ObolPreferences.createForFile(prefsFile1, scope),
            )
            app.container = container1
            val store1 = ViewModelStore()
            try {
                runBg {
                    container1.repository.ensureDefaultScheduleSeeded()
                    container1.preferences.markSetupCompleted(Instant.parse("2026-11-09T16:30:00Z"))
                    container1.repository.onAlarmFired(clock1.now())
                }
                val vm1 = ViewModelProvider(store1, ObolViewModel.factory(container1))[ObolViewModel::class.java]
                clock1.advanceSeconds(299L)
                container1.repository.notifyClockTick()
                awaitMainAndRoom { container1.repository.currentSnapshotNow().now == clock1.now() }
                vm1.onMorningPrimaryAction()
                awaitMainAndRoom { container1.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.BRUSHING }

                clock1.advanceSeconds(2L)
                assertTrue("Precondition: snapshot still reflects free window before tick", container1.repository.currentSnapshotNow().activeMorning!!.isWithinFreeWindow)
                vm1.onCameraFrameAnalyzed(
                    VisionFrameAnalysis(480, 640, meanLuma = 12f, faces = emptyList(), toothbrushBoxes = emptyList()),
                )
                assertEquals(VisionFailureReason.TOO_DARK, vm1.visionUi.value.failureReason)
                awaitMainAndRoom {
                    val screen = vm1.screenModel.value as? ObolScreenModel.BrushingCamera
                    screen?.screenId == "15-brushing-lost" && screen.statusBanner.startsWith("Too dark.")
                }
            } finally {
                store1.clear()
                drainMainNow()
                container1.audioController.stopAll()
                container1.appScope.cancel()
                container1.database.close()
                prefsFile1.delete()
            }

            // Interleaving B: Dispatchers.Default updates repository.snapshot (isWithinFreeWindow = false)
            // while MainLooper is still paused (syncCoordinatorForActiveMorning collector has not run yet).
            val clock2 = FakeObolClock(initialInstant = Instant.parse("2026-11-18T00:40:00Z"), initialZone = zone)
            val container2 = AppContainer(
                context = app,
                customClock = clock2,
                inMemoryDb = true,
                customPreferences = ObolPreferences.createForFile(prefsFile2, scope),
            )
            app.container = container2
            val store2 = ViewModelStore()
            try {
                runBg {
                    container2.repository.ensureDefaultScheduleSeeded()
                    container2.preferences.markSetupCompleted(Instant.parse("2026-11-09T16:30:00Z"))
                    container2.repository.onAlarmFired(clock2.now())
                }
                val vm2 = ViewModelProvider(store2, ObolViewModel.factory(container2))[ObolViewModel::class.java]
                clock2.advanceSeconds(299L)
                container2.repository.notifyClockTick()
                awaitMainAndRoom { container2.repository.currentSnapshotNow().now == clock2.now() }
                vm2.onMorningPrimaryAction()
                awaitMainAndRoom { container2.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.BRUSHING }

                clock2.advanceSeconds(2L)
                container2.repository.notifyClockTick()
                // Wait for background Default snapshot WITHOUT draining MainLooper!
                val bgDeadline = System.currentTimeMillis() + 30_000L
                while (System.currentTimeMillis() < bgDeadline &&
                    container2.repository.currentSnapshotNow().activeMorning?.isWithinFreeWindow != false
                ) {
                    Thread.sleep(5L)
                }
                assertFalse(container2.repository.currentSnapshotNow().activeMorning!!.isWithinFreeWindow)

                // Analyze frame BEFORE MainLooper collector runs syncCoordinatorForActiveMorning
                vm2.onCameraFrameAnalyzed(
                    VisionFrameAnalysis(480, 640, meanLuma = 12f, faces = emptyList(), toothbrushBoxes = emptyList()),
                )
                assertEquals(VisionFailureReason.TOO_DARK, vm2.visionUi.value.failureReason)
                drainMainNow()
                val screen2 = vm2.screenModel.value as ObolScreenModel.BrushingCamera
                assertEquals("15-brushing-lost", screen2.screenId)
                assertTrue(screen2.statusBanner.startsWith("Too dark."))
            } finally {
                store2.clear()
                drainMainNow()
                container2.audioController.stopAll()
                container2.appScope.cancel()
                container2.database.close()
                prefsFile2.delete()
            }
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `0fps dark-room expiry, tick banner preservation, late post-Back frames, rapid Open-Back-Open, Recents swipe, and override stop`() {
        val app = ApplicationProvider.getApplicationContext<ObolApplication>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefsFile = File(app.cacheDir, "vision_adversarial_${System.nanoTime()}.preferences_pb")

        fun drainMainNow() {
            repeat(6) {
                ShadowLooper.runUiThreadTasks()
            }
        }

        fun <T> runBg(block: suspend () -> T): T {
            val deferred = scope.async { block() }
            val deadline = System.currentTimeMillis() + 30_000L
            while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                drainMainNow()
                Thread.sleep(10L)
            }
            drainMainNow()
            if (!deferred.isCompleted) {
                deferred.cancel()
                throw AssertionError("Timed out waiting for background operation after 30s")
            }
            return deferred.getCompleted()
        }

        fun awaitMainAndRoom(predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                drainMainNow()
                if (predicate()) {
                    drainMainNow()
                    return
                }
                Thread.sleep(10L)
            }
            drainMainNow()
        }

        // Part 1: Direct CrossingCoordinator check — onTimeTick preserves TOO_DARK at 10s sight-loss & 60s override unlock
        val tickClock = FakeObolClock(initialInstant = startInstant, initialZone = zone)
        val tickCoord = CrossingCoordinator()
        tickCoord.onBrushingSessionOpened(tickClock.timePoint(), inFreeWindow = false)
        tickCoord.onBrushingFrame(makePassingBrushFrame(0f), tickClock.timePoint())
        tickClock.advanceByMs(200L)
        tickCoord.onBrushingFrame(makePassingBrushFrame(2f), tickClock.timePoint())
        tickClock.advanceByMs(200L)
        assertTrue(tickCoord.onBrushingFrame(makePassingBrushFrame(4f), tickClock.timePoint()).isWindowPassing)
        tickClock.advanceByMs(700L)
        val darkLost = tickCoord.onBrushingFrame(
            VisionFrameAnalysis(480, 640, meanLuma = 12f, faces = emptyList(), toothbrushBoxes = emptyList()),
            tickClock.timePoint(),
        )
        assertEquals(VisionFailureReason.TOO_DARK, darkLost.failureReason)
        tickClock.advanceByMs(9_300L)
        val tick10 = tickCoord.onTimeTick(tickClock.timePoint(), inFreeWindow = false)!!
        assertEquals("10s dropout tick must preserve TOO_DARK instead of resetting to NO_TOOTHBRUSH", VisionFailureReason.TOO_DARK, tick10.failureReason)
        tickClock.advanceSeconds(50L)
        val tick60 = tickCoord.onTimeTick(tickClock.timePoint(), inFreeWindow = false)!!
        assertTrue(tick60.isOverrideUnlocked)
        assertEquals("60s override unlock tick must preserve TOO_DARK", VisionFailureReason.TOO_DARK, tick60.failureReason)

        // Part 2: 0 fps dark-room free-window expiry + late frames during Back + rapid Open->Back->Open + override background + Recents swipe
        val clock = FakeObolClock(initialInstant = Instant.parse("2026-11-18T00:40:00Z"), initialZone = zone)
        val container = AppContainer(
            context = app,
            customClock = clock,
            inMemoryDb = true,
            customPreferences = ObolPreferences.createForFile(prefsFile, scope),
        )
        app.container = container
        val store = ViewModelStore()
        try {
            runBg {
                container.repository.ensureDefaultScheduleSeeded()
                container.preferences.markSetupCompleted(Instant.parse("2026-11-09T16:30:00Z"))
                container.repository.onAlarmFired(clock.now())
            }
            val vm = ViewModelProvider(store, ObolViewModel.factory(container))[ObolViewModel::class.java]
            awaitMainAndRoom { vm.screenModel.value.screenId == "08-alarm-free" }

            // 2a. 0 fps dark room: single dark frame at 06:14:59.5 -> ACQUIRING, then clock ticks past 06:15:00.0 with ZERO new frames!
            clock.advanceByMs(299_500L)
            container.repository.notifyClockTick()
            awaitMainAndRoom { container.repository.currentSnapshotNow().now == clock.now() }
            vm.onMorningPrimaryAction()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.BRUSHING }

            vm.onCameraFrameAnalyzed(
                VisionFrameAnalysis(480, 640, meanLuma = 12f, faces = emptyList(), toothbrushBoxes = emptyList()),
            )
            assertEquals(VisionFailureReason.ACQUIRING, vm.visionUi.value.failureReason)
            awaitMainAndRoom {
                val scr = vm.screenModel.value as? ObolScreenModel.BrushingCamera
                scr?.screenId == "14-brushing" && scr.statusBanner == "Looking for brushing..."
            }

            // Advance 1s into 06:15:00.5 and tick WITHOUT analyzing any further camera frame (0 fps starvation)
            clock.advanceSeconds(1L)
            container.repository.notifyClockTick()
            awaitMainAndRoom {
                val scr = vm.screenModel.value as? ObolScreenModel.BrushingCamera
                scr?.screenId == "15-brushing-lost" && scr.statusBanner.startsWith("Too dark.")
            }
            val zeroFpsScreen = vm.screenModel.value as ObolScreenModel.BrushingCamera
            assertTrue("0 fps expiry must immediately surface TOO_DARK, got ${zeroFpsScreen.statusBanner}", zeroFpsScreen.statusBanner.startsWith("Too dark."))

            // 2b. Late camera frames arriving on Main IMMEDIATELY after System Back (while CameraClosed Room write is still queued)
            assertTrue(vm.onSystemBackPressed())
            // Feed 3 passing brushing frames before draining Room/Main!
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))

            awaitMainAndRoom {
                runBg { container.repository.computeLiveSnapshot() }.activeMorning?.phase == MorningPhase.METERING
            }
            val afterLateFrames = runBg { container.repository.computeLiveSnapshot() }.activeMorning!!
            assertEquals(MorningPhase.METERING, afterLateFrames.phase)
            assertFalse("Late post-Back frames must never pause the meter after CameraClosed", afterLateFrames.isBrushingSeen)

            // 2c. Rapid "I'm up" -> Back -> "I'm up" burst BEFORE intermediate snapshots drain
            vm.onMorningPrimaryAction()
            assertTrue("Back pressed immediately before snapshot reaches BRUSHING must still close session", vm.onSystemBackPressed())
            clock.advanceByMs(200L)
            vm.onMorningPrimaryAction()
            vm.onCameraFrameAnalyzed(
                VisionFrameAnalysis(480, 640, meanLuma = 10f, faces = emptyList(), toothbrushBoxes = emptyList()),
            )
            assertEquals(VisionFailureReason.TOO_DARK, vm.visionUi.value.failureReason)
            awaitMainAndRoom {
                val live = runBg { container.repository.computeLiveSnapshot() }.activeMorning
                val scr = vm.screenModel.value as? ObolScreenModel.BrushingCamera
                live?.phase == MorningPhase.BRUSHING && scr?.screenId == "15-brushing-lost" && scr.statusBanner.startsWith("Too dark.")
            }

            // 2d. Backgrounding from 16-override closes MorningPhase.BRUSHING so alarm vibration resumes on escalated mornings
            vm.onCameraHardwareError()
            awaitMainAndRoom { (vm.screenModel.value as? ObolScreenModel.BrushingCamera)?.showOverrideLink == true }
            vm.onOpenOverride()
            // Late passing frames while OverrideOpened is in flight must also be ignored
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            awaitMainAndRoom { vm.screenModel.value is ObolScreenModel.OverrideTyping }

            vm.onActivityStopped(isChangingConfigurations = false)
            awaitMainAndRoom {
                runBg { container.repository.computeLiveSnapshot() }.activeMorning?.phase == MorningPhase.METERING
            }
            assertFalse(runBg { container.repository.computeLiveSnapshot() }.activeMorning!!.isInOverrideScreen)

            // 2e. Recents swipe: onActivityStopped(false) immediately followed by ViewModelStore.clear() (cancelling viewModelScope!)
            // while BrushingPausedMeter is active MUST still persist BrushingSightLost + CameraClosed via appScope!
            vm.onMorningPrimaryAction()
            awaitMainAndRoom { container.repository.currentSnapshotNow().activeMorning?.phase == MorningPhase.BRUSHING }
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(0f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(2f))
            clock.advanceByMs(200L)
            vm.onCameraFrameAnalyzed(makePassingBrushFrame(4f))
            awaitMainAndRoom { runBg { container.repository.computeLiveSnapshot() }.activeMorning?.isBrushingSeen == true }

            // Simulate Android Recents swipe: onStop -> immediate onDestroy/onCleared without yielding in between!
            vm.onActivityStopped(isChangingConfigurations = false)
            store.clear()

            awaitMainAndRoom {
                val live = runBg { container.repository.computeLiveSnapshot() }.activeMorning
                live?.phase == MorningPhase.METERING && !live.isBrushingSeen
            }
            val afterRecentsSwipe = runBg { container.repository.computeLiveSnapshot() }.activeMorning!!
            assertEquals("Recents swipe must not leave morning stuck in BRUSHING", MorningPhase.METERING, afterRecentsSwipe.phase)
            assertFalse("Recents swipe must not leave meter permanently paused", afterRecentsSwipe.isBrushingSeen)
        } finally {
            store.clear()
            drainMainNow()
            container.audioController.stopAll()
            container.appScope.cancel()
            container.database.close()
            scope.cancel()
            prefsFile.delete()
        }
    }
}
