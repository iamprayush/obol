package dev.prayush.obol.domain.model

import java.time.DayOfWeek
import java.time.LocalTime

object ObolConstants {
    const val FREE_WINDOW_SECONDS: Long = 300L
    const val RATE_PAISE_PER_MIN: Long = 2_000L
    val MORNING_CAP_PAISE: Paise = Paise(60_000L)
    val MONTHLY_CAP_PAISE: Paise = Paise(600_000L)
    val MIN_DONATION_THRESHOLD_PAISE: Paise = Paise(30_000L)
    val OVERRIDE_FEE_PAISE: Paise = Paise(2_000L)
    val TEST_PAYMENT_PAISE: Paise = Paise(1_000L)
    val DONORBOX_TEST_PAYMENT_PAISE: Paise = MIN_DONATION_THRESHOLD_PAISE

    const val BRUSH_TARGET_MS: Long = 10_000L
    const val BRUSH_DROPOUT_GRACE_MS: Long = 10_000L
    const val OVERRIDE_UNLOCK_MS: Long = 60_000L
    const val CAMERA_INITIAL_MUTE_MS: Long = 30_000L
    const val OVERRIDE_MUTE_MS: Long = 90_000L

    const val SNOOZE_DURATION_SECONDS: Long = 300L

    const val POST_CAP_RING_LIMIT_SEC: Long = 3_600L
    const val GRACE_NIGHT_RING_LIMIT_SEC: Long = 3_600L

    const val ALARM_WEAKEN_DELAY_HOURS: Long = 0L
    const val DESTINATION_DELAY_HOURS: Long = 24L
    const val PAUSE_ACTIVATION_HOURS: Long = 0L

    const val MONTHLY_GRACE_NIGHTS: Int = 3
    const val GRACE_NIGHT_MIN_LEAD_SEC: Long = 7_200L

    const val MISSED_ALARM_TOLERANCE_SEC: Long = 120L

    const val OVERRIDE_TARGET_SENTENCE: String =
        "I am awake, upright, and paying twenty rupees for the privilege."

    val VPA_REGEX: Regex = Regex("^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+$")

    data class DefaultSlotSpec(
        val dayOfWeek: DayOfWeek,
        val enabled: Boolean,
        val time: LocalTime,
    )

    val DEFAULT_WEEKLY_SCHEDULE: List<DefaultSlotSpec> = listOf(
        DefaultSlotSpec(DayOfWeek.MONDAY, true, LocalTime.of(6, 10)),
        DefaultSlotSpec(DayOfWeek.TUESDAY, true, LocalTime.of(6, 10)),
        DefaultSlotSpec(DayOfWeek.WEDNESDAY, true, LocalTime.of(6, 10)),
        DefaultSlotSpec(DayOfWeek.THURSDAY, true, LocalTime.of(6, 10)),
        DefaultSlotSpec(DayOfWeek.FRIDAY, true, LocalTime.of(6, 10)),
        DefaultSlotSpec(DayOfWeek.SATURDAY, true, LocalTime.of(8, 0)),
        DefaultSlotSpec(DayOfWeek.SUNDAY, false, LocalTime.of(8, 0)),
    )
}
