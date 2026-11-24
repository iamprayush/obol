package dev.prayush.obol.domain.time

import dev.prayush.obol.domain.serialization.InstantEpochMsSerializer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlinx.serialization.Serializable

interface ObolClock {
    fun now(): Instant
    fun zone(): ZoneId
    fun bootCount(): Long
    fun elapsedMs(): Long

    fun timePoint(): TimePoint = TimePoint(
        at = now(),
        bootCount = bootCount(),
        elapsedMs = elapsedMs(),
    )
}

@Serializable
data class TimePoint(
    @Serializable(with = InstantEpochMsSerializer::class)
    val at: Instant,
    val bootCount: Long,
    val elapsedMs: Long,
)

fun deltaMs(a: TimePoint, b: TimePoint): Long =
    if (a.bootCount == b.bootCount) {
        max(0L, b.elapsedMs - a.elapsedMs)
    } else {
        max(0L, Duration.between(a.at, b.at).toMillis())
    }

class FakeObolClock(
    initialInstant: Instant = Instant.parse("2026-11-18T00:40:00Z"),
    initialZone: ZoneId = ZoneId.of("Asia/Kolkata"),
    initialBootCount: Long = 1L,
    initialElapsedMs: Long = 1_000_000L,
) : ObolClock {
    private var currentInstant: Instant = initialInstant
    private var currentZone: ZoneId = initialZone
    private var currentBootCount: Long = initialBootCount
    private var currentElapsedMs: Long = initialElapsedMs

    override fun now(): Instant = currentInstant
    override fun zone(): ZoneId = currentZone
    override fun bootCount(): Long = currentBootCount
    override fun elapsedMs(): Long = currentElapsedMs

    fun advanceByMs(ms: Long) {
        require(ms >= 0L) { "Cannot advance clock by negative ms: $ms" }
        currentInstant = currentInstant.plusMillis(ms)
        currentElapsedMs += ms
    }

    fun advanceSeconds(seconds: Long) = advanceByMs(seconds * 1_000L)

    fun advanceMinutes(minutes: Long) = advanceByMs(minutes * 60_000L)

    fun advanceHours(hours: Long) = advanceByMs(hours * 3_600_000L)

    fun shiftWallClockOnly(deltaMs: Long) {
        currentInstant = currentInstant.plusMillis(deltaMs)
    }

    fun setInstantPreservingBoot(newInstant: Instant, elapsedDeltaMs: Long) {
        require(elapsedDeltaMs >= 0L)
        currentInstant = newInstant
        currentElapsedMs += elapsedDeltaMs
    }

    fun reboot(
        downtimeMs: Long,
        newElapsedMs: Long = 5_000L,
        wallClockShiftMs: Long = 0L,
    ) {
        require(downtimeMs >= 0L) { "Downtime cannot be negative" }
        require(newElapsedMs >= 0L) { "New elapsedMs cannot be negative" }
        currentBootCount += 1L
        currentInstant = currentInstant.plusMillis(downtimeMs + wallClockShiftMs)
        currentElapsedMs = newElapsedMs
    }

    fun setZone(newZone: ZoneId) {
        currentZone = newZone
    }
}

private val YEAR_MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

fun Instant.localDateIn(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()

fun Instant.localTimeIn(zone: ZoneId): LocalTime = atZone(zone).toLocalTime()

fun Instant.yearMonthIn(zone: ZoneId): String = atZone(zone).format(YEAR_MONTH_FORMATTER)
