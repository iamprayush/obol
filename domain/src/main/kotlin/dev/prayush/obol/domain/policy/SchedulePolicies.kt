package dev.prayush.obol.domain.policy

import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.serialization.InstantEpochMsSerializer
import dev.prayush.obol.domain.serialization.LocalTimeIsoSerializer
import dev.prayush.obol.domain.time.TimePoint
import dev.prayush.obol.domain.time.localDateIn
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PendingLock<T>(
    val pendingValue: T,
    @Serializable(with = InstantEpochMsSerializer::class)
    val requestedAt: Instant,
    @Serializable(with = InstantEpochMsSerializer::class)
    val effectiveAt: Instant,
    val bootCount: Long,
    val targetElapsedMs: Long,
) {
    fun isReady(point: TimePoint): Boolean =
        if (point.bootCount == bootCount) {
            point.elapsedMs >= targetElapsedMs
        } else {
            !point.at.isBefore(effectiveAt)
        }

    fun isReadyAtInstant(anchorPoint: TimePoint, targetInstant: Instant): Boolean =
        if (anchorPoint.bootCount == bootCount) {
            val deltaMs = Duration.between(anchorPoint.at, targetInstant).toMillis()
            anchorPoint.elapsedMs + deltaMs >= targetElapsedMs
        } else {
            !targetInstant.isBefore(effectiveAt)
        }

    fun isReadyAtFutureInstant(nowPoint: TimePoint, futureInstant: Instant): Boolean {
        val futureDeltaMs = max(0L, Duration.between(nowPoint.at, futureInstant).toMillis())
        val projectedPoint = TimePoint(
            at = futureInstant,
            bootCount = nowPoint.bootCount,
            elapsedMs = nowPoint.elapsedMs + futureDeltaMs,
        )
        return isReady(projectedPoint)
    }

    companion object {
        fun <T> create(
            value: T,
            point: TimePoint,
            delayHours: Long,
        ): PendingLock<T> {
            val delayMs = Math.multiplyExact(delayHours, 3_600_000L)
            return PendingLock(
                pendingValue = value,
                requestedAt = point.at,
                effectiveAt = point.at.plusMillis(delayMs),
                bootCount = point.bootCount,
                targetElapsedMs = Math.addExact(point.elapsedMs, delayMs),
            )
        }
    }
}

@Serializable
data class AlarmSlotConfig(
    val enabled: Boolean,
    @Serializable(with = LocalTimeIsoSerializer::class)
    val time: LocalTime,
)

@Serializable
data class AlarmSlot(
    val dayOfWeek: DayOfWeek,
    val active: AlarmSlotConfig,
    val pending: PendingLock<AlarmSlotConfig>? = null,
) {
    fun resolvedAt(point: TimePoint): AlarmSlot {
        val lock = pending ?: return this
        return if (lock.isReady(point)) {
            copy(active = lock.pendingValue, pending = null)
        } else {
            this
        }
    }

    fun configAtFuture(nowPoint: TimePoint, futureInstant: Instant): AlarmSlotConfig {
        val current = resolvedAt(nowPoint)
        val lock = current.pending ?: return current.active
        return if (lock.isReadyAtFutureInstant(nowPoint, futureInstant)) {
            lock.pendingValue
        } else {
            current.active
        }
    }

    fun effectiveConfigForDate(
        date: LocalDate,
        anchorPoint: TimePoint,
        zone: ZoneId,
    ): AlarmSlotConfig {
        val lock = pending ?: return active
        val pendingAtInstant = date.atTime(lock.pendingValue.time).atZone(zone).toInstant()
        val activeAtInstant = date.atTime(active.time).atZone(zone).toInstant()
        val checkInstant = when {
            active.enabled && activeAtInstant.isAfter(lock.requestedAt) && lock.pendingValue.enabled ->
                minOf(activeAtInstant, pendingAtInstant)
            active.enabled && activeAtInstant.isAfter(lock.requestedAt) ->
                activeAtInstant
            else ->
                pendingAtInstant
        }
        return if (lock.isReadyAtInstant(anchorPoint, checkInstant)) {
            lock.pendingValue
        } else {
            active
        }
    }
}

data class ScheduledAlarmInstance(
    val calendarDate: LocalDate,
    val dayOfWeek: DayOfWeek,
    val localTime: LocalTime,
    val scheduledAt: Instant,
)

object AlarmSchedulePolicy {

    fun defaultSlots(): List<AlarmSlot> =
        ObolConstants.DEFAULT_WEEKLY_SCHEDULE.map { spec ->
            AlarmSlot(
                dayOfWeek = spec.dayOfWeek,
                active = AlarmSlotConfig(enabled = spec.enabled, time = spec.time),
                pending = null,
            )
        }

