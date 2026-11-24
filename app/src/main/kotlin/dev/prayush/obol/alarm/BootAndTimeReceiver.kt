package dev.prayush.obol.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.prayush.obol.ObolApplication
import kotlinx.coroutines.launch

class BootAndTimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val app = context.applicationContext as? ObolApplication ?: return
        val container = app.container
        val pendingResult = goAsync()

        container.appScope.launch {
            try {
                val isRestart = action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                    action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED
                handleBootOrTimeChangeSuspend(container, isProcessOrBootRestart = isRestart)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )

        suspend fun handleBootOrTimeChangeSuspend(
            container: dev.prayush.obol.di.AppContainer,
            isProcessOrBootRestart: Boolean = true,
        ) {
            if (isProcessOrBootRestart) {
                container.consumeFirstProcessReconcileFlag()
            }
            val result = container.repository.reconcile(isProcessOrBootRestart = isProcessOrBootRestart)
            container.alarmScheduler.syncWithReconcileResult(result)
            if (result.needsImmediateAlarmResume) {
                // Schedule an immediate setAlarmClock trigger so AlarmRingingService launches under REASON_ALARM_MANAGER
                container.alarmScheduler.scheduleImmediateBootRecovery()
            }
            runCatching {
                container.backupManager.flushIfDirty()
            }
        }
    }
}
