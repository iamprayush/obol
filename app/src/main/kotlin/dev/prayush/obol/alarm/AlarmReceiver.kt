package dev.prayush.obol.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.prayush.obol.ObolApplication
import dev.prayush.obol.ui.MainActivity
import java.time.Instant
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? ObolApplication ?: return
        val container = app.container
        val pendingResult = goAsync()

        container.appScope.launch {
            try {
                handleIntentSuspend(context, intent, container)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        suspend fun handleIntentSuspend(
            context: Context,
            intent: Intent?,
            container: dev.prayush.obol.di.AppContainer,
        ) {
            when (intent?.action) {
                AlarmScheduler.ACTION_ALARM_FIRE -> {
                    container.consumeFirstProcessReconcileFlag()
                    val scheduledMs = intent.getLongExtra(
                        AlarmScheduler.EXTRA_SCHEDULED_AT_EPOCH_MS,
                        container.clock.now().toEpochMilli(),
                    )
                    container.repository.onAlarmFired(Instant.ofEpochMilli(scheduledMs))
                }
                AlarmScheduler.ACTION_WAKE_DEADLINE -> {
                    val coldStart = container.consumeFirstProcessReconcileFlag()
                    container.repository.reconcile(isProcessOrBootRestart = coldStart)
                }
                AlarmScheduler.ACTION_BOOT_RESUME -> {
                    container.consumeFirstProcessReconcileFlag()
                    container.repository.reconcile(isProcessOrBootRestart = true)
                }
            }

            val snap = container.repository.computeLiveSnapshot()
            container.alarmScheduler.syncWithSnapshot(snap)

            val active = snap.activeMorning
            if (active != null && active.requiresAlarmService) {
                AlarmRingingService.start(context)
                runCatching {
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(activityIntent)
                }
            }
        }
    }
}