    fun nextOccurrenceInstant(
        dayOfWeek: DayOfWeek,
        time: LocalTime,
        now: Instant,
        zone: ZoneId,
    ): Instant {
        val today = now.localDateIn(zone)
        val daysAhead = ((dayOfWeek.value - today.dayOfWeek.value) + 7) % 7
        val firstDate = today.plusDays(daysAhead.toLong())
        val firstInstant = firstDate.atTime(time).atZone(zone).toInstant()
        return if (firstInstant.isAfter(now)) {
            firstInstant
        } else {
            firstDate.plusDays(7L).atTime(time).atZone(zone).toInstant()
        }
    }

    fun applyEdit(
        slot: AlarmSlot,
        desired: AlarmSlotConfig,
        point: TimePoint,
        isOnboarding: Boolean,
        zone: ZoneId = ZoneId.of("UTC"),
    ): AlarmSlot {
        val current = slot.resolvedAt(point)
        return current.copy(active = desired, pending = null)
    }

    fun nextScheduledAlarm(
        slots: List<AlarmSlot>,
        point: TimePoint,
        zone: ZoneId,
        pauseLock: PauseState = PauseState(),
        alreadyHandledSchedules: Set<Instant> = emptySet(),
    ): ScheduledAlarmInstance? {
        val resolvedPause = pauseLock.resolvedAt(point)
        if (resolvedPause.isPaused) return null

        val byDay = slots.associateBy { it.dayOfWeek }
        val startDate = point.at.localDateIn(zone)

        for (dayOffset in 0L..8L) {
            val candidateDate = startDate.plusDays(dayOffset)
            val slot = byDay[candidateDate.dayOfWeek] ?: continue
            val candidateTime = slot.effectiveConfigForDate(
                date = candidateDate,
                anchorPoint = point,
                zone = zone,
            )
            if (!candidateTime.enabled) continue

            val candidateInstant = candidateDate.atTime(candidateTime.time).atZone(zone).toInstant()
            if (!candidateInstant.isAfter(point.at)) continue
            if (candidateInstant in alreadyHandledSchedules) continue
            if (resolvedPause.pendingPause?.isReadyAtFutureInstant(point, candidateInstant) == true) {
                continue
            }
            return ScheduledAlarmInstance(
                calendarDate = candidateDate,
                dayOfWeek = candidateDate.dayOfWeek,
                localTime = candidateTime.time,
                scheduledAt = candidateInstant,
            )
        }
        return null
    }

    fun findMissedSchedules(
        slots: List<AlarmSlot>,
        sinceInstant: Instant,
        nowPoint: TimePoint,
        zone: ZoneId,
        pauseState: PauseState,
        existingScheduledInstants: Set<Instant>,
    ): List<ScheduledAlarmInstance> {
        val cutoff = nowPoint.at.minusSeconds(ObolConstants.MISSED_ALARM_TOLERANCE_SEC)
        if (!cutoff.isAfter(sinceInstant)) return emptyList()
        val resolvedPause = pauseState.resolvedAt(nowPoint)
        val pausedFromInstant: Instant? = when {
            resolvedPause.isPaused -> resolvedPause.pausedSince ?: Instant.EPOCH
            else -> null
        }

        val byDay = slots.associateBy { it.dayOfWeek }
        val startDate = sinceInstant.localDateIn(zone)
        val endDate = cutoff.localDateIn(zone)
        val daysBetween = ChronoUnit.DAYS.between(startDate, endDate).coerceAtMost(31L)
        if (daysBetween < 0L) return emptyList()

        val missed = mutableListOf<ScheduledAlarmInstance>()
        for (offset in 0L..daysBetween) {
            val date = startDate.plusDays(offset)
            val slot = byDay[date.dayOfWeek] ?: continue
            val effectiveCfg = slot.effectiveConfigForDate(
                date = date,
                anchorPoint = nowPoint,
                zone = zone,
            )
            if (!effectiveCfg.enabled) continue
            val scheduledAt = date.atTime(effectiveCfg.time).atZone(zone).toInstant()
            if (pausedFromInstant != null && !scheduledAt.isBefore(pausedFromInstant)) continue
            if (scheduledAt.isAfter(sinceInstant) &&
                !scheduledAt.isAfter(cutoff) &&
                scheduledAt !in existingScheduledInstants
            ) {
                missed.add(
                    ScheduledAlarmInstance(
                        calendarDate = date,
                        dayOfWeek = date.dayOfWeek,
                        localTime = effectiveCfg.time,
                        scheduledAt = scheduledAt,
                    ),
                )
            }
        }
        return missed
    }
}

