package dev.prayush.obol.domain.model

import dev.prayush.obol.domain.serialization.InstantEpochMsSerializer
import dev.prayush.obol.domain.serialization.LocalDateIsoSerializer
import dev.prayush.obol.domain.time.TimePoint
import dev.prayush.obol.domain.time.deltaMs
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MorningHeader(
    val morningId: String,
    @Serializable(with = LocalDateIsoSerializer::class)
    val calendarDate: LocalDate,
    @Serializable(with = InstantEpochMsSerializer::class)
    val scheduledAt: Instant,
    val isTrialDay: Boolean = false,
    val isGraceNight: Boolean,
    val hadPriorDebt: Boolean,
    val priorMonthChargedPaise: Paise,
    val ratePaisePerMin: Long = ObolConstants.RATE_PAISE_PER_MIN,
    val morningCapPaise: Paise = ObolConstants.MORNING_CAP_PAISE,
    val monthlyCapPaise: Paise = ObolConstants.MONTHLY_CAP_PAISE,
)

@Serializable
sealed interface MorningEvent {
    @Serializable
    @SerialName("AlarmFired")
    data object AlarmFired : MorningEvent

    @Serializable
    @SerialName("Snoozed")
    data class Snoozed(
        val cycle: Int,
        val startDisplayPaise: Paise,
    ) : MorningEvent

    @Serializable
    @SerialName("ReRang")
    data class ReRang(
        val cycle: Int,
    ) : MorningEvent

    @Serializable
    @SerialName("GraceNightDismissed")
    data object GraceNightDismissed : MorningEvent

    @Serializable
    @SerialName("CameraOpened")
    data object CameraOpened : MorningEvent

    @Serializable
    @SerialName("CameraClosed")
    data object CameraClosed : MorningEvent

    @Serializable
    @SerialName("BrushingPausedMeter")
    data object BrushingPausedMeter : MorningEvent

    @Serializable
    @SerialName("BrushingSightLost")
    data object BrushingSightLost : MorningEvent

    @Serializable
    @SerialName("BrushCompleted")
    data object BrushCompleted : MorningEvent

    @Serializable
    @SerialName("OverrideOpened")
    data object OverrideOpened : MorningEvent

    @Serializable
    @SerialName("OverrideClosed")
    data object OverrideClosed : MorningEvent

    @Serializable
    @SerialName("OverrideAccepted")
    data class OverrideAccepted(
        val feePaise: Paise = ObolConstants.OVERRIDE_FEE_PAISE,
    ) : MorningEvent

    @Serializable
    @SerialName("StayUpScheduled")
    data class StayUpScheduled(
        val round: Int,
        @Serializable(with = InstantEpochMsSerializer::class)
        val ringAt: Instant,
    ) : MorningEvent

    @Serializable
    @SerialName("StayUpEarlyOpened")
    data class StayUpEarlyOpened(
        val round: Int,
    ) : MorningEvent

    @Serializable
    @SerialName("StayUpEarlyCancelled")
    data class StayUpEarlyCancelled(
        val round: Int,
    ) : MorningEvent

    @Serializable
    @SerialName("StayUpRang")
    data class StayUpRang(
        val round: Int,
    ) : MorningEvent

    @Serializable
    @SerialName("StayUpFacePassed")
    data class StayUpFacePassed(
        val round: Int,
    ) : MorningEvent

    @Serializable
    @SerialName("RingTimedOut")
    data class RingTimedOut(
        val episode: Int,
    ) : MorningEvent

    @Serializable
    @SerialName("PaymentRecorded")
    data class PaymentRecorded(
        val amountPaise: Paise,
        val railId: String,
        val ref: String,
    ) : MorningEvent

    @Serializable
    @SerialName("PaymentPendingSubmitted")
    data class PaymentPendingSubmitted(
        val amountPaise: Paise,
        val ref: String,
    ) : MorningEvent

    @Serializable
    @SerialName("PaymentFailed")
    data class PaymentFailed(
        val reason: String = "",
    ) : MorningEvent

    @Serializable
    @SerialName("PaymentDeferred")
    data object PaymentDeferred : MorningEvent

    @Serializable
    @SerialName("MorningDismissed")
    data object MorningDismissed : MorningEvent

