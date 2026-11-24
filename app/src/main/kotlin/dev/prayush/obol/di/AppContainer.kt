package dev.prayush.obol.di

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.prayush.obol.alarm.AlarmAudioController
import dev.prayush.obol.alarm.AlarmRingingService
import dev.prayush.obol.alarm.AlarmScheduler
import dev.prayush.obol.data.ObolBackupManager
import dev.prayush.obol.data.ObolDatabase
import dev.prayush.obol.data.ObolPreferences
import dev.prayush.obol.data.ObolRepository
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.time.ObolClock
import dev.prayush.obol.vision.RealVisionFrameDetector
import dev.prayush.obol.vision.VisionFrameDetector
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SystemObolClock(private val context: Context) : ObolClock {
    override fun now(): Instant = Instant.now()

    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun bootCount(): Long = runCatching {
        Settings.Global.getLong(context.contentResolver, Settings.Global.BOOT_COUNT, 1L)
    }.getOrDefault(1L)

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

class ControllableObolClock(
    private val delegate: ObolClock,
) : ObolClock {
    private val offsetMs = AtomicLong(0L)
    private val bootOffset = AtomicLong(0L)

    override fun now(): Instant = delegate.now().plusMillis(offsetMs.get())

    override fun zone(): ZoneId = delegate.zone()

    override fun bootCount(): Long = delegate.bootCount() + bootOffset.get()

    override fun elapsedMs(): Long = delegate.elapsedMs() + offsetMs.get()

    fun advanceByMs(deltaMs: Long) {
        offsetMs.addAndGet(deltaMs)
    }

    fun simulateReboot(downtimeMs: Long = 0L) {
        offsetMs.addAndGet(downtimeMs)
        bootOffset.incrementAndGet()
    }

    fun resetOffsets() {
        offsetMs.set(0L)
        bootOffset.set(0L)
    }
}

open class AppContainer(
    context: Context,
    customClock: ObolClock? = null,
    inMemoryDb: Boolean = false,
    customPreferences: ObolPreferences? = null,
) {
    val appContext: Context = context.applicationContext
    val deviceProtectedContext: Context =
        if (appContext.isDeviceProtectedStorage) {
            appContext
        } else {
            appContext.createDeviceProtectedStorageContext()
        }

    private val firstProcessReconcileFlag = AtomicBoolean(true)

    fun consumeFirstProcessReconcileFlag(): Boolean = firstProcessReconcileFlag.getAndSet(false)

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controllableClock: ControllableObolClock =
        ControllableObolClock(customClock ?: SystemObolClock(deviceProtectedContext))

    open val clock: ObolClock
        get() = controllableClock

    val database: ObolDatabase by lazy {
        ObolDatabase.create(deviceProtectedContext, inMemory = inMemoryDb)
    }

    val preferences: ObolPreferences by lazy {
        customPreferences ?: ObolPreferences.create(deviceProtectedContext, appScope)
    }

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(deviceProtectedContext, clock)
    }

    val audioController: AlarmAudioController by lazy {
        AlarmAudioController(
            context = deviceProtectedContext,
            clock = clock,
            mediaContext = appContext,
        ).also { controller ->
            appScope.launch {
                var firstEmission = true
                preferences.preferencesFlow.collect { prefs ->
                    val skipDefaultClobber = firstEmission &&
                        prefs.alarmSound == AlarmSoundChoice.Default &&
                        controller.configuredSound != AlarmSoundChoice.Default
                    firstEmission = false
                    if (!skipDefaultClobber) {
                        controller.setAlarmSoundChoice(prefs.alarmSound)
                    }
                }
            }
        }
    }

    val visionDetector: VisionFrameDetector by lazy {
        RealVisionFrameDetector(deviceProtectedContext)
    }

    val backupManager: ObolBackupManager by lazy {
        ObolBackupManager(
            appContext = appContext,
            dpContext = deviceProtectedContext,
            repository = repository,
            preferences = preferences,
            clock = clock,
            scope = appScope,
        )
    }

    val repository: ObolRepository by lazy {
        ObolRepository(
            dao = database.obolDao(),
            preferences = preferences,
            clock = clock,
            scope = appScope,
            onScheduleRefreshNeeded = {
                appScope.launch {
                    if (!database.isOpen) return@launch
                    val snap = runCatching { repository.computeLiveSnapshot() }.getOrNull() ?: return@launch
                    alarmScheduler.syncWithSnapshot(snap)
                    if (snap.activeMorning?.requiresAlarmService == true) {
                        AlarmRingingService.start(deviceProtectedContext)
                    }
                }
            },
            onBackupDataChanged = {
                backupManager.scheduleAutoBackup()
            },
        )
    }
}
