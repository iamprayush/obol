package dev.prayush.obol.ui

import dev.prayush.obol.data.AppSnapshot
import dev.prayush.obol.data.OnboardingStep
import dev.prayush.obol.domain.model.BillRow
import dev.prayush.obol.domain.model.BillRowKind
import dev.prayush.obol.domain.model.DerivedMorning
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.model.sumPaise
import dev.prayush.obol.domain.policy.AlarmSchedulePolicy
import dev.prayush.obol.domain.policy.AlarmSlot
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.time.localDateIn
import dev.prayush.obol.domain.time.localTimeIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToLong

enum class VisionFailureReason {
    NONE,
    ACQUIRING,
    TOO_DARK,
    NO_FACE,
    NO_TOOTHBRUSH,
    BRUSH_AWAY_FROM_MOUTH,
    CAMERA_ERROR,
}

data class PermissionGrantState(
    val alarmsAllowed: Boolean = true,
    val cameraAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val batteryUnrestricted: Boolean = false,
) {
    val allowedCount: Int
        get() = listOf(alarmsAllowed, cameraAllowed, notificationsAllowed, batteryUnrestricted).count { it }

    val remainingCount: Int
        get() = 4 - allowedCount

    val allGranted: Boolean
        get() = remainingCount == 0
}

data class LiveVisionUiState(
    val accumulatedBrushMs: Long = 0L,
    val isWindowPassing: Boolean? = null,
    val failureReason: VisionFailureReason = VisionFailureReason.NO_TOOTHBRUSH,
    val cameraErrorUnlockedOverride: Boolean = false,
    val overrideTypedText: String = "",
)

sealed interface CalmOverlay {
    data object None : CalmOverlay
    data object Alarms : CalmOverlay
    data class History(val showEarlierMonths: Boolean = false) : CalmOverlay
    data class FullBill(
        val morningId: String,
        val returnToEarlier: Boolean = false,
    ) : CalmOverlay
    data object Settings : CalmOverlay
}

sealed interface ObolScreenModel {
    val screenId: String

    data class Welcome(
        override val screenId: String = "01-welcome",
    ) : ObolScreenModel

    data class Permissions(
        override val screenId: String = "02-permissions",
        val grants: PermissionGrantState,
    ) : ObolScreenModel

    data class Alarms(
        override val screenId: String = "03-alarms",
        val isOnboarding: Boolean,
        val rows: List<AlarmRowUi>,
    ) : ObolScreenModel

    data class Home(
        override val screenId: String,
        val statusLeft: String,
        val label: String,
        val heroText: String,
        val dryLine: String,
        val subtitle: String?,
        val unpaidAmountText: String? = null,
        val unpaidSuffix: String = " unpaid",
        val unpaidNote: String? = null,
        val pendingUpiBanner: String? = null,
        val primaryButton: String? = null,
        val nightOffText: String? = null,
        val nightOffTrailing: String? = null,
        val nightOffTaken: Boolean = false,
        val nightOffEnabled: Boolean = true,
        val bottomNote: String? = null,
        val bottomNoteIsLink: Boolean = false,
        val bottomNoteTopDp: Float = 770f,
        val isPaused: Boolean = false,
    ) : ObolScreenModel

    data class MorningMeter(
        override val screenId: String,
        val heatFraction: Float,
        val isCalmSun: Boolean,
        val statusLeft: String,
        val statusRight: String,
        val label: String,
        val heroCountdown: String? = null,
        val subHeroLabel: String? = null,
        val dryLine: String,
        val dryLineTopDp: Float,
        val sunAmount: String,
        val sunLabel: String,
        val primaryButton: String,
        val secondaryButton: String? = null,
        val bottomNote: String,
        val bottomNoteTopDp: Float = 770f,
        val isGraceNightRing: Boolean = false,
    ) : ObolScreenModel

    data class BrushingCamera(
        override val screenId: String,
        val heatFraction: Float,
        val statusLeft: String,
        val statusRight: String,
        val label: String,
        val heroRemaining: String,
        val heroDimmed: Boolean,
        val ringProgress: Float,
        val ringActive: Boolean,
        val showBrushHint: Boolean,
        val dotLit: Boolean,
        val statusBanner: String,
        val dryLine: String,
        val showOverrideLink: Boolean,
    ) : ObolScreenModel

    data class OverrideTyping(
        override val screenId: String = "16-override",
        val heatFraction: Float,
        val statusLeft: String,
        val statusRight: String,
        val targetSentence: String = ObolConstants.OVERRIDE_TARGET_SENTENCE,
        val typedText: String,
        val primaryButton: String = "Stop the meter for ₹20",
        val canSubmit: Boolean,
    ) : ObolScreenModel

    data class PostBrushSettle(
        override val screenId: String,
        val heatFraction: Float,
        val isCalmSun: Boolean,
        val statusLeft: String,
        val statusRight: String,
        val label: String,
        val dryLine: String,
        val sunAmount: String,
        val sunLabel: String,
        val primaryButton: String,
        val secondaryButton: String?,
        val bottomNote: String,
        val bottomNoteTopDp: Float,
        val isNothingOwed: Boolean,
    ) : ObolScreenModel

    data class ClosedSummary(
        override val screenId: String,
        val statusLeft: String,
        val showTopNav: Boolean = true,
        val heroAmount: String,
        val dryLine: String,
        val subtitle: String,
        val showFullBillButton: Boolean,
        val morningId: String?,
    ) : ObolScreenModel

    data class HistoryList(
        override val screenId: String = "25-history",
        val monthTitle: String,
        val monthTotalText: String,
        val capSubtext: String = "of the ₹6,000 monthly cap",
        val dryLine: String = "Avg 11 min · worst 16 min.",
        val rows: List<HistoryRowUi>,
        val hasEarlier: Boolean,
        val showingAllTime: Boolean = false,
    ) : ObolScreenModel

    data class FullBill(
        override val screenId: String = "26-bill",
        val backLabel: String,
        val dateLabel: String,
        val heroTotal: String,
        val dryLine: String = "Every rupee, itemised.",
        val rows: List<BillItemUi>,
    ) : ObolScreenModel

    data class Settings(
        override val screenId: String = "27-settings",
        val alarmSoundLabel: String = "Marimba",
        val destinationLabel: String,
        val destinationIsUnset: Boolean,
        val backupLabel: String = "Not set",
        val backupNeedsAttention: Boolean = false,
        val pauseToggleOn: Boolean,
        val pauseNote: String,
    ) : ObolScreenModel
}

data class AlarmRowUi(
    val dayOfWeek: DayOfWeek,
    val dayName: String,
    val timeText: String,
    val enabled: Boolean,
    val hasPendingDisable: Boolean = false,
    val pendingCaption: String? = null,
    val hour: Int = 6,
    val minute: Int = 10,
)

