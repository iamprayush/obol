package dev.prayush.obol.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.prayush.obol.data.AppSnapshot
import dev.prayush.obol.data.ReconcileResult
import dev.prayush.obol.domain.model.WakeDeadline
import dev.prayush.obol.domain.policy.ScheduledAlarmInstance
import dev.prayush.obol.domain.time.ObolClock
import dev.prayush.obol.ui.MainActivity

class AlarmScheduler(
    context: Context,
    private val clock: ObolClock,
) {
    private val dpContext: Context =
        if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

    private val alarmManager: AlarmManager =
        dpContext.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun syncWithSnapshot(snapshot: AppSnapshot) {
        val active = snapshot.activeMorning?.takeIf { it.isActivePostFire }
        syncAlarms(
            nextScheduled = snapshot.nextAlarm,
            wakeDeadline = active?.nextWakeDeadline,
        )
    }

    fun syncWithReconcileResult(result: ReconcileResult) {
        syncAlarms(
            nextScheduled = result.nextScheduledAlarm,
            wakeDeadline = result.nextWakeDeadline,
        )
    }

    fun syncAlarms(
        nextScheduled: ScheduledAlarmInstance?,
        wakeDeadline: WakeDeadline?,
    ) {
        if (nextScheduled != null) {
            val fireIntent = Intent(dpContext, AlarmReceiver::class.java).apply {
                action = ACTION_ALARM_FIRE
                putExtra(EXTRA_SCHEDULED_AT_EPOCH_MS, nextScheduled.scheduledAt.toEpochMilli())
            }
            val pi = PendingIntent.getBroadcast(
                dpContext,
                REQ_MORNING_FIRE,
                fireIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setExactAlarmClockSafe(nextScheduled.scheduledAt.toEpochMilli(), pi)
        } else {
            cancelBroadcast(REQ_MORNING_FIRE, ACTION_ALARM_FIRE)
        }

        if (wakeDeadline != null) {
            val deadlineIntent = Intent(dpContext, AlarmReceiver::class.java).apply {
                action = ACTION_WAKE_DEADLINE
                putExtra(EXTRA_DEADLINE_KIND, wakeDeadline.kind.name)
            }
            val pi = PendingIntent.getBroadcast(
                dpContext,
                REQ_WAKE_DEADLINE,
                deadlineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAt = maxOf(clock.now().toEpochMilli() + 500L, wakeDeadline.at.toEpochMilli())
            setExactAlarmClockSafe(triggerAt, pi)
        } else {
            cancelBroadcast(REQ_WAKE_DEADLINE, ACTION_WAKE_DEADLINE)
        }
    }

    fun scheduleImmediateBootRecovery() {
        val recoveryIntent = Intent(dpContext, AlarmReceiver::class.java).apply {
            action = ACTION_BOOT_RESUME
        }
        val pi = PendingIntent.getBroadcast(
            dpContext,
            REQ_BOOT_RECOVERY,
            recoveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = clock.now().toEpochMilli() + 1_000L
        setExactAlarmClockSafe(triggerAt, pi)
    }

    private fun setExactAlarmClockSafe(triggerAtMs: Long, operation: PendingIntent) {
        val showIntent = PendingIntent.getActivity(
            dpContext,
            REQ_SHOW_UI,
            Intent(dpContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMs, showIntent),
                operation,
            )
        }
    }

    private fun cancelBroadcast(requestCode: Int, actionName: String) {
        val intent = Intent(dpContext, AlarmReceiver::class.java).apply {
            action = actionName
        }
        val existing = PendingIntent.getBroadcast(
            dpContext,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) {
            runCatching { alarmManager.cancel(existing) }
            existing.cancel()
        }
    }

    companion object {
        const val ACTION_ALARM_FIRE = "dev.prayush.obol.action.ALARM_FIRE"
        const val ACTION_WAKE_DEADLINE = "dev.prayush.obol.action.WAKE_DEADLINE"
        const val ACTION_BOOT_RESUME = "dev.prayush.obol.action.BOOT_RESUME"

        const val EXTRA_SCHEDULED_AT_EPOCH_MS = "extra_scheduled_at_epoch_ms"
        const val EXTRA_DEADLINE_KIND = "extra_deadline_kind"

        const val REQ_MORNING_FIRE = 1001
        const val REQ_WAKE_DEADLINE = 1002
        const val REQ_BOOT_RECOVERY = 1003
        const val REQ_SHOW_UI = 1004
    }
}
