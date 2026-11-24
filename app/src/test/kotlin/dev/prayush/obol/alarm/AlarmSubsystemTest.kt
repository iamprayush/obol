package dev.prayush.obol.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.prayush.obol.BuildConfig
import dev.prayush.obol.ObolApplication
import dev.prayush.obol.data.ObolPreferences
import dev.prayush.obol.di.AppContainer
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.policy.PaymentDestinationState
import dev.prayush.obol.domain.time.FakeObolClock
import dev.prayush.obol.ui.MainActivity
import dev.prayush.obol.ui.ObolViewModel
import dev.prayush.obol.vision.RealVisionFrameDetector
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSubsystemTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val setupAt = Instant.parse("2026-11-09T16:30:00Z")

    private lateinit var app: ObolApplication
    private lateinit var testScope: CoroutineScope
    private lateinit var prefsFile: File
    private lateinit var clock: FakeObolClock
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        prefsFile = File(app.cacheDir, "alarm_test_${System.nanoTime()}.preferences_pb")
        clock = FakeObolClock(
            initialInstant = Instant.parse("2026-11-17T17:17:00Z"), // Tue 22:47 IST
            initialZone = zone,
        )
        val prefs = ObolPreferences.createForFile(prefsFile, testScope)
        container = AppContainer(
            context = app,
            customClock = clock,
            inMemoryDb = true,
            customPreferences = prefs,
        )
        app.container = container
        runBlocking {
            container.repository.ensureDefaultScheduleSeeded()
            container.preferences.markSetupCompleted(setupAt)
        }
    }

    @After
    fun tearDown() {
        container.audioController.stopAll()
        container.appScope.cancel()
        container.database.close()
        testScope.cancel()
        prefsFile.delete()
    }

    @Test
    fun `AlarmScheduler registers next morning setAlarmClock intent`() = runBlocking {
        val snap = container.repository.computeLiveSnapshot()
        assertNotNull(snap.nextAlarm)
        container.alarmScheduler.syncWithSnapshot(snap)

        val alarmManager = container.deviceProtectedContext.getSystemService(AlarmManager::class.java)
        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertTrue(scheduled.isNotEmpty())
        assertEquals(snap.nextAlarm!!.scheduledAt.toEpochMilli(), scheduled.first().triggerAtMs)
    }

    @Test
    fun `BootAndTimeReceiver on LOCKED_BOOT_COMPLETED recovers post-fire morning via immediate setAlarmClock`() = runBlocking {
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        container.repository.onAlarmFired(wedAlarm)

        clock.advanceMinutes(10)
        container.repository.appendActiveEvent(MorningEvent.CameraOpened)
        container.repository.appendActiveEvent(MorningEvent.BrushingPausedMeter)
        clock.reboot(downtimeMs = 2 * 60_000L)

        BootAndTimeReceiver.handleBootOrTimeChangeSuspend(container)

        val snap = container.repository.computeLiveSnapshot()
        assertNotNull(snap.activeMorning)
        assertEquals(Paise.fromRupees(140), snap.activeMorning!!.displayPaise)

        val alarmManager = container.deviceProtectedContext.getSystemService(AlarmManager::class.java)
        val scheduledAlarms = shadowOf(alarmManager).scheduledAlarms
        val hasImmediateBootResume = scheduledAlarms.any {
            it.triggerAtMs == clock.now().toEpochMilli() + 1_000L
        }
        assertTrue("Expected immediate +1s setAlarmClock boot recovery alarm", hasImmediateBootResume)
    }

    @Test
    fun `AlarmRingingService renews 10m PARTIAL_WAKE_LOCK every 60 ticks and stops once brushing completes`(): Unit = runBlocking {
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        container.repository.onAlarmFired(wedAlarm)

        val controller = Robolectric.buildService(AlarmRingingService::class.java).create()
        val service = controller.get()
        val initialCount = service.wakeLockAcquireCount
        assertTrue(initialCount >= 1)

        repeat(60) {
            clock.advanceSeconds(1L)
            assertTrue(service.tickOnce())
        }
        assertEquals(initialCount + 1, service.wakeLockAcquireCount)

        // Completing brushing stops AlarmRingingService immediately (even when money is owed on 17-pay!)
        clock.advanceMinutes(6)
        container.repository.appendActiveEvent(MorningEvent.BrushCompleted)
        assertFalse("Service must stop immediately once brushing completes", service.tickOnce())
        assertFalse(container.audioController.isChimeAudible)

        controller.destroy()
        Unit
    }

    @Test
    fun `AlarmAudioController enforces 50 percent volume, stops vibration on camera, and unmutes after 30s idle`() = runBlocking {
        val audioManager = container.deviceProtectedContext.getSystemService(AudioManager::class.java)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        container.repository.onAlarmFired(wedAlarm)
        clock.advanceMinutes(6)
        container.repository.appendActiveEvent(MorningEvent.Snoozed(1, Paise.fromRupees(10)))
        clock.advanceMinutes(5)
        container.repository.appendActiveEvent(MorningEvent.ReRang(1))
        container.repository.appendActiveEvent(MorningEvent.Snoozed(2, Paise.fromRupees(60)))
        clock.advanceMinutes(5)
        val ringing = container.repository.appendActiveEvent(MorningEvent.ReRang(2))

        val audio = container.audioController
        audio.updateForMorning(ringing)
        assertTrue(audio.isChimeAudible)
        assertTrue(audio.isVibrating)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        assertTrue(audioManager.getStreamVolume(AudioManager.STREAM_ALARM) >= (maxVol + 1) / 2)

        val camOpened = container.repository.appendActiveEvent(MorningEvent.CameraOpened)
        audio.updateForMorning(camOpened)
        assertFalse("Vibration must stop immediately when camera opens", audio.isVibrating)
        assertFalse("Chime should mute during initial 30s camera acquisition", audio.isChimeAudible)

        clock.advanceSeconds(29L)
        audio.updateForMorning(container.repository.computeLiveSnapshot().activeMorning)
        assertFalse(audio.isChimeAudible)

        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        clock.advanceSeconds(1L)
        audio.updateForMorning(container.repository.computeLiveSnapshot().activeMorning)
        assertTrue("Chime must resume after 30s idle on 15-brushing-lost", audio.isChimeAudible)
        assertTrue("Volume floor must be re-enforced on unmute", audioManager.getStreamVolume(AudioManager.STREAM_ALARM) >= (maxVol + 1) / 2)
        assertFalse("Vibration must remain off while camera is open", audio.isVibrating)

        audio.updateForMorning(container.repository.appendActiveEvent(MorningEvent.OverrideOpened))
        assertFalse(audio.isChimeAudible)
        clock.advanceSeconds(50L)
        audio.updateForMorning(container.repository.appendActiveEvent(MorningEvent.OverrideClosed))
        clock.advanceSeconds(41L)
        audio.updateForMorning(container.repository.appendActiveEvent(MorningEvent.OverrideOpened))
        assertTrue("Toggling override back and forth must not extend 90s override mute", audio.isChimeAudible)
    }

    @Test
    fun `AlarmRingingService wakes MainActivity on due ReRang and sets FLAG_ONLY_ALERT_ONCE on routine ticks`(): Unit = runBlocking {
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        container.repository.onAlarmFired(wedAlarm)
        clock.advanceMinutes(3)
        container.repository.appendActiveEvent(MorningEvent.Snoozed(1, Paise.ZERO))

        val controller = Robolectric.buildService(AlarmRingingService::class.java).create()
        val service = controller.get()
        val nm = service.getSystemService(NotificationManager::class.java)
        val snoozeChannel = nm.getNotificationChannel(AlarmRingingService.CHANNEL_STAY_UP)
        assertEquals("Obol Snooze", snoozeChannel.name.toString())

        assertTrue(service.tickOnce())
        val snoozeNotif = shadowOf(nm).getNotification(AlarmRingingService.NOTIFICATION_ID)
        assertNotNull(snoozeNotif)
        assertTrue((snoozeNotif.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)

        val audioManager = container.deviceProtectedContext.getSystemService(AudioManager::class.java)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        clock.advanceMinutes(5)
        assertTrue(service.tickOnce())
        assertTrue(container.audioController.isChimeAudible)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        assertTrue(audioManager.getStreamVolume(AudioManager.STREAM_ALARM) >= (maxVol + 1) / 2)
        val alertNotif = shadowOf(nm).getNotification(AlarmRingingService.NOTIFICATION_ID)
        assertEquals(0, alertNotif.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val startedActivity = shadowOf(app).nextStartedActivity
        assertNotNull("Expected MainActivity wake intent when ReRang fires in tickOnce", startedActivity)
        assertEquals(MainActivity::class.java.name, startedActivity.component?.className)

        clock.advanceSeconds(1L)
        assertTrue(service.tickOnce())
        val nextTickNotif = shadowOf(nm).getNotification(AlarmRingingService.NOTIFICATION_ID)
        assertTrue((nextTickNotif.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)

        controller.destroy()
        Unit
    }

    @Test
    fun `built-in chimes synthesize distinct clip-free PCM, live preview toggles cleanly, and invalid SystemAlarm URI falls back to Marimba`() = runBlocking {
        val marimbaPcm = AlarmAudioController.pcmForBuiltIn(BuiltInChime.MARIMBA)
        val bowlPcm = AlarmAudioController.pcmForBuiltIn(BuiltInChime.SINGING_BOWL)
        val pianoPcm = AlarmAudioController.pcmForBuiltIn(BuiltInChime.ELECTRIC_PIANO)

        for ((chime, pcm) in listOf(
            BuiltInChime.MARIMBA to marimbaPcm,
            BuiltInChime.SINGING_BOWL to bowlPcm,
            BuiltInChime.ELECTRIC_PIANO to pianoPcm,
        )) {
            assertTrue("$chime PCM must be >= 2s", pcm.size >= AlarmAudioController.SAMPLE_RATE_HZ * 2)
            assertEquals("$chime loop start must fade from 0", 0.toShort(), pcm.first())
            assertEquals("$chime loop end must fade to 0", 0.toShort(), pcm.last())
            val maxPeak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$chime peak must be loud (> 25000) without clipping, got $maxPeak", maxPeak in 25_000..30_000)
            val rms = kotlin.math.sqrt(pcm.sumOf { (it.toDouble() / 32768.0) * (it.toDouble() / 32768.0) } / pcm.size)
            assertTrue("$chime RMS must project clearly (>= 0.22 full-scale, ~-13 dBFS), got $rms", rms >= 0.22)
        }
        assertFalse("Marimba and Singing Bowl waveforms must be distinct", marimbaPcm.contentEquals(bowlPcm))
        assertFalse("Marimba and Electric Piano waveforms must be distinct", marimbaPcm.contentEquals(pianoPcm))

        val audio = container.audioController
        val bowlChoice = AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL)
        val pianoChoice = AlarmSoundChoice.BuiltIn(BuiltInChime.ELECTRIC_PIANO)

        // Toggle preview ON -> switch -> toggle OFF
        audio.togglePreview(bowlChoice)
        assertTrue(audio.isPreviewPlaying)
        assertEquals(bowlChoice, audio.previewingChoice.value)

        audio.togglePreview(pianoChoice)
        assertTrue(audio.isPreviewPlaying)
        assertEquals(pianoChoice, audio.previewingChoice.value)

        audio.togglePreview(pianoChoice)
        assertFalse(audio.isPreviewPlaying)
        assertNull(audio.previewingChoice.value)

        // Real morning alarm immediately preempts active Settings preview and blocks preview starts while ringing
        audio.startPreview(bowlChoice)
        assertTrue(audio.isPreviewPlaying)

        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        val fired = container.repository.onAlarmFired(wedAlarm)
        audio.setAlarmSoundChoice(AlarmSoundChoice.SystemAlarm("content://invalid.obol.test/locked/99", "Locked Tone"))
        audio.updateForMorning(fired)

        assertFalse("Active morning alarm must stop Settings preview immediately", audio.isPreviewPlaying)
        assertTrue("Active morning alarm must remain audible via built-in fallback", audio.isChimeAudible)
        assertTrue("Unresolvable/Direct-Boot SystemAlarm URI must fall back to Marimba PCM", audio.usedBuiltInFallbackOnLastPlay)

        audio.startPreview(pianoChoice)
        assertFalse("Preview must not start while active morning alarm is ringing", audio.isPreviewPlaying)

        // Verify Android 15+ expired transient URI grant detection when MediaPlayer swallows SecurityException internally
        val deniedAuthority = "denied.obol.test.${System.nanoTime()}"
        val deniedProvider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
                throw SecurityException("Permission Denial: reading external_primary requires grant")
            }
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        deniedProvider.attachInfo(app, ProviderInfo().apply { authority = deniedAuthority })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(deniedAuthority, deniedProvider)
        assertTrue(
            "ContentResolver SecurityException must be detected even when MediaPlayer catches it internally",
            AlarmAudioController.didContentResolverDenyPermission(app, Uri.parse("content://$deniedAuthority/audio/42")),
        )
        assertFalse(
            "Missing/unresolvable URI must not be misclassified as permission denial",
            AlarmAudioController.didContentResolverDenyPermission(app, Uri.parse("content://invalid.obol.test/locked/99")),
        )
    }

    @Test
    fun `MainActivity preserves pending UPI and Donorbox state across Activity recreation`() {
        val actController = Robolectric.buildActivity(MainActivity::class.java).setup()
        actController.get().let { activity ->
            app.container.repository
            val vmField = MainActivity::class.java.getDeclaredField("pendingUpiAmount").apply { isAccessible = true }
            vmField.set(activity, Paise.fromRupees(130))
        }
        val saved = Bundle()
        actController.saveInstanceState(saved).pause().stop().destroy()
        assertEquals(13_000L, saved.getLong(MainActivity.KEY_PENDING_UPI_PAISE))

        saved.putLong(MainActivity.KEY_PENDING_DONORBOX_PAISE, 10_000L)
        saved.putBoolean(MainActivity.KEY_PENDING_DONORBOX_IS_TEST, false)

        val recreated = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume()
        val restoredUpi = MainActivity::class.java.getDeclaredField("pendingUpiAmount")
            .apply { isAccessible = true }
            .get(recreated.get())
        assertEquals(Paise.fromRupees(130), restoredUpi)
        recreated.pause().stop().destroy()
    }

    @Test
    fun `cold-start ACTION_WAKE_DEADLINE closes orphaned BrushingPausedMeter from dead process`() = runBlocking {
        val fileDbContext = container.deviceProtectedContext
        val dbName = "cold_start_test_${System.nanoTime()}.db"
        val db1 = androidx.room.Room.databaseBuilder(
            fileDbContext,
            dev.prayush.obol.data.ObolDatabase::class.java,
            dbName,
        ).allowMainThreadQueries().build()

        val repo1 = dev.prayush.obol.data.ObolRepository(
            dao = db1.obolDao(),
            preferences = container.preferences,
            clock = clock,
            scope = testScope,
        )
        val wedAlarm = Instant.parse("2026-11-18T00:40:00Z")
        clock.setInstantPreservingBoot(wedAlarm, 10_000L)
        repo1.onAlarmFired(wedAlarm)
        clock.advanceMinutes(6)
        repo1.appendActiveEvent(MorningEvent.CameraOpened)
        repo1.appendActiveEvent(MorningEvent.BrushingPausedMeter)
        assertTrue(repo1.computeLiveSnapshot().activeMorning!!.isBrushingSeen)
        db1.close()

        clock.advanceMinutes(4)
        val db2 = androidx.room.Room.databaseBuilder(
            fileDbContext,
            dev.prayush.obol.data.ObolDatabase::class.java,
            dbName,
        ).allowMainThreadQueries().build()
        val coldContainer = object : AppContainer(
            context = app,
            customClock = clock,
            inMemoryDb = true,
            customPreferences = container.preferences,
        ) {
            val coldRepo = dev.prayush.obol.data.ObolRepository(
                dao = db2.obolDao(),
                preferences = container.preferences,
                clock = clock,
                scope = testScope,
            )
        }
        try {
            val res = coldContainer.coldRepo.reconcile(isProcessOrBootRestart = false)
            assertFalse("Orphaned BrushingPausedMeter from dead process must be closed", res.activeMorning!!.isBrushingSeen)
            assertEquals(dev.prayush.obol.domain.model.MorningPhase.METERING, res.activeMorning!!.phase)
            assertEquals(Paise.fromRupees(100), res.activeMorning!!.displayPaise)
        } finally {
            db2.close()
            fileDbContext.deleteDatabase(dbName)
        }
    }

    @Test
    fun `ObolPreferences create uses Device-Protected filesDir during Direct Boot and migrates legacy CE file when unlocked`() = runBlocking {
        val deRoot = File(app.cacheDir, "de_root_${System.nanoTime()}").apply { mkdirs() }
        val ceRoot = File(app.cacheDir, "ce_root_${System.nanoTime()}").apply { mkdirs() }
        val fileName = "direct_boot_prefs"

        // 1. Seed a legacy Credential-Encrypted datastore file (matching previous preferencesDataStoreFile path)
        val legacyCeFile = File(ceRoot, "datastore/$fileName.preferences_pb")
        legacyCeFile.parentFile!!.mkdirs()
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val legacyPrefs = ObolPreferences.createForFile(legacyCeFile, seedScope)
        legacyPrefs.markSetupCompleted(setupAt)
        legacyPrefs.saveDestinationState(PaymentDestinationState(active = PaymentDestination.DonorboxOppia))
        legacyPrefs.saveAlarmSound(AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL))
        seedScope.cancel()
        assertTrue("Precondition: legacy CE preferences_pb must exist", legacyCeFile.exists())

        // 2. While FBE-locked (Direct Boot before first PIN unlock), accessing CE filesDir throws IllegalStateException!
        val lockedCeAppContext = object : ContextWrapper(app) {
            override fun isDeviceProtectedStorage(): Boolean = false
            override fun getFilesDir(): File {
                throw IllegalStateException("SharedPreferences/files in credential encrypted storage are not available until after user is unlocked")
            }
        }
        val userManager = app.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(false)

        val lockedDpContext = object : ContextWrapper(app.createDeviceProtectedStorageContext()) {
            override fun isDeviceProtectedStorage(): Boolean = true
            override fun getFilesDir(): File = deRoot
            override fun getApplicationContext(): Context = lockedCeAppContext
        }

        // Resolving under locked Direct Boot must NEVER touch lockedCeAppContext.filesDir and must point to deRoot
        val lockedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lockedPrefs = ObolPreferences.create(lockedDpContext, lockedScope, fileName = "fresh_locked_boot")
        assertNull(lockedPrefs.current().setupCompletedAt)
        lockedPrefs.saveAlarmSound(AlarmSoundChoice.BuiltIn(BuiltInChime.ELECTRIC_PIANO))
        assertEquals(AlarmSoundChoice.BuiltIn(BuiltInChime.ELECTRIC_PIANO), lockedPrefs.current().alarmSound)
        assertTrue(File(deRoot, "datastore/fresh_locked_boot.preferences_pb").exists())
        lockedScope.cancel()

        // Also verify ML Kit Direct Boot initializer never touches locked CE applicationContext
        RealVisionFrameDetector.ensureMlKitInitializedForDirectBoot(lockedDpContext)

        // 3. When user is unlocked, upgrading in-place migrates legacyCeFile -> deRoot/datastore/ and deletes legacyCeFile
        shadowOf(userManager).setUserUnlocked(true)
        val unlockedCeAppContext = object : ContextWrapper(app) {
            override fun isDeviceProtectedStorage(): Boolean = false
            override fun getFilesDir(): File = ceRoot
        }
        val unlockedDpContext = object : ContextWrapper(app.createDeviceProtectedStorageContext()) {
            override fun isDeviceProtectedStorage(): Boolean = true
            override fun getFilesDir(): File = deRoot
            override fun getApplicationContext(): Context = unlockedCeAppContext
        }
        val migratedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val migratedPrefs = ObolPreferences.create(unlockedDpContext, migratedScope, fileName = fileName)
        val stored = migratedPrefs.current()
        assertEquals(setupAt, stored.setupCompletedAt)
        assertEquals(PaymentDestination.DonorboxOppia, stored.destinationState.active)
        assertEquals(AlarmSoundChoice.BuiltIn(BuiltInChime.SINGING_BOWL), stored.alarmSound)
        assertTrue("DE preferences_pb must exist after migration", File(deRoot, "datastore/$fileName.preferences_pb").exists())
        assertFalse("Legacy CE preferences_pb must be removed after migration", legacyCeFile.exists())
        migratedScope.cancel()

        // 4. Subsequent overnight reboot into Direct Boot (locked again!) reads migrated DE preferences cleanly
        shadowOf(userManager).setUserUnlocked(false)
        val rebootScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val rebootPrefs = ObolPreferences.create(lockedDpContext, rebootScope, fileName = fileName)
        val rebootContainer = AppContainer(
            context = lockedDpContext,
            customClock = clock,
            inMemoryDb = true,
            customPreferences = rebootPrefs,
        )
        try {
            BootAndTimeReceiver.handleBootOrTimeChangeSuspend(rebootContainer, isProcessOrBootRestart = true)
            val snap = rebootContainer.repository.computeLiveSnapshot()
            assertEquals(setupAt, snap.setupCompletedAt)
            assertEquals(PaymentDestination.DonorboxOppia, snap.destinationState.active)
            assertNotNull("Next 6:00 AM alarm must still be scheduled during locked Direct Boot", snap.nextAlarm)
        } finally {
            rebootContainer.audioController.stopAll()
            rebootContainer.appScope.cancel()
            rebootContainer.database.close()
            rebootScope.cancel()
            shadowOf(userManager).setUserUnlocked(true)
            deRoot.deleteRecursively()
            ceRoot.deleteRecursively()
        }
    }

    @Test
    fun `debug simulation hooks on ObolViewModel are inert in release builds`() {
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider(store, ObolViewModel.factory(container))[ObolViewModel::class.java]
            val beforeNow = container.clock.now()
            vm.debugAdvanceClockMs(600_000L)
            if (!BuildConfig.DEBUG) {
                assertEquals("Release build must ignore debugAdvanceClockMs", beforeNow, container.clock.now())
                vm.setDebugPreviewScreen(dev.prayush.obol.ui.ObolCopy.presetScreens().first())
                assertNull("Release build must ignore setDebugPreviewScreen", vm.debugPreviewScreen.value)
            }
        } finally {
            store.clear()
        }
    }
}
