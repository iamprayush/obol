package dev.prayush.obol.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.prayush.obol.ObolApplication
import dev.prayush.obol.R
import dev.prayush.obol.di.AppContainer
import dev.prayush.obol.domain.model.DerivedMorning
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.ui.MainActivity
import dev.prayush.obol.ui.ObolCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AlarmRingingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickCounter: Long = 0L
    private var lastAlertSignature: String? = null

    var wakeLockAcquireCount: Int = 0
        private set

    private val container: AppContainer
        get() = (application as ObolApplication).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
        acquireOrRenewWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            container.audioController.stopAll()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val snap = container.repository.currentSnapshotNow()
        if (snap.isLoaded) {
            container.audioController.setAlarmSoundChoice(snap.alarmSound)
        }
        val initialSig = alertSignatureFor(snap.activeMorning)
        val shouldAlertInitial = initialSig != null && initialSig != lastAlertSignature
        if (initialSig != null) {
            lastAlertSignature = initialSig
        }
        val initialNotif = buildNotification(snap.activeMorning, alertNow = shouldAlertInitial)
        startForegroundSafe(initialNotif)
        acquireOrRenewWakeLock()
        container.audioController.updateForMorning(snap.activeMorning)

        if (tickerJob?.isActive != true) {
            tickerJob = serviceScope.launch {
                while (isActive) {
                    val keepAlive = tickOnce()
                    if (!keepAlive) break
                    delay(1_000L)
                }
            }
        }
        return START_STICKY
    }

    suspend fun tickOnce(): Boolean {
        tickCounter += 1L
        if (tickCounter % 60L == 0L) {
            acquireOrRenewWakeLock()
        }

        val repo = container.repository
        repo.notifyClockTick()
        val emittedDue = repo.advanceDueSystemEvents()
        val liveSnap = repo.computeLiveSnapshot()
        val active = liveSnap.activeMorning

        container.alarmScheduler.syncWithSnapshot(liveSnap)
        container.audioController.setAlarmSoundChoice(liveSnap.alarmSound)
        container.audioController.updateForMorning(active)

        if (active == null || !active.requiresAlarmService) {
            container.audioController.stopAll()
            releaseWakeLock()
            lastAlertSignature = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }

        val shouldWakeScreen = emittedDue.any {
            it is MorningEvent.ReRang
        }
        if (shouldWakeScreen) {
            runCatching {
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(activityIntent)
            }
        }

        val sig = alertSignatureFor(active)
        val alertNow = (sig != null && sig != lastAlertSignature) || shouldWakeScreen
        lastAlertSignature = sig

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(active, alertNow = alertNow))
        return true
    }

    fun acquireOrRenewWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "obol:morning_wakelock",
        ).apply {
            setReferenceCounted(false)
            wakeLock = this
        }
        lock.acquire(WAKE_LOCK_LEASE_MS)
        wakeLockAcquireCount += 1
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun startForegroundSafe(notification: Notification) {
        val fgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        runCatching {
            startForeground(NOTIFICATION_ID, notification, fgsTypes)
        }.recoverCatching {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        }.recoverCatching {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val alarmChan = NotificationChannel(
            CHANNEL_ALARM,
            "Obol Alarm",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Full-screen morning alarm and live rupee meter"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val quietChan = NotificationChannel(
            CHANNEL_STAY_UP,
            "Obol Snooze",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Silent snooze countdown while the meter runs"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alarmChan)
        nm.createNotificationChannel(quietChan)
    }

    private fun isQuietPhase(phase: MorningPhase?): Boolean =
        phase == MorningPhase.SNOOZED ||
            phase == MorningPhase.POST_CHECK_PAY_OR_ZERO

    private fun alertSignatureFor(morning: DerivedMorning?): String? {
        if (morning == null || !morning.requiresAlarmService || isQuietPhase(morning.phase)) return null
        return "${morning.header.morningId}:${morning.phase}:${morning.reRingCount}"
    }

    private fun buildNotification(
        morning: DerivedMorning?,
        alertNow: Boolean = false,
    ): Notification {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this,
            2001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isQuietWait = isQuietPhase(morning?.phase)

        val channelId = if (isQuietWait) CHANNEL_STAY_UP else CHANNEL_ALARM
        val title = when (morning?.phase) {
            MorningPhase.FREE_WINDOW -> "Obol · Free for ${ObolCopy.formatCountdown(morning.countdownSeconds ?: 0L)}"
            MorningPhase.SNOOZED -> "Obol · Quiet for ${ObolCopy.formatCountdown(morning.countdownSeconds ?: 0L)}"
            null -> "Obol"
            else -> "Obol · ${morning.displayPaise.formatRupees()} so far"
        }
        val body = when (morning?.phase) {
            MorningPhase.SNOOZED -> "Snoozed. Meter remains active."
            MorningPhase.BRUSHING -> "Verify 10 seconds of brushing to stop the meter."
            else -> "Open Obol to complete morning verification."
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(!alertNow)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPi)

        if (!isQuietWait) {
            builder
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(fullScreenPi, true)
        } else {
            builder
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }

        return builder.build()
    }

    override fun onDestroy() {
        container.audioController.stopAll()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ALARM = "obol_alarm"
        const val CHANNEL_STAY_UP = "obol_stay_up"
        const val NOTIFICATION_ID = 4201
        const val WAKE_LOCK_LEASE_MS = 10 * 60_000L
        const val ACTION_START_OR_SYNC = "dev.prayush.obol.action.START_OR_SYNC_SERVICE"
        const val ACTION_STOP_SERVICE = "dev.prayush.obol.action.STOP_SERVICE"

        fun start(context: Context) {
            val dpContext = if (context.isDeviceProtectedStorage) {
                context
            } else {
                context.createDeviceProtectedStorageContext()
            }
            val intent = Intent(dpContext, AlarmRingingService::class.java).apply {
                action = ACTION_START_OR_SYNC
            }
            runCatching {
                ContextCompat.startForegroundService(dpContext, intent)
            }
        }
    }
}