@Serializable
data class PauseState(
    val isPaused: Boolean = false,
    @Serializable(with = InstantEpochMsSerializer::class)
    val pausedSince: Instant? = null,
    val pendingPause: PendingLock<Boolean>? = null,
) {
    fun resolvedAt(point: TimePoint): PauseState {
        val lock = pendingPause ?: return this
        return if (lock.isReady(point)) {
            PauseState(
                isPaused = lock.pendingValue,
                pausedSince = if (lock.pendingValue) lock.effectiveAt else null,
                pendingPause = null,
            )
        } else {
            this
        }
    }

    fun requestPause(point: TimePoint): PauseState {
        val current = resolvedAt(point)
        if (current.isPaused) return current
        return PauseState(
            isPaused = true,
            pausedSince = point.at,
            pendingPause = null,
        )
    }

    fun resumeNow(): PauseState = PauseState(
        isPaused = false,
        pausedSince = null,
        pendingPause = null,
    )
}

@Serializable
sealed interface PaymentDestination {
    @Serializable
    @SerialName("Unset")
    data object Unset : PaymentDestination

    @Serializable
    @SerialName("Upi")
    data class Upi(
        val vpa: String,
        val payeeName: String,
    ) : PaymentDestination {
        init {
            require(ObolConstants.VPA_REGEX.matches(vpa)) { "Invalid UPI VPA: $vpa" }
        }
    }

    @Serializable
    @SerialName("DonorboxOppia")
    data object DonorboxOppia : PaymentDestination
}

@Serializable
data class PaymentDestinationState(
    val active: PaymentDestination = PaymentDestination.Unset,
    val pending: PendingLock<PaymentDestination>? = null,
) {
    fun resolvedAt(point: TimePoint): PaymentDestinationState {
        val lock = pending ?: return this
        return if (lock.isReady(point)) {
            PaymentDestinationState(active = lock.pendingValue, pending = null)
        } else {
            this
        }
    }

    fun requestChange(
        desired: PaymentDestination,
        point: TimePoint,
    ): PaymentDestinationState {
        val current = resolvedAt(point)
        if (desired == current.active) {
            return current.copy(pending = null)
        }
        return if (current.active == PaymentDestination.Unset) {
            PaymentDestinationState(active = desired, pending = null)
        } else {
            current.copy(
                pending = PendingLock.create(
                    value = desired,
                    point = point,
                    delayHours = ObolConstants.DESTINATION_DELAY_HOURS,
                ),
            )
        }
    }
}

object GraceNightPolicy {
    fun remainingForMonth(usedInMonth: Int): Int =
        max(0, ObolConstants.MONTHLY_GRACE_NIGHTS - usedInMonth)

    fun canDeclareFor(
        nextScheduledAt: Instant?,
        now: Instant,
        usedInTargetMonth: Int,
        alreadyDeclaredForNext: Boolean,
    ): Boolean {
        val target = nextScheduledAt ?: return false
        if (alreadyDeclaredForNext) return false
        if (remainingForMonth(usedInTargetMonth) <= 0) return false
        val leadSeconds = Duration.between(now, target).seconds
        return leadSeconds >= ObolConstants.GRACE_NIGHT_MIN_LEAD_SEC
    }

    fun canUndoFor(
        nextScheduledAt: Instant?,
        now: Instant,
        alreadyDeclaredForNext: Boolean,
    ): Boolean {
        val target = nextScheduledAt ?: return false
        if (!alreadyDeclaredForNext) return false
        return now.isBefore(target)
    }
}

@Serializable
enum class BuiltInChime(
    val displayName: String,
    val subtitle: String,
) {
    MARIMBA("Marimba", "Warm wooden mallet arpeggio"),
    SINGING_BOWL("Singing bowl", "Resonant bronze bell shimmer"),
    ELECTRIC_PIANO("Electric piano", "Soft Rhodes morning chords"),
}

@Serializable
sealed interface AlarmSoundChoice {
    @Serializable
    @SerialName("BuiltIn")
    data class BuiltIn(
        val chime: BuiltInChime = BuiltInChime.MARIMBA,
    ) : AlarmSoundChoice

    @Serializable
    @SerialName("SystemAlarm")
    data class SystemAlarm(
        val uriString: String,
        val title: String,
    ) : AlarmSoundChoice

    val label: String
        get() = when (this) {
            is BuiltIn -> chime.displayName
            is SystemAlarm -> title.ifBlank { "Pixel alarm" }
        }

    companion object {
        val Default: AlarmSoundChoice = BuiltIn(BuiltInChime.MARIMBA)
    }
}