    @Serializable
    @SerialName("Cancelled")
    data class Cancelled(
        val reason: String = "MISSED_ALARM",
    ) : MorningEvent
}

@Serializable
data class MorningEventRecord(
    val seq: Int,
    val point: TimePoint,
    val event: MorningEvent,
) {
    val at: Instant get() = point.at
    val bootCount: Long get() = point.bootCount
    val elapsedMs: Long get() = point.elapsedMs
}

enum class MorningPhase {
    IDLE_PRE_FIRE,
    GRACE_NIGHT_RING,
    FREE_WINDOW,
    METERING,
    SNOOZED,
    CAPPED_RINGING,
    BRUSHING,
    POST_CHECK_PAY_OR_ZERO,
    CLOSED_DONE,
    CLOSED_UNPAID,
    CANCELLED,
}

enum class WakeDeadlineKind {
    SNOOZE_RE_RING,
    RING_TIMEOUT,
}

data class WakeDeadline(
    val at: Instant,
    val kind: WakeDeadlineKind,
    val roundOrCycleOrEpisode: Int,
    val isDue: Boolean = false,
    val overdueMs: Long = 0L,
)

enum class BillRowKind {
    ALARM_RANG,
    SNOOZED,
    FREE_WINDOW_ENDED,
    RANG_AGAIN,
    RANG_AGAIN_AND_SNOOZED,
    BRUSHING_PAUSED_METER,
    BRUSHING_RESUMED_METER,
    BRUSH_COMPLETED,
    OVERRIDE_ACCEPTED,
    CAP_REACHED,
    PAID,
    PAYMENT_SUBMITTED,
    STAY_UP_PASSED,
    STAY_UP_MISSED,
    RING_TIMED_OUT,
    GRACE_NIGHT_DISMISSED,
    CANCELLED,
}

data class BillRow(
    val at: Instant,
    val kind: BillRowKind,
    val amountPaise: Paise? = null,
)

data class DerivedMorning(
    val header: MorningHeader,
    val phase: MorningPhase,
    val displayPaise: Paise,
    val morningChargedPaise: Paise,
    val paidThisMorningPaise: Paise,
    val morningUnpaidPaise: Paise,
    val priorMorningsUnpaidPaise: Paise,
    val totalPayableNowPaise: Paise,
    val effectiveCapPaise: Paise,
    val skyHeatFraction: Float,
    val countdownSeconds: Long?,
    val isOverrideUnlocked: Boolean,
    val nextWakeDeadline: WakeDeadline?,
    val billRows: List<BillRow>,
    val firedAt: Instant?,
    val brushStartedAt: Instant?,
    val brushCompletedAt: Instant?,
    val firstPaidAt: Instant?,
    val snoozeCycle: Int,
    val snoozeEndAt: Instant?,
    val lastNapCostPaise: Paise,
    val reRingCount: Int,
    val isBrushingSeen: Boolean,
    val isInOverrideScreen: Boolean,
    val isPaymentFailed: Boolean,
    val pendingSubmittedRef: String?,
    val pendingSubmittedPaise: Paise,
    val capRingUntilAt: Instant?,
    val isSummaryDismissed: Boolean,
    val upDurationMinutes: Long?,
    val verifiedBrushMs: Long = 0L,
    val isWithinFreeWindow: Boolean = false,
    val eventCount: Int = 0,
    val evaluatedAtPoint: TimePoint? = null,
    val freeWindowRemainingMs: Long = 0L,
) {
    val isClosedTerminal: Boolean
        get() = phase == MorningPhase.CLOSED_DONE ||
            phase == MorningPhase.CLOSED_UNPAID ||
            phase == MorningPhase.CANCELLED

    val isActivePostFire: Boolean
        get() = firedAt != null && !isClosedTerminal

    val requiresAlarmService: Boolean
        get() = isActivePostFire && phase != MorningPhase.POST_CHECK_PAY_OR_ZERO

    fun isWithinFreeWindowAt(point: TimePoint): Boolean {
        if (!isWithinFreeWindow) return false
        if (header.isGraceNight) return true
        val anchor = evaluatedAtPoint ?: return isWithinFreeWindow
        return deltaMs(anchor, point) < freeWindowRemainingMs
    }
}