data class HistoryRowUi(
    val morningId: String,
    val dateShort: String,
    val summary: String,
    val amountText: String,
    val isFreeDimmed: Boolean,
)

data class BillItemUi(
    val timeText: String,
    val eventText: String,
    val amountText: String = "",
)

object ObolCopy {

    private val STATUS_DAY_TIME_FMT = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val DATE_LABEL_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val HIST_ROW_DATE_FMT = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)
    private val HIST_ALL_TIME_DATE_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val HIST_ALL_TIME_YEAR_FMT = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH)
    private val MONTH_NAME_FMT = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

    fun formatStatusTime(instant: Instant, zone: ZoneId): String =
        instant.atZone(zone).format(STATUS_DAY_TIME_FMT)

    fun formatClockShort(time: LocalTime): String =
        "%d:%02d".format(Locale.US, time.hour, time.minute)

    fun formatClockShort(instant: Instant, zone: ZoneId): String =
        formatClockShort(instant.localTimeIn(zone))

    fun formatCountdown(seconds: Long): String {
        val clamped = seconds.coerceAtLeast(0L)
        val mins = clamped / 60L
        val secs = clamped % 60L
        return "%d:%02d".format(Locale.US, mins, secs)
    }

    fun heroSizeForText(text: String): Float = when {
        text.startsWith("₹") -> 132f
        text.length >= 5 -> 122f
        else -> 150f
    }

    fun mapBillRowDescription(kind: BillRowKind): String = when (kind) {
        BillRowKind.ALARM_RANG -> "Alarm rang"
        BillRowKind.SNOOZED -> "Snoozed"
        BillRowKind.FREE_WINDOW_ENDED -> "Free minutes over. Meter on"
        BillRowKind.RANG_AGAIN -> "Rang again"
        BillRowKind.RANG_AGAIN_AND_SNOOZED -> "Rang again. Snoozed"
        BillRowKind.BRUSHING_PAUSED_METER -> "Brushing seen. Meter paused"
        BillRowKind.BRUSHING_RESUMED_METER -> "Brushing lost. Meter on"
        BillRowKind.BRUSH_COMPLETED -> "Ten seconds done. Meter off"
        BillRowKind.OVERRIDE_ACCEPTED -> "Override typed (+₹20). Meter off"
        BillRowKind.CAP_REACHED -> "Cap reached. Charging stopped"
        BillRowKind.PAID -> "Paid"
        BillRowKind.PAYMENT_SUBMITTED -> "UPI transfer submitted"
        BillRowKind.STAY_UP_PASSED -> "Face check passed"
        BillRowKind.STAY_UP_MISSED -> "Face check missed. Meter on"
        BillRowKind.RING_TIMED_OUT -> "Ringing stopped after 60 min"
        BillRowKind.GRACE_NIGHT_DISMISSED -> "Night off dismissed"
        BillRowKind.CANCELLED -> "Alarm didn't ring"
    }

    fun brushingFailureBanner(reason: VisionFailureReason, currentRupeesText: String): String = when (reason) {
        VisionFailureReason.ACQUIRING -> "Looking for brushing..."
        VisionFailureReason.TOO_DARK -> "Too dark. Turn on a light. $currentRupeesText so far."
        VisionFailureReason.NO_FACE -> "Can't see a face. $currentRupeesText so far."
        VisionFailureReason.NO_TOOTHBRUSH, VisionFailureReason.NONE, VisionFailureReason.CAMERA_ERROR ->
            "Can't see a toothbrush. $currentRupeesText so far."
        VisionFailureReason.BRUSH_AWAY_FROM_MOUTH ->
            "The brush isn't at your mouth. $currentRupeesText so far."
    }

    fun resolveScreen(
        snapshot: AppSnapshot,
        overlay: CalmOverlay,
        permissions: PermissionGrantState,
        vision: LiveVisionUiState,
        isBackupSyncing: Boolean = false,
    ): ObolScreenModel {
        val zone = snapshot.zone
        val now = snapshot.now

        if (snapshot.onboardingStep != OnboardingStep.COMPLETE) {
            return when (snapshot.onboardingStep) {
                OnboardingStep.WELCOME -> ObolScreenModel.Welcome()
                OnboardingStep.PERMISSIONS -> ObolScreenModel.Permissions(grants = permissions)
                OnboardingStep.ALARMS -> ObolScreenModel.Alarms(
                    isOnboarding = true,
                    rows = mapAlarmRows(snapshot.alarmSlots, now, zone),
                )
                OnboardingStep.COMPLETE -> ObolScreenModel.Welcome()
            }
        }

        val active = snapshot.activeMorning
        val canShowOverlay = active == null ||
            active.phase == MorningPhase.CLOSED_DONE ||
            active.phase == MorningPhase.CANCELLED

        if (canShowOverlay && overlay != CalmOverlay.None) {
            return when (overlay) {
                CalmOverlay.Alarms -> ObolScreenModel.Alarms(
                    isOnboarding = false,
                    rows = mapAlarmRows(snapshot.alarmSlots, now, zone),
                )
                is CalmOverlay.History -> buildHistoryScreen(snapshot, overlay.showEarlierMonths)
                is CalmOverlay.FullBill -> buildFullBillScreen(
                    snapshot = snapshot,
                    morningId = overlay.morningId,
                    returnToEarlier = overlay.returnToEarlier,
                )
                CalmOverlay.Settings -> buildSettingsScreen(snapshot, isBackupSyncing = isBackupSyncing)
                CalmOverlay.None -> buildHomeScreen(snapshot)
            }
        }

        if (active == null) {
            return buildHomeScreen(snapshot)
        }

        return resolveMorningScreen(
            morning = active,
            snapshot = snapshot,
            vision = vision,
        )
    }

    private fun resolveMorningScreen(
        morning: DerivedMorning,
        snapshot: AppSnapshot,
        vision: LiveVisionUiState,
    ): ObolScreenModel {
        val zone = snapshot.zone
        val now = snapshot.now
        val statusLeft = formatStatusTime(now, zone)
        val rangSince = morning.firedAt?.let { "ringing since ${formatClockShort(it, zone)}" } ?: ""

        return when (morning.phase) {
            MorningPhase.GRACE_NIGHT_RING -> ObolScreenModel.MorningMeter(
                screenId = "08-alarm-free",
                heatFraction = 0f,
                isCalmSun = true,
                statusLeft = statusLeft,
                statusRight = rangSince,
                label = "Night off",
                heroCountdown = formatClockShort(morning.header.scheduledAt, zone),
                subHeroLabel = null,
                dryLine = "Morning. Go back to\nsleep if you like.",
                dryLineTopDp = 300f,
                sunAmount = "₹0",
                sunLabel = "free",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Still ₹0.",
                isGraceNightRing = true,
            )

            MorningPhase.FREE_WINDOW -> ObolScreenModel.MorningMeter(
                screenId = "08-alarm-free",
                heatFraction = 0f,
                isCalmSun = true,
                statusLeft = statusLeft,
                statusRight = rangSince,
                label = "Free for",
                heroCountdown = formatCountdown(morning.countdownSeconds ?: 0L),
                subHeroLabel = "then ₹20 a minute.",
                dryLine = "Take your time.\nIt's only money.",
                dryLineTopDp = 340f,
                sunAmount = "₹0",
                sunLabel = "so far",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Quieter. Not cheaper.",
            )

            MorningPhase.METERING -> {
                if (morning.reRingCount >= 1) {
                    ObolScreenModel.MorningMeter(
                        screenId = "11-ring-again",
                        heatFraction = morning.skyHeatFraction,
                        isCalmSun = false,
                        statusLeft = statusLeft,
                        statusRight = rangSince,
                        label = "Meter running. ₹20 a minute.",
                        dryLine = "Welcome back.\nThat nap cost ${morning.lastNapCostPaise.formatRupees()}.",
                        dryLineTopDp = 132f,
                        sunAmount = morning.displayPaise.formatRupees(),
                        sunLabel = "so far",
                        primaryButton = "I'm up",
                        secondaryButton = "Snooze 5 min",
                        bottomNote = "Quieter. Not cheaper.",
                    )
                } else if (morning.header.hadPriorDebt) {
                    ObolScreenModel.MorningMeter(
                        screenId = "09b-meter-no-free",
                        heatFraction = morning.skyHeatFraction,
                        isCalmSun = false,
                        statusLeft = statusLeft,
                        statusRight = rangSince,
                        label = "No free minutes. ${morning.priorMorningsUnpaidPaise.formatRupees()} still unpaid.",
                        dryLine = "No warm-up today.\nYou know why.",
                        dryLineTopDp = 132f,
                        sunAmount = morning.displayPaise.formatRupees(),
                        sunLabel = "so far",
                        primaryButton = "I'm up",
                        secondaryButton = "Snooze 5 min",
                        bottomNote = "Quieter. Not cheaper.",
                    )
                } else {
                    ObolScreenModel.MorningMeter(
                        screenId = "09-meter",
                        heatFraction = morning.skyHeatFraction,
                        isCalmSun = false,
                        statusLeft = statusLeft,
                        statusRight = rangSince,
                        label = "Meter running. ₹20 a minute.",
                        dryLine = "No rush.\nI bill by the minute.",
                        dryLineTopDp = 132f,
                        sunAmount = morning.displayPaise.formatRupees(),
                        sunLabel = "so far",
                        primaryButton = "I'm up",
                        secondaryButton = "Snooze 5 min",
                        bottomNote = "Quieter. Not cheaper.",
                    )
                }
            }

            MorningPhase.SNOOZED -> {
                val reRingText = morning.snoozeEndAt?.let { "rings again at ${formatClockShort(it, zone)}" } ?: ""
                val isCapped = !morning.header.isGraceNight && morning.displayPaise >= morning.effectiveCapPaise
                val calmSun = morning.displayPaise.isZero
                val untilText = morning.capRingUntilAt?.let { formatClockShort(it, zone) } ?: "later"
                ObolScreenModel.MorningMeter(
                    screenId = "10-snoozed",
                    heatFraction = morning.skyHeatFraction,
                    isCalmSun = calmSun,
                    statusLeft = statusLeft,
                    statusRight = reRingText,
                    label = "Quiet for",
                    heroCountdown = formatCountdown(morning.countdownSeconds ?: 0L),
                    dryLine = when {
                        morning.header.isGraceNight -> "Five quiet minutes.\nStill free."
                        isCapped -> "Charging has stopped."
                        else -> "Enjoy the silence.\nI'm still counting."
                    },
                    dryLineTopDp = 296f,
                    sunAmount = morning.displayPaise.formatRupees(),
                    sunLabel = when {
                        morning.header.isGraceNight -> "free"
                        isCapped -> "capped"
                        else -> "so far"
                    },
                    primaryButton = "I'm up",
                    secondaryButton = null,
                    bottomNote = when {
                        morning.header.isGraceNight -> "Still ₹0."
                        isCapped -> "Rings until $untilText."
                        else -> "The meter doesn't snooze."
                    },
                    bottomNoteTopDp = 712f,
                    isGraceNightRing = morning.header.isGraceNight,
                )
            }

            MorningPhase.CAPPED_RINGING -> {
                val untilText = morning.capRingUntilAt?.let { formatClockShort(it, zone) } ?: "later"
                val note = "Rings until $untilText. Then ${morning.displayPaise.formatRupees()} carries to tomorrow."
                ObolScreenModel.MorningMeter(
                    screenId = "12-capped",
                    heatFraction = morning.skyHeatFraction,
                    isCalmSun = false,
                    statusLeft = statusLeft,
                    statusRight = rangSince,
                    label = "Charging has stopped. Ringing has not.",
                    dryLine = "That's the cap. I'd take\nmore, but rules.",
                    dryLineTopDp = 132f,
                    sunAmount = morning.displayPaise.formatRupees(),
                    sunLabel = "capped",
                    primaryButton = "I'm up",
                    secondaryButton = "Snooze 5 min",
                    bottomNote = note,
                )
            }

            MorningPhase.BRUSHING -> {
                if (morning.isInOverrideScreen) {
                    ObolScreenModel.OverrideTyping(
                        heatFraction = morning.skyHeatFraction,
                        statusLeft = statusLeft,
                        statusRight = "${morning.displayPaise.formatRupees()} so far",
                        typedText = vision.overrideTypedText,
                        canSubmit = vision.overrideTypedText.trim() == ObolConstants.OVERRIDE_TARGET_SENTENCE,
                    )
                } else {
                    val remainingMs = (ObolConstants.BRUSH_TARGET_MS - vision.accumulatedBrushMs).coerceAtLeast(0L)
                    val remainingSec = (remainingMs + 999L) / 1_000L
                    val progress = (vision.accumulatedBrushMs.toFloat() / ObolConstants.BRUSH_TARGET_MS.toFloat())
                        .coerceIn(0f, 1f)
                    val isAcquiring = vision.failureReason == VisionFailureReason.ACQUIRING && morning.isWithinFreeWindow
                    if (morning.isBrushingSeen || isAcquiring) {
                        val activelyPassing = morning.isBrushingSeen && vision.isWindowPassing != false
                        ObolScreenModel.BrushingCamera(
                            screenId = "14-brushing",
                            heatFraction = morning.skyHeatFraction,
                            statusLeft = statusLeft,
                            statusRight = "meter paused",
                            label = "Keep brushing for",
                            heroRemaining = formatCountdown(remainingSec),
                            heroDimmed = false,
                            ringProgress = progress,
                            ringActive = activelyPassing,
                            showBrushHint = activelyPassing,
                            dotLit = activelyPassing,
                            statusBanner = if (activelyPassing) {
                                "Brushing seen. Meter paused at ${morning.displayPaise.formatRupees()}."
                            } else {
                                "Looking for brushing..."
                            },
                            dryLine = "Ten seconds. Don't tell\nyour dentist.",
                            showOverrideLink = false,
                        )
                    } else {
                        val unlockOverride = morning.isOverrideUnlocked || vision.cameraErrorUnlockedOverride
                        val lostReason = if (vision.failureReason == VisionFailureReason.ACQUIRING) {
                            VisionFailureReason.NO_TOOTHBRUSH
                        } else {
                            vision.failureReason
                        }
                        ObolScreenModel.BrushingCamera(
                            screenId = "15-brushing-lost",
                            heatFraction = morning.skyHeatFraction,
                            statusLeft = statusLeft,
                            statusRight = "meter running",
                            label = "Brushing left",
                            heroRemaining = formatCountdown(remainingSec),
                            heroDimmed = true,
                            ringProgress = progress,
                            ringActive = false,
                            showBrushHint = false,
                            dotLit = false,
                            statusBanner = brushingFailureBanner(
                                reason = lostReason,
                                currentRupeesText = morning.displayPaise.formatRupees(),
                            ),
                            dryLine = "I don't see a brush.\nI do see ₹20 a minute.",
                            showOverrideLink = unlockOverride,
                        )
                    }
                }
            }

            MorningPhase.POST_CHECK_PAY_OR_ZERO -> {
                val bStart = morning.brushStartedAt
                val bEnd = morning.brushCompletedAt
                val brushedRange = if (bStart != null && bEnd != null) {
                    val s = formatClockShort(bStart, zone)
                    val e = formatClockShort(bEnd, zone)
                    if (s == e) "brushed at $s" else "brushed $s to $e"
                } else {
                    "meter stopped"
                }

                if (morning.totalPayableNowPaise.isZero) {
                    val topLabel = if (morning.displayPaise.isZero) {
                        "The meter never started."
                    } else {
                        "Meter's off."
                    }
                    ObolScreenModel.PostBrushSettle(
                        screenId = "18-nothing-owed",
                        heatFraction = 0f,
                        isCalmSun = true,
                        statusLeft = statusLeft,
                        statusRight = brushedRange,
                        label = topLabel,
                        dryLine = "Nothing owed.\nWell done, I suppose.",
                        sunAmount = "₹0",
                        sunLabel = "final",
                        primaryButton = "Done",
                        secondaryButton = null,
                        bottomNote = "",
                        bottomNoteTopDp = 712f,
                        isNothingOwed = true,
                    )
                } else if (morning.isPaymentFailed) {
                    val shownOwed = if (morning.morningUnpaidPaise.isPositive) {
                        morning.morningUnpaidPaise
                    } else {
                        morning.totalPayableNowPaise
                    }
                    ObolScreenModel.PostBrushSettle(
                        screenId = "17b-pay-failed",
                        heatFraction = morning.skyHeatFraction,
                        isCalmSun = shownOwed.isZero,
                        statusLeft = statusLeft,
                        statusRight = brushedRange,
                        label = "Payment didn't go through.",
                        dryLine = "Nothing was taken.\nI'll wait.",
                        sunAmount = shownOwed.formatRupees(),
                        sunLabel = "still owed",
                        primaryButton = "Try again",
                        secondaryButton = "Later",
                        bottomNote = "Later costs you tomorrow's free minutes.",
                        bottomNoteTopDp = 770f,
                        isNothingOwed = false,
                    )
                } else {
                    val label = if (morning.priorMorningsUnpaidPaise.isPositive) {
                        "Includes ${morning.priorMorningsUnpaidPaise.formatRupees()} from earlier."
                    } else {
                        "Meter's off."
                    }
                    val shownOwed = if (morning.morningUnpaidPaise.isPositive) {
                        morning.morningUnpaidPaise
                    } else {
                        morning.totalPayableNowPaise
                    }
                    val shownSunLabel = if (morning.morningUnpaidPaise.isPositive) "final" else "from earlier"
                    ObolScreenModel.PostBrushSettle(
                        screenId = "17-pay",
                        heatFraction = morning.skyHeatFraction,
                        isCalmSun = morning.morningUnpaidPaise.isZero,
                        statusLeft = statusLeft,
                        statusRight = brushedRange,
                        label = label,
                        dryLine = "Pleasure doing\nbusiness.",
                        sunAmount = shownOwed.formatRupees(),
                        sunLabel = shownSunLabel,
                        primaryButton = "Pay ${morning.totalPayableNowPaise.formatRupees()}",
                        secondaryButton = "Later",
                        bottomNote = "Later costs you tomorrow's free minutes.",
                        bottomNoteTopDp = 770f,
                        isNothingOwed = false,
                    )
                }
            }

            MorningPhase.CLOSED_DONE -> {
                val upMin = morning.upDurationMinutes ?: 0L
                val paidAt = morning.firstPaidAt
                if (morning.morningChargedPaise.isPositive) {
                    val paySuffix = when {
                        morning.morningUnpaidPaise.isZero && paidAt != null ->
                            "Paid at ${formatClockShort(paidAt, zone)}."
                        morning.morningUnpaidPaise.isPositive &&
                            morning.totalPayableNowPaise < ObolConstants.MIN_DONATION_THRESHOLD_PAISE ->
                            "${morning.totalPayableNowPaise.formatRupees()} on tab. Pays at ${ObolConstants.MIN_DONATION_THRESHOLD_PAISE.formatRupees()}."
                        morning.morningUnpaidPaise.isPositive ->
                            "${morning.morningUnpaidPaise.formatRupees()} unpaid."
                        else -> "Settled."
                    }
                    val amtStr = morning.morningChargedPaise.formatRupees()
                    ObolScreenModel.ClosedSummary(
                        screenId = "22-closed",
                        statusLeft = statusLeft,
                        heroAmount = amtStr,
                        dryLine = "$amtStr poorer,\nbut vertical.",
                        subtitle = "Up in $upMin minutes. $paySuffix",
                        showFullBillButton = true,
                        morningId = morning.header.morningId,
                    )
                } else {
                    val subtitle = if (morning.header.isGraceNight) {
                        "Night off."
                    } else {
                        "Up in $upMin minutes."
                    }
                    ObolScreenModel.ClosedSummary(
                        screenId = "23-closed-free",
                        statusLeft = statusLeft,
                        heroAmount = "₹0",
                        dryLine = "A free morning.\nI'll survive.",
                        subtitle = subtitle,
                        showFullBillButton = false,
                        morningId = morning.header.morningId,
                    )
                }
            }

            MorningPhase.CANCELLED -> ObolScreenModel.ClosedSummary(
                screenId = "24-not-rung",
                statusLeft = statusLeft,
                heroAmount = "₹0",
                dryLine = "The alarm didn't ring.\nThat one's on me.",
                subtitle = "Morning cancelled. Nothing charged.",
                showFullBillButton = false,
                morningId = morning.header.morningId,
            )

            MorningPhase.IDLE_PRE_FIRE,
            MorningPhase.CLOSED_UNPAID,
            -> buildHomeScreen(snapshot)
        }
    }

    private fun buildHomeScreen(snapshot: AppSnapshot): ObolScreenModel.Home {
        val zone = snapshot.zone
        val now = snapshot.now
        val statusLeft = formatStatusTime(now, zone)
        val pause = snapshot.pauseState

        if (pause.isPaused) {
            val sinceLabel = pause.pausedSince?.let {
                "Paused since ${it.atZone(zone).format(DATE_LABEL_FMT)}"
            } ?: "Paused"
            return ObolScreenModel.Home(
                screenId = "07-home-paused",
                statusLeft = statusLeft,
                label = sinceLabel,
                heroText = "Off.",
                dryLine = "No alarms. No charges.\nI'm not sulking.",
                subtitle = null,
                primaryButton = "Resume",
                bottomNote = "Pausing and resuming are instant.",
                bottomNoteTopDp = 712f,
                isPaused = true,
            )
        }

        val next = snapshot.nextAlarm
        val today = now.localDateIn(zone)
        val label = when {
            next == null -> "No alarms enabled"
            next.calendarDate == today -> "Today, ${next.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)}"
            next.calendarDate == today.plusDays(1) ->
                "Tomorrow, ${next.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)}"
            else -> next.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)
        }
        val heroTime = next?.let { formatClockShort(it.localTime) } ?: "Off."
        val leftCount = "${snapshot.graceNightsRemainingThisMonth} left"

        val pendingPauseSubtitle = pause.pendingPause?.let { lock ->
            val effLocal = lock.effectiveAt.atZone(zone)
            val suffix = if (effLocal.toLocalDate() == today) "today" else "tomorrow"
            "Pause starts at ${formatClockShort(effLocal.toLocalTime())} $suffix."
        }

        if (snapshot.totalUnpaidPaise.isPositive) {
            val unpaidStr = snapshot.totalUnpaidPaise.formatRupees()
            val isDueNow = snapshot.totalUnpaidPaise >= ObolConstants.MIN_DONATION_THRESHOLD_PAISE
            val pendingBanner = snapshot.pendingUpiSubmission?.let {
                "Confirm pending ${it.amountPaise.formatRupees()} UPI transfer"
            }
            val freeDayWord = if (next?.calendarDate == today) "Today's" else "Tomorrow's"
            return ObolScreenModel.Home(
                screenId = "05-home-unpaid",
                statusLeft = statusLeft,
                label = label,
                heroText = heroTime,
                dryLine = if (snapshot.isNextAlarmGraceNight) {
                    "$freeDayWord free. I'll just\nsit here, then."
                } else {
                    "Sleep well. I'm rooting\nfor the snooze button."
                },
                subtitle = pendingPauseSubtitle,
                unpaidAmountText = unpaidStr,
                unpaidSuffix = if (isDueNow) " unpaid" else " on tab",
                unpaidNote = if (isDueNow) {
                    "I'm holding tomorrow's free minutes\nas collateral."
                } else {
                    "Pays once your tab hits ${ObolConstants.MIN_DONATION_THRESHOLD_PAISE.formatRupees()}.\nTomorrow's free minutes are safe."
                },
                pendingUpiBanner = pendingBanner,
                primaryButton = if (isDueNow) "Pay $unpaidStr" else null,
                nightOffText = when {
                    !snapshot.isNextAlarmGraceNight -> "Take a night off"
                    snapshot.canUndoGraceNightForNext -> "Undo night off"
                    else -> "Night off taken"
                },
                nightOffTrailing = leftCount,
                nightOffTaken = snapshot.isNextAlarmGraceNight,
                nightOffEnabled = if (snapshot.isNextAlarmGraceNight) {
                    snapshot.canUndoGraceNightForNext
                } else {
                    snapshot.canDeclareGraceNightForNext
                },
                bottomNote = "Change alarms",
                bottomNoteIsLink = true,
            )
        }

        if (snapshot.isNextAlarmGraceNight) {
            val freeDayWord = if (next?.calendarDate == today) "Today's" else "Tomorrow's"
            return ObolScreenModel.Home(
                screenId = "06-home-night-off",
                statusLeft = statusLeft,
                label = label,
                heroText = heroTime,
                dryLine = "$freeDayWord free. I'll just\nsit here, then.",
                subtitle = pendingPauseSubtitle ?: "The alarm still rings. Nothing's charged.",
                nightOffText = if (snapshot.canUndoGraceNightForNext) "Undo night off" else "Night off taken",
                nightOffTrailing = leftCount,
                nightOffTaken = true,
                nightOffEnabled = snapshot.canUndoGraceNightForNext,
                bottomNote = "Change alarms",
                bottomNoteIsLink = true,
            )
        }

        return ObolScreenModel.Home(
            screenId = "04-home",
            statusLeft = statusLeft,
            label = label,
            heroText = heroTime,
            dryLine = "Sleep well. I'm rooting\nfor the snooze button.",
            subtitle = pendingPauseSubtitle ?: "5 free minutes, then ₹20 a minute.",
            nightOffText = "Take a night off",
            nightOffTrailing = leftCount,
            nightOffTaken = false,
            nightOffEnabled = snapshot.canDeclareGraceNightForNext,
            bottomNote = "Change alarms",
            bottomNoteIsLink = true,
        )
    }

    private fun buildHistoryScreen(
        snapshot: AppSnapshot,
        showEarlier: Boolean,
    ): ObolScreenModel.HistoryList {
        val zone = snapshot.zone
        val currentYm = snapshot.now.atZone(zone)
        val currentYear = currentYm.year
        val currentMonthPrefix = "%04d-%02d".format(Locale.US, currentYear, currentYm.monthValue)

        val completed = snapshot.mornings.filter { !it.isActivePostFire }.reversed()
        val filtered = if (showEarlier) {
            completed
        } else {
            completed.filter { it.header.calendarDate.toString().startsWith(currentMonthPrefix) }
        }
        val hasEarlier = !showEarlier && completed.size > filtered.size

        val upDurations = filtered.mapNotNull { it.upDurationMinutes }
        val dryLine = when {
            upDurations.isEmpty() -> "No mornings logged yet."
            upDurations.size == 1 -> "Up in ${upDurations[0]} min."
            else -> {
                val avg = upDurations.average().roundToLong()
                val worst = upDurations.max()
                "Avg $avg min · worst $worst min."
            }
        }

        val monthTitle = if (showEarlier) "All time" else currentYm.format(MONTH_NAME_FMT)
        val heroTotal = if (showEarlier) {
            completed.map { it.morningChargedPaise }.sumPaise().formatRupees()
        } else {
            snapshot.currentMonthChargedPaise.formatRupees()
        }
        val capSubtext = if (showEarlier) {
            val totalCount = completed.size
            val paidCount = completed.count { it.morningChargedPaise.isPositive }
            val morningWord = if (totalCount == 1) "morning" else "mornings"
            "$totalCount $morningWord · $paidCount paid"
        } else {
            "of the ${ObolConstants.MONTHLY_CAP_PAISE.formatRupees()} monthly cap"
        }

        val rows = filtered.map { m ->
            val dateShort = if (showEarlier) {
                val fmt = if (m.header.calendarDate.year == currentYear) {
                    HIST_ALL_TIME_DATE_FMT
                } else {
                    HIST_ALL_TIME_YEAR_FMT
                }
                m.header.calendarDate.format(fmt)
            } else {
                m.header.calendarDate.format(HIST_ROW_DATE_FMT)
            }
            val mid = when {
                m.phase == MorningPhase.CANCELLED -> "didn't ring"
                m.header.isGraceNight -> "night off"
                m.phase == MorningPhase.CLOSED_UNPAID -> "never brushed"
                m.upDurationMinutes != null -> "up in ${m.upDurationMinutes} min"
                else -> "closed"
            }
            val isFree = m.morningChargedPaise.isZero
            HistoryRowUi(
                morningId = m.header.morningId,
                dateShort = dateShort,
                summary = mid,
                amountText = if (isFree) "free" else m.morningChargedPaise.formatRupees(),
                isFreeDimmed = isFree,
            )
        }

        return ObolScreenModel.HistoryList(
            monthTitle = monthTitle,
            monthTotalText = heroTotal,
            capSubtext = capSubtext,
            dryLine = dryLine,
            rows = rows,
            hasEarlier = hasEarlier,
            showingAllTime = showEarlier,
        )
    }

    private fun buildFullBillScreen(
        snapshot: AppSnapshot,
        morningId: String,
        returnToEarlier: Boolean = false,
    ): ObolScreenModel.FullBill {
        val zone = snapshot.zone
        val target = snapshot.mornings.firstOrNull { it.header.morningId == morningId }
            ?: snapshot.mornings.lastOrNull()
        val monthName = target?.header?.calendarDate?.format(MONTH_NAME_FMT)
            ?: snapshot.now.atZone(zone).format(MONTH_NAME_FMT)
        val dateLabel = target?.header?.calendarDate?.format(DATE_LABEL_FMT) ?: ""
        val total = target?.morningChargedPaise?.formatRupees() ?: "₹0"
        val rows = target?.billRows?.map { row: BillRow ->
            BillItemUi(
                timeText = formatClockShort(row.at, zone),
                eventText = mapBillRowDescription(row.kind),
                amountText = row.amountPaise?.formatRupees() ?: "",
            )
        } ?: emptyList()

        return ObolScreenModel.FullBill(
            backLabel = if (returnToEarlier) "‹ All time" else "‹ $monthName",
            dateLabel = dateLabel,
            heroTotal = total,
            rows = rows,
        )
    }

    private fun buildSettingsScreen(
        snapshot: AppSnapshot,
        isBackupSyncing: Boolean = false,
    ): ObolScreenModel.Settings {
        val dest = snapshot.destinationState.active
        val pendingDest = snapshot.destinationState.pending
        val (destLabel, isUnset) = when (dest) {
            PaymentDestination.Unset -> "Not set" to true
            is PaymentDestination.Upi -> {
                val base = dest.payeeName.ifBlank { dest.vpa }
                if (pendingDest != null) "$base (change pending)" to false else base to false
            }
            PaymentDestination.DonorboxOppia -> {
                if (pendingDest != null) "Oppia Donorbox (change pending)" to false else "Oppia Donorbox" to false
            }
        }
        val today = snapshot.now.localDateIn(snapshot.zone)
        val (backupLabel, backupAttention) = when {
            isBackupSyncing -> "Backing up…" to false
            snapshot.backupUri.isNullOrBlank() -> "Not set" to false
            snapshot.lastBackupError != null -> "Needs reconnect" to true
            snapshot.backupDirty -> "Pending sync" to false
            snapshot.lastBackupAt != null -> {
                val syncedLocal = snapshot.lastBackupAt.atZone(snapshot.zone)
                val label = if (syncedLocal.toLocalDate() == today) {
                    "Synced · ${formatClockShort(syncedLocal.toLocalTime())}"
                } else {
                    "Synced · ${syncedLocal.toLocalDate().format(HIST_ALL_TIME_DATE_FMT)}"
                }
                label to false
            }
            else -> "Connected" to false
        }
        val pauseOn = snapshot.pauseState.isPaused || snapshot.pauseState.pendingPause != null
        val pauseNote = when {
            snapshot.pauseState.isPaused -> "Pause is active. Tap toggle to resume immediately."
            snapshot.pauseState.pendingPause != null -> {
                val effLocal = snapshot.pauseState.pendingPause!!.effectiveAt.atZone(snapshot.zone)
                val suffix = if (effLocal.toLocalDate() == today) "today" else "tomorrow"
                "Pause requested. Activates at ${formatClockShort(effLocal.toLocalTime())} $suffix."
            }
            else -> "Pausing stops all alarms immediately."
        }
        return ObolScreenModel.Settings(
            alarmSoundLabel = snapshot.alarmSound.label,
            destinationLabel = destLabel,
            destinationIsUnset = isUnset,
            backupLabel = backupLabel,
            backupNeedsAttention = backupAttention,
            pauseToggleOn = pauseOn,
            pauseNote = pauseNote,
        )
    }

    internal fun mapAlarmRows(
        slots: List<AlarmSlot>,
        now: Instant,
        zone: ZoneId,
    ): List<AlarmRowUi> {
        val today = now.localDateIn(zone)
        return slots.map { slot ->
            val dayName = slot.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)
            val pending = slot.pending
            val caption = pending?.let { lock ->
                val targetVal = if (lock.pendingValue.enabled) {
                    formatClockShort(lock.pendingValue.time)
                } else {
                    "Off"
                }
                val effDate = lock.effectiveAt.localDateIn(zone)
                val effTime = formatClockShort(lock.effectiveAt, zone)
                when {
                    effDate.isAfter(today.plusDays(1)) -> "$targetVal after tomorrow"
                    effDate == today.plusDays(1) -> "$targetVal from $effTime tomorrow"
                    effDate == today -> "$targetVal from $effTime today"
                    else -> "$targetVal in 24h"
                }
            }
            AlarmRowUi(
                dayOfWeek = slot.dayOfWeek,
                dayName = dayName,
                timeText = formatClockShort(slot.active.time),
                enabled = slot.active.enabled,
                hasPendingDisable = slot.active.enabled && pending?.pendingValue?.enabled == false,
                pendingCaption = caption,
                hour = slot.active.time.hour,
                minute = slot.active.time.minute,
            )
        }
    }

    fun presetScreens(): List<ObolScreenModel> {
        val defaultRows = AlarmSchedulePolicy.defaultSlots().map { s ->
            val name = s.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)
            AlarmRowUi(
                dayOfWeek = s.dayOfWeek,
                dayName = name,
                timeText = formatClockShort(s.active.time),
                enabled = s.active.enabled,
                pendingCaption = null,
                hour = s.active.time.hour,
                minute = s.active.time.minute,
            )
        }

        return listOf(
            ObolScreenModel.Welcome(),
            ObolScreenModel.Permissions(
                grants = PermissionGrantState(
                    alarmsAllowed = true,
                    cameraAllowed = false,
                    notificationsAllowed = false,
                    batteryUnrestricted = false,
                ),
            ),
            ObolScreenModel.Alarms(
                isOnboarding = false,
                rows = defaultRows,
            ),
            ObolScreenModel.Home(
                screenId = "04-home",
                statusLeft = "Tue 22:47",
                label = "Tomorrow, Wednesday",
                heroText = "6:10",
                dryLine = "Sleep well. I'm rooting\nfor the snooze button.",
                subtitle = "5 free minutes, then ₹20 a minute.",
                nightOffText = "Take a night off",
                nightOffTrailing = "2 left",
                bottomNote = "Change alarms",
                bottomNoteIsLink = true,
            ),
            ObolScreenModel.Home(
                screenId = "05-home-unpaid",
                statusLeft = "Tue 22:47",
                label = "Tomorrow, Wednesday",
                heroText = "6:10",
                dryLine = "Sleep well. I'm rooting\nfor the snooze button.",
                subtitle = null,
                unpaidAmountText = "₹320",
                unpaidNote = "I'm holding tomorrow's free minutes\nas collateral.",
                primaryButton = "Pay ₹320",
                nightOffText = "Take a night off",
                nightOffTrailing = "2 left",
                bottomNote = "Change alarms",
                bottomNoteIsLink = true,
            ),
            ObolScreenModel.Home(
                screenId = "06-home-night-off",
                statusLeft = "Tue 22:47",
                label = "Tomorrow, Wednesday",
                heroText = "6:10",
                dryLine = "Tomorrow's free. I'll just\nsit here, then.",
                subtitle = "The alarm still rings. Nothing's charged.",
                nightOffText = "Undo night off",
                nightOffTrailing = "1 left",
                nightOffTaken = true,
                nightOffEnabled = true,
                bottomNote = "Change alarms",
                bottomNoteIsLink = true,
            ),
            ObolScreenModel.Home(
                screenId = "07-home-paused",
                statusLeft = "Tue 22:47",
                label = "Paused since Mon 16 Nov",
                heroText = "Off.",
                dryLine = "No alarms. No charges.\nI'm not sulking.",
                subtitle = null,
                primaryButton = "Resume",
                bottomNote = "Pausing and resuming are instant.",
                bottomNoteTopDp = 712f,
                isPaused = true,
            ),
            ObolScreenModel.MorningMeter(
                screenId = "08-alarm-free",
                heatFraction = 0f,
                isCalmSun = true,
                statusLeft = "Wed 06:11",
                statusRight = "ringing since 6:10",
                label = "Free for",
                heroCountdown = "4:12",
                subHeroLabel = "then ₹20 a minute.",
                dryLine = "Take your time.\nIt's only money.",
                dryLineTopDp = 340f,
                sunAmount = "₹0",
                sunLabel = "so far",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Quieter. Not cheaper.",
            ),
            ObolScreenModel.MorningMeter(
                screenId = "09-meter",
                heatFraction = 20f / 600f,
                isCalmSun = false,
                statusLeft = "Wed 06:16",
                statusRight = "ringing since 6:10",
                label = "Meter running. ₹20 a minute.",
                dryLine = "No rush.\nI bill by the minute.",
                dryLineTopDp = 132f,
                sunAmount = "₹20",
                sunLabel = "so far",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Quieter. Not cheaper.",
            ),
            ObolScreenModel.MorningMeter(
                screenId = "09b-meter-no-free",
                heatFraction = 20f / 600f,
                isCalmSun = false,
                statusLeft = "Wed 06:11",
                statusRight = "ringing since 6:10",
                label = "No free minutes. ₹320 still unpaid.",
                dryLine = "No warm-up today.\nYou know why.",
                dryLineTopDp = 132f,
                sunAmount = "₹20",
                sunLabel = "so far",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Quieter. Not cheaper.",
            ),
            ObolScreenModel.MorningMeter(
                screenId = "10-snoozed",
                heatFraction = 100f / 600f,
                isCalmSun = false,
                statusLeft = "Wed 06:20",
                statusRight = "rings again at 6:23",
                label = "Quiet for",
                heroCountdown = "3:00",
                dryLine = "Enjoy the silence.\nI'm still counting.",
                dryLineTopDp = 296f,
                sunAmount = "₹100",
                sunLabel = "so far",
                primaryButton = "I'm up",
                secondaryButton = null,
                bottomNote = "The meter doesn't snooze.",
                bottomNoteTopDp = 712f,
            ),
            ObolScreenModel.MorningMeter(
                screenId = "11-ring-again",
                heatFraction = 160f / 600f,
                isCalmSun = false,
                statusLeft = "Wed 06:23",
                statusRight = "ringing since 6:10",
                label = "Meter running. ₹20 a minute.",
                dryLine = "Welcome back.\nThat nap cost ₹100.",
                dryLineTopDp = 132f,
                sunAmount = "₹160",
                sunLabel = "so far",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Quieter. Not cheaper.",
            ),
            ObolScreenModel.MorningMeter(
                screenId = "12-capped",
                heatFraction = 1f,
                isCalmSun = false,
                statusLeft = "Wed 06:45",
                statusRight = "ringing since 6:10",
                label = "Charging has stopped. Ringing has not.",
                dryLine = "That's the cap. I'd take\nmore, but rules.",
                dryLineTopDp = 132f,
                sunAmount = "₹600",
                sunLabel = "capped",
                primaryButton = "I'm up",
                secondaryButton = "Snooze 5 min",
                bottomNote = "Rings until 7:45. Then ₹600 carries to tomorrow.",
            ),
            ObolScreenModel.BrushingCamera(
                screenId = "14-brushing",
                heatFraction = 200f / 600f,
                statusLeft = "Wed 06:25",
                statusRight = "meter paused",
                label = "Keep brushing for",
                heroRemaining = "0:06",
                heroDimmed = false,
                ringProgress = 0.4f,
                ringActive = true,
                showBrushHint = true,
                dotLit = true,
                statusBanner = "Brushing seen. Meter paused at ₹200.",
                dryLine = "Ten seconds. Don't tell\nyour dentist.",
                showOverrideLink = false,
            ),
            ObolScreenModel.BrushingCamera(
                screenId = "15-brushing-lost",
                heatFraction = 206f / 600f,
                statusLeft = "Wed 06:25",
                statusRight = "meter running",
                label = "Brushing left",
                heroRemaining = "0:06",
                heroDimmed = true,
                ringProgress = 0.4f,
                ringActive = false,
                showBrushHint = false,
                dotLit = false,
                statusBanner = "Can't see a toothbrush. ₹206 so far.",
                dryLine = "I don't see a brush.\nI do see ₹20 a minute.",
                showOverrideLink = true,
            ),
            ObolScreenModel.OverrideTyping(
                heatFraction = 280f / 600f,
                statusLeft = "Wed 06:31",
                statusRight = "₹280 so far",
                typedText = "I am awake, upright, and pay",
                canSubmit = false,
            ),
            ObolScreenModel.PostBrushSettle(
                screenId = "17-pay",
                heatFraction = 320f / 600f,
                isCalmSun = false,
                statusLeft = "Wed 06:31",
                statusRight = "brushed at 6:31",
                label = "Meter's off.",
                dryLine = "Pleasure doing\nbusiness.",
                sunAmount = "₹320",
                sunLabel = "final",
                primaryButton = "Pay ₹320",
                secondaryButton = "Later",
                bottomNote = "Later costs you tomorrow's free minutes.",
                bottomNoteTopDp = 770f,
                isNothingOwed = false,
            ),
            ObolScreenModel.PostBrushSettle(
                screenId = "17b-pay-failed",
                heatFraction = 320f / 600f,
                isCalmSun = false,
                statusLeft = "Wed 06:32",
                statusRight = "brushed at 6:31",
                label = "Payment didn't go through.",
                dryLine = "Nothing was taken.\nI'll wait.",
                sunAmount = "₹320",
                sunLabel = "still owed",
                primaryButton = "Try again",
                secondaryButton = "Later",
                bottomNote = "Later costs you tomorrow's free minutes.",
                bottomNoteTopDp = 770f,
                isNothingOwed = false,
            ),
            ObolScreenModel.PostBrushSettle(
                screenId = "18-nothing-owed",
                heatFraction = 0f,
                isCalmSun = true,
                statusLeft = "Wed 06:14",
                statusRight = "brushed at 6:14",
                label = "The meter never started.",
                dryLine = "Nothing owed.\nWell done, I suppose.",
                sunAmount = "₹0",
                sunLabel = "final",
                primaryButton = "Done",
                secondaryButton = null,
                bottomNote = "",
                bottomNoteTopDp = 712f,
                isNothingOwed = true,
            ),
            ObolScreenModel.ClosedSummary(
                screenId = "22-closed",
                statusLeft = "Wed 06:26",
                heroAmount = "₹100",
                dryLine = "₹100 poorer,\nbut vertical.",
                subtitle = "Up in 15 minutes. Paid at 6:26.",
                showFullBillButton = true,
                morningId = "2026-11-18",
            ),
            ObolScreenModel.ClosedSummary(
                screenId = "23-closed-free",
                statusLeft = "Wed 06:14",
                heroAmount = "₹0",
                dryLine = "A free morning.\nI'll survive.",
                subtitle = "Up in 4 minutes.",
                showFullBillButton = false,
                morningId = "2026-11-18",
            ),
            ObolScreenModel.ClosedSummary(
                screenId = "24-not-rung",
                statusLeft = "Wed 06:47",
                heroAmount = "₹0",
                dryLine = "The alarm didn't ring.\nThat one's on me.",
                subtitle = "Morning cancelled. Nothing charged.",
                showFullBillButton = false,
                morningId = "2026-11-18",
            ),
            ObolScreenModel.HistoryList(
                monthTitle = "November",
                monthTotalText = "₹340",
                rows = listOf(
                    HistoryRowUi("2026-11-21", "Sat 21", "up in 3 min", "free", true),
                    HistoryRowUi("2026-11-20", "Fri 20", "up in 16 min", "₹110", false),
                    HistoryRowUi("2026-11-19", "Thu 19", "night off", "free", true),
                    HistoryRowUi("2026-11-18", "Wed 18", "up in 15 min", "₹100", false),
                    HistoryRowUi("2026-11-17", "Tue 17", "up in 13 min", "₹80", false),
                    HistoryRowUi("2026-11-16", "Mon 16", "up in 10 min", "₹50", false),
                ),
                hasEarlier = true,
            ),
            ObolScreenModel.FullBill(
                backLabel = "‹ November",
                dateLabel = "Wed 18 Nov",
                heroTotal = "₹100",
                rows = listOf(
                    BillItemUi("6:10", "Alarm rang"),
                    BillItemUi("6:13", "Snoozed"),
                    BillItemUi("6:15", "Free minutes over. Meter on", "₹0"),
                    BillItemUi("6:18", "Rang again. Snoozed", "₹30"),
                    BillItemUi("6:23", "Rang again", "₹80"),
                    BillItemUi("6:25", "Brushing seen. Meter paused", "₹100"),
                    BillItemUi("6:25", "Ten seconds done. Meter off", "₹100"),
                    BillItemUi("6:26", "Paid", "₹100"),
                ),
            ),
            ObolScreenModel.Settings(
                alarmSoundLabel = "Marimba",
                destinationLabel = "Not set",
                destinationIsUnset = true,
                backupLabel = "Not set",
                backupNeedsAttention = false,
                pauseToggleOn = false,
                pauseNote = "Pausing stops all alarms immediately.",
            ),
        )
    }
}
