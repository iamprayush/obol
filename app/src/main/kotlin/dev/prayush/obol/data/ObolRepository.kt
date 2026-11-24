package dev.prayush.obol.data

import dev.prayush.obol.domain.engine.MorningDerivation
import dev.prayush.obol.domain.model.DerivedMorning
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningHeader
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.model.WakeDeadline
import dev.prayush.obol.domain.model.sumPaise
import dev.prayush.obol.domain.policy.AlarmSchedulePolicy
import dev.prayush.obol.domain.policy.AlarmSlot
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.GraceNightPolicy
import dev.prayush.obol.domain.policy.PauseState
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.domain.policy.PaymentDestinationState
import dev.prayush.obol.domain.policy.ScheduledAlarmInstance
import dev.prayush.obol.domain.time.ObolClock
import dev.prayush.obol.domain.time.TimePoint
import dev.prayush.obol.domain.time.localDateIn
import dev.prayush.obol.domain.time.yearMonthIn
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PendingUpiInfo(
    val morningId: String,
    val amountPaise: Paise,
    val ref: String,
)

data class AppSnapshot(
    val isLoaded: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.of("UTC"),
    val onboardingStep: OnboardingStep = OnboardingStep.WELCOME,
    val setupCompletedAt: Instant? = null,
    val alarmSlots: List<AlarmSlot> = AlarmSchedulePolicy.defaultSlots(),
    val pauseState: PauseState = PauseState(),
    val destinationState: PaymentDestinationState = PaymentDestinationState(),
    val alarmSound: AlarmSoundChoice = AlarmSoundChoice.Default,
    val lastSystemAlarm: AlarmSoundChoice.SystemAlarm? = null,
    val nextAlarm: ScheduledAlarmInstance? = null,
    val isNextAlarmGraceNight: Boolean = false,
    val graceNightsRemainingThisMonth: Int = ObolConstants.MONTHLY_GRACE_NIGHTS,
    val canDeclareGraceNightForNext: Boolean = false,
    val canUndoGraceNightForNext: Boolean = false,
    val mornings: List<DerivedMorning> = emptyList(),
    val activeMorning: DerivedMorning? = null,
    val totalUnpaidPaise: Paise = Paise.ZERO,
    val currentMonthChargedPaise: Paise = Paise.ZERO,
    val pendingUpiSubmission: PendingUpiInfo? = null,
    val backupUri: String? = null,
    val backupDisplayName: String? = null,
    val lastBackupAt: Instant? = null,
    val lastBackupError: String? = null,
    val backupDirty: Boolean = false,
)

data class ReconcileResult(
    val needsImmediateAlarmResume: Boolean,
    val activeMorning: DerivedMorning?,
    val nextWakeDeadline: WakeDeadline?,
    val nextScheduledAlarm: ScheduledAlarmInstance?,
)

class ObolRepository(
    private val dao: ObolDao,
    private val preferences: ObolPreferences,
    val clock: ObolClock,
    scope: CoroutineScope,
    var onScheduleRefreshNeeded: (() -> Unit)? = null,
    var onBackupDataChanged: (() -> Unit)? = null,
) {
    private val reconcileMutex = Mutex()
    private val tickFlow = MutableStateFlow(0L)
    @Volatile
    private var cameraSessionActiveInProcess: Boolean = false

    val snapshot: StateFlow<AppSnapshot> = combine(
        dao.observeAllMorningsWithEvents(),
        dao.observeAlarmSlots(),
        dao.observeGraceNights(),
        preferences.preferencesFlow,
        tickFlow,
    ) { rawMornings, rawSlots, rawGraceNights, storedPrefs, _ ->
        buildSnapshot(
            rawMornings = rawMornings,
            rawSlots = rawSlots,
            rawGraceNights = rawGraceNights,
            storedPrefs = storedPrefs,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AppSnapshot(
            isLoaded = false,
            now = clock.now(),
            zone = clock.zone(),
        ),
    )

    fun notifyClockTick() {
        tickFlow.value += 1L
    }

    fun currentSnapshotNow(): AppSnapshot = snapshot.value

    suspend fun computeLiveSnapshot(): AppSnapshot {
        ensureDefaultScheduleSeeded()
        return buildSnapshot(
            rawMornings = dao.loadAllMorningsWithEvents(),
            rawSlots = dao.loadAlarmSlots(),
            rawGraceNights = dao.loadGraceNights(),
            storedPrefs = preferences.current(),
        )
    }

    private fun buildSnapshot(
        rawMornings: List<MorningWithEvents>,
        rawSlots: List<AlarmSlotEntity>,
        rawGraceNights: List<GraceNightEntity>,
        storedPrefs: StoredPreferences,
    ): AppSnapshot {
        val point = clock.timePoint()
        val zone = clock.zone()
        val rawDomainSlots = if (rawSlots.isEmpty()) {
            AlarmSchedulePolicy.defaultSlots()
        } else {
            rawSlots.map { it.toDomain() }
        }
        val slots = rawDomainSlots.map { it.resolvedAt(point) }

        val pauseState = storedPrefs.pauseState.resolvedAt(point)
        val destinationState = storedPrefs.destinationState.resolvedAt(point)

        var runningPriorUnpaid = Paise.ZERO
        val derivedMornings = rawMornings.map { item ->
            val derived = MorningDerivation.evaluate(
                header = item.header.toDomain(),
                events = item.toDomainEvents(),
                evalPoint = point,
                priorMorningsUnpaidPaise = runningPriorUnpaid,
            )
            runningPriorUnpaid += derived.morningUnpaidPaise
            derived
        }

        val handledSchedules = rawMornings.map { Instant.ofEpochMilli(it.header.scheduledAtEpochMs) }.toSet()
        val nextAlarm = AlarmSchedulePolicy.nextScheduledAlarm(
            slots = rawDomainSlots,
            point = point,
            zone = zone,
            pauseLock = pauseState,
            alreadyHandledSchedules = handledSchedules,
        )

        val graceScheduledSet = rawGraceNights.map { it.scheduledAtEpochMs }.toSet()
        val targetMonth = (nextAlarm?.scheduledAt ?: point.at).yearMonthIn(zone)
        val usedInTargetMonth = rawGraceNights.count { it.calendarMonth == targetMonth }
        val isNextGrace = nextAlarm != null && nextAlarm.scheduledAt.toEpochMilli() in graceScheduledSet
        val remainingGrace = GraceNightPolicy.remainingForMonth(usedInTargetMonth)
        val canDeclareGrace = GraceNightPolicy.canDeclareFor(
            nextScheduledAt = nextAlarm?.scheduledAt,
            now = point.at,
            usedInTargetMonth = usedInTargetMonth,
            alreadyDeclaredForNext = isNextGrace,
        )
        val canUndoGrace = GraceNightPolicy.canUndoFor(
            nextScheduledAt = nextAlarm?.scheduledAt,
            now = point.at,
            alreadyDeclaredForNext = isNextGrace,
        )

        val activeMorning = derivedMornings.lastOrNull { it.isActivePostFire }
            ?: derivedMornings.lastOrNull()?.takeIf { m ->
                (m.phase == MorningPhase.CLOSED_DONE || m.phase == MorningPhase.CANCELLED) &&
                    !m.isSummaryDismissed
            }

        val currentMonthPrefix = point.at.yearMonthIn(zone)
        val currentMonthCharged = derivedMornings
            .filter { it.header.calendarDate.toString().startsWith(currentMonthPrefix) }
            .map { it.morningChargedPaise }
            .sumPaise()

        val pendingUpi = derivedMornings.lastOrNull {
            it.morningUnpaidPaise.isPositive && it.pendingSubmittedRef != null
        }?.let { m ->
            val submittedCap = if (m.pendingSubmittedPaise.isPositive) {
                Paise.minOf(m.pendingSubmittedPaise, runningPriorUnpaid)
            } else {
                Paise.minOf(m.morningUnpaidPaise, runningPriorUnpaid)
            }
            PendingUpiInfo(
                morningId = m.header.morningId,
                amountPaise = submittedCap,
                ref = m.pendingSubmittedRef!!,
            )
        }

        return AppSnapshot(
            isLoaded = true,
            now = point.at,
            zone = zone,
            onboardingStep = storedPrefs.onboardingStep,
            setupCompletedAt = storedPrefs.setupCompletedAt,
            alarmSlots = slots,
            pauseState = pauseState,
            destinationState = destinationState,
            alarmSound = storedPrefs.alarmSound,
            lastSystemAlarm = storedPrefs.lastSystemAlarm,
            nextAlarm = nextAlarm,
            isNextAlarmGraceNight = isNextGrace,
            graceNightsRemainingThisMonth = remainingGrace,
            canDeclareGraceNightForNext = canDeclareGrace,
            canUndoGraceNightForNext = canUndoGrace,
            mornings = derivedMornings,
            activeMorning = activeMorning,
            totalUnpaidPaise = runningPriorUnpaid,
            currentMonthChargedPaise = currentMonthCharged,
            pendingUpiSubmission = pendingUpi,
            backupUri = storedPrefs.backupUri,
            backupDisplayName = storedPrefs.backupDisplayName,
            lastBackupAt = storedPrefs.lastBackupAt,
            lastBackupError = storedPrefs.lastBackupError,
            backupDirty = storedPrefs.backupDirty,
        )
    }

    suspend fun ensureDefaultScheduleSeeded() {
        if (dao.loadAlarmSlots().isEmpty()) {
            val defaults = AlarmSchedulePolicy.defaultSlots().map { AlarmSlotEntity.fromDomain(it) }
            dao.upsertAlarmSlots(defaults)
        }
    }

    suspend fun acceptWelcome() {
        ensureDefaultScheduleSeeded()
        preferences.markWelcomeAccepted()
        notifyClockTick()
    }

    suspend fun acceptPermissions() {
        ensureDefaultScheduleSeeded()
        preferences.markPermissionsAccepted()
        notifyClockTick()
    }

    suspend fun finishSetup() {
        ensureDefaultScheduleSeeded()
        preferences.markSetupCompleted(clock.now())
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        onBackupDataChanged?.invoke()
    }

    suspend fun updateAlarmSlot(dayOfWeek: DayOfWeek, desired: AlarmSlotConfig) = reconcileMutex.withLock {
        ensureDefaultScheduleSeeded()
        val point = clock.timePoint()
        val prefs = preferences.current()
        val isOnboarding = prefs.setupCompletedAt == null
        val existing = dao.loadAlarmSlots()
            .firstOrNull { it.dayOfWeek == dayOfWeek.value }
            ?.toDomain()
            ?: AlarmSchedulePolicy.defaultSlots().first { it.dayOfWeek == dayOfWeek }

        val updated = AlarmSchedulePolicy.applyEdit(
            slot = existing,
            desired = desired,
            point = point,
            isOnboarding = isOnboarding,
            zone = clock.zone(),
        )
        dao.upsertAlarmSlot(AlarmSlotEntity.fromDomain(updated))
        pruneOrphanedFutureGraceNights(point)
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        onBackupDataChanged?.invoke()
    }

    suspend fun updateAlarmSound(choice: AlarmSoundChoice) {
        preferences.saveAlarmSound(choice)
        notifyClockTick()
        onBackupDataChanged?.invoke()
    }

    suspend fun declareGraceNightForNext(): Boolean = reconcileMutex.withLock {
        val snap = computeLiveSnapshot()
        val target = snap.nextAlarm ?: return@withLock false
        if (!snap.canDeclareGraceNightForNext) return@withLock false
        val month = target.scheduledAt.yearMonthIn(clock.zone())
        val inserted = dao.insertGraceNight(
            GraceNightEntity(
                scheduledAtEpochMs = target.scheduledAt.toEpochMilli(),
                calendarMonth = month,
                declaredAtEpochMs = clock.now().toEpochMilli(),
            ),
        )
        notifyClockTick()
        if (inserted != -1L) {
            onBackupDataChanged?.invoke()
        }
        inserted != -1L
    }

    suspend fun undoGraceNightForNext(): Boolean = reconcileMutex.withLock {
        val snap = computeLiveSnapshot()
        val target = snap.nextAlarm ?: return@withLock false
        if (!snap.canUndoGraceNightForNext) return@withLock false
        val targetEpochMs = target.scheduledAt.toEpochMilli()
        val alreadyHandled = dao.loadAllMorningsWithEvents().any {
            it.header.scheduledAtEpochMs == targetEpochMs
        }
        if (alreadyHandled || !clock.now().isBefore(target.scheduledAt)) {
            return@withLock false
        }
        val deleted = dao.deleteGraceNight(targetEpochMs)
        notifyClockTick()
        if (deleted > 0) {
            onBackupDataChanged?.invoke()
        }
        deleted > 0
    }

    suspend fun setPauseRequested(enabled: Boolean) = reconcileMutex.withLock {
        val point = clock.timePoint()
        val current = preferences.current().pauseState
        val wasPaused = current.resolvedAt(point).isPaused
        val next = if (enabled) current.requestPause(point) else current.resumeNow()
        preferences.savePauseState(next)
        if (!enabled && wasPaused) {
            preferences.updateLastReconciledAt(point.at)
        }
        pruneOrphanedFutureGraceNights(point)
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        onBackupDataChanged?.invoke()
    }

    private suspend fun pruneOrphanedFutureGraceNights(point: TimePoint) {
        val graceRows = dao.loadGraceNights()
        if (graceRows.isEmpty()) return
        val nowEpochMs = point.at.toEpochMilli()
        val futureRows = graceRows.filter { it.scheduledAtEpochMs > nowEpochMs }
        if (futureRows.isEmpty()) return

        val zone = clock.zone()
        val resolvedPause = preferences.current().pauseState.resolvedAt(point)
        val slotsByDay = dao.loadAlarmSlots()
            .map { it.toDomain() }
            .ifEmpty { AlarmSchedulePolicy.defaultSlots() }
            .associateBy { it.dayOfWeek }
        val handledEpochMs = dao.loadAllMorningsWithEvents()
            .map { it.header.scheduledAtEpochMs }
            .toSet()

        for (row in futureRows) {
            if (row.scheduledAtEpochMs in handledEpochMs) continue
            val targetInstant = Instant.ofEpochMilli(row.scheduledAtEpochMs)
            val targetDate = targetInstant.localDateIn(zone)
            val slot = slotsByDay[targetDate.dayOfWeek]
            val effectiveCfg = slot?.effectiveConfigForDate(
                date = targetDate,
                anchorPoint = point,
                zone = zone,
            )
            val pausedAtTarget = resolvedPause.isPaused ||
                resolvedPause.pendingPause?.isReadyAtFutureInstant(point, targetInstant) == true
            val stillScheduled = !pausedAtTarget &&
                effectiveCfg != null &&
                effectiveCfg.enabled &&
                targetDate.atTime(effectiveCfg.time).atZone(zone).toInstant() == targetInstant
            if (!stillScheduled) {
                dao.deleteGraceNight(row.scheduledAtEpochMs)
            }
        }
    }

    suspend fun updatePaymentDestination(destination: PaymentDestination) {
        val point = clock.timePoint()
        val current = preferences.current().destinationState
        val next = current.requestChange(destination, point)
        preferences.saveDestinationState(next)
        notifyClockTick()
        onBackupDataChanged?.invoke()
    }

    suspend fun reconcile(
        isProcessOrBootRestart: Boolean = true,
    ): ReconcileResult = reconcileMutex.withLock {
        ensureDefaultScheduleSeeded()
        val point = clock.timePoint()
        val zone = clock.zone()
        val prefs = preferences.current()

        // 1. Persist matured pause & destination locks
        val resolvedPause = prefs.pauseState.resolvedAt(point)
        if (resolvedPause != prefs.pauseState) {
            preferences.savePauseState(resolvedPause)
        }
        val resolvedDest = prefs.destinationState.resolvedAt(point)
        if (resolvedDest != prefs.destinationState) {
            preferences.saveDestinationState(resolvedDest)
        }

        // 2. Close dangling camera/brushing sessions on restart/reboot and advance due system events
        val mornings = dao.loadAllMorningsWithEvents()
        var reconcilePriorUnpaid = Paise.ZERO
        for (m in mornings) {
            val header = m.header.toDomain()
            var events = m.toDomainEvents()
            val derived = MorningDerivation.evaluate(
                header = header,
                events = events,
                evalPoint = point,
                priorMorningsUnpaidPaise = reconcilePriorUnpaid,
            )
            if (derived.isActivePostFire) {
                val rebootOccurred = events.isNotEmpty() && events.last().bootCount != point.bootCount
                if (isProcessOrBootRestart || rebootOccurred || !cameraSessionActiveInProcess) {
                    cameraSessionActiveInProcess = false
                    if (derived.isBrushingSeen) {
                        val lastPausePoint = events
                            .lastOrNull { it.event is MorningEvent.BrushingPausedMeter }
                            ?.point
                            ?: events.last().point
                        dao.appendEvent(header.morningId, lastPausePoint, MorningEvent.BrushingSightLost)
                        dao.appendEvent(header.morningId, lastPausePoint, MorningEvent.CameraClosed)
                        events = dao.loadMorningWithEvents(header.morningId)!!.toDomainEvents()
                    } else if (derived.phase == MorningPhase.BRUSHING) {
                        val anchorPoint = events.last().point
                        dao.appendEvent(header.morningId, anchorPoint, MorningEvent.CameraClosed)
                        events = dao.loadMorningWithEvents(header.morningId)!!.toDomainEvents()
                    }
                }
                var guard = 0
                while (guard < 8) {
                    val (dueEvent, duePoint) =
                        MorningDerivation.nextDueSystemTransition(header, events, point) ?: break
                    dao.appendEvent(header.morningId, duePoint, dueEvent)
                    events = dao.loadMorningWithEvents(header.morningId)!!.toDomainEvents()
                    guard += 1
                }
            }
            val finalDerived = MorningDerivation.evaluate(
                header = header,
                events = events,
                evalPoint = point,
                priorMorningsUnpaidPaise = reconcilePriorUnpaid,
            )
            reconcilePriorUnpaid += finalDerived.morningUnpaidPaise
        }

        // 3. Cancel missed un-fired schedules into 24-not-rung (evaluated against uncompacted slot locks)
        val setupAt = prefs.setupCompletedAt
        if (setupAt != null) {
            val since = prefs.lastReconciledAt?.let { if (it.isAfter(setupAt)) it else setupAt } ?: setupAt
            val uncompactedSlots = dao.loadAlarmSlots().map { it.toDomain() }
            val latestMornings = dao.loadAllMorningsWithEvents()
            val existingSchedules = latestMornings.map { Instant.ofEpochMilli(it.header.scheduledAtEpochMs) }.toSet()
            val graceSet = dao.loadGraceNights().map { it.scheduledAtEpochMs }.toSet()

            val missedList = AlarmSchedulePolicy.findMissedSchedules(
                slots = uncompactedSlots,
                sinceInstant = since,
                nowPoint = point,
                zone = zone,
                pauseState = resolvedPause,
                existingScheduledInstants = existingSchedules,
            )
            for (missed in missedList) {
                val header = createSnapshotHeader(
                    scheduledAt = missed.scheduledAt,
                    evalPoint = point,
                    graceScheduledSet = graceSet,
                    priorMornings = dao.loadAllMorningsWithEvents(),
                )
                val ageMs = Duration.between(missed.scheduledAt, point.at).toMillis().coerceAtLeast(0L)
                val cancelPoint = if (ageMs <= point.elapsedMs) {
                    TimePoint(
                        at = missed.scheduledAt,
                        bootCount = point.bootCount,
                        elapsedMs = point.elapsedMs - ageMs,
                    )
                } else {
                    TimePoint(
                        at = missed.scheduledAt,
                        bootCount = -1L,
                        elapsedMs = 0L,
                    )
                }
                dao.createMorningWithInitialEvent(
                    header = MorningHeaderEntity.fromDomain(header),
                    point = cancelPoint,
                    initialEvent = MorningEvent.Cancelled("MISSED_ALARM"),
                )
            }
            val safeCursor = point.at.minusSeconds(ObolConstants.MISSED_ALARM_TOLERANCE_SEC)
            if (safeCursor.isAfter(since)) {
                preferences.updateLastReconciledAt(safeCursor)
            }
            if (missedList.isNotEmpty()) {
                onBackupDataChanged?.invoke()
            }
        }

        // 4. Compact matured slot locks once their maturation date has passed (preserving same-day pre-lock context)
        val todayLocal = point.at.localDateIn(zone)
        for (entity in dao.loadAlarmSlots()) {
            val domain = entity.toDomain()
            val resolved = domain.resolvedAt(point)
            val lock = domain.pending
            val safeToCompact = lock == null ||
                !domain.active.enabled ||
                !lock.pendingValue.enabled ||
                todayLocal.isAfter(lock.effectiveAt.localDateIn(zone))
            if (resolved != domain && safeToCompact) {
                dao.upsertAlarmSlot(AlarmSlotEntity.fromDomain(resolved))
            }
        }
        pruneOrphanedFutureGraceNights(point)

        notifyClockTick()
        val finalSnap = computeLiveSnapshot()
        val activePostFire = finalSnap.activeMorning?.takeIf { it.isActivePostFire }
        ReconcileResult(
            needsImmediateAlarmResume = activePostFire?.requiresAlarmService == true,
            activeMorning = finalSnap.activeMorning,
            nextWakeDeadline = activePostFire?.nextWakeDeadline,
            nextScheduledAlarm = finalSnap.nextAlarm,
        )
    }

    suspend fun advanceDueSystemEvents(): List<MorningEvent> = reconcileMutex.withLock {
        val point = clock.timePoint()
        val emitted = mutableListOf<MorningEvent>()
        val mornings = dao.loadAllMorningsWithEvents()
        var runningPriorUnpaid = Paise.ZERO
        for (m in mornings) {
            val header = m.header.toDomain()
            var events = m.toDomainEvents()
            val derived = MorningDerivation.evaluate(
                header = header,
                events = events,
                evalPoint = point,
                priorMorningsUnpaidPaise = runningPriorUnpaid,
            )
            if (derived.isActivePostFire) {
                var guard = 0
                while (guard < 8) {
                    val (dueEvent, duePoint) =
                        MorningDerivation.nextDueSystemTransition(header, events, point) ?: break
                    dao.appendEvent(header.morningId, duePoint, dueEvent)
                    emitted.add(dueEvent)
                    events = dao.loadMorningWithEvents(header.morningId)!!.toDomainEvents()
                    guard += 1
                }
            }
            val finalDerived = MorningDerivation.evaluate(
                header = header,
                events = events,
                evalPoint = point,
                priorMorningsUnpaidPaise = runningPriorUnpaid,
            )
            runningPriorUnpaid += finalDerived.morningUnpaidPaise
        }
        if (emitted.isNotEmpty()) {
            notifyClockTick()
            onScheduleRefreshNeeded?.invoke()
        }
        emitted
    }

    suspend fun onAlarmFired(scheduledAt: Instant): DerivedMorning? = reconcileMutex.withLock {
        ensureDefaultScheduleSeeded()
        val point = clock.timePoint()
        val prefs = preferences.current()
        val pauseState = prefs.pauseState.resolvedAt(point)
        if (pauseState.isPaused) return@withLock null

        val allMornings = dao.loadAllMorningsWithEvents()
        val existingBySchedule = allMornings.firstOrNull {
            it.header.scheduledAtEpochMs == scheduledAt.toEpochMilli()
        }
        if (existingBySchedule != null) {
            notifyClockTick()
            return@withLock computeLiveSnapshot().mornings.firstOrNull {
                it.header.morningId == existingBySchedule.header.morningId
            }
        }

        // Close any lingering post-brush payment screen so a new scheduled alarm is never blocked,
        // while keeping a genuinely ringing/brushing active morning untouched.
        val liveBefore = computeLiveSnapshot()
        val openActive = liveBefore.activeMorning
        if (openActive?.phase == MorningPhase.POST_CHECK_PAY_OR_ZERO) {
            dao.appendEvent(openActive.header.morningId, point, MorningEvent.PaymentDeferred)
            dao.appendEvent(openActive.header.morningId, point, MorningEvent.MorningDismissed)
        } else if (openActive?.isActivePostFire == true) {
            return@withLock openActive
        }

        val freshAllMornings = dao.loadAllMorningsWithEvents()
        val graceSet = dao.loadGraceNights().map { it.scheduledAtEpochMs }.toSet()
        val header = createSnapshotHeader(
            scheduledAt = scheduledAt,
            evalPoint = point,
            graceScheduledSet = graceSet,
            priorMornings = freshAllMornings,
        )

        val latenessSec = Duration.between(scheduledAt, point.at).seconds
        val initialEvent = if (latenessSec > ObolConstants.MISSED_ALARM_TOLERANCE_SEC) {
            MorningEvent.Cancelled("MISSED_ALARM")
        } else {
            MorningEvent.AlarmFired
        }

        dao.createMorningWithInitialEvent(
            header = MorningHeaderEntity.fromDomain(header),
            point = point,
            initialEvent = initialEvent,
        )
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        computeLiveSnapshot().mornings.firstOrNull { it.header.morningId == header.morningId }
    }

    suspend fun triggerImmediateAlarmNow(
        forceGraceNight: Boolean = false,
    ): DerivedMorning = reconcileMutex.withLock {
        ensureDefaultScheduleSeeded()
        val point = clock.timePoint()
        val prefs = preferences.current()
        if (prefs.setupCompletedAt == null) {
            preferences.markSetupCompleted(point.at)
            preferences.updateLastReconciledAt(point.at)
        }
        // Dismiss any completed summary or lingering post-brush settle screen first
        val live = computeLiveSnapshot()
        val active = live.activeMorning
        if (active != null && active.phase == MorningPhase.POST_CHECK_PAY_OR_ZERO) {
            dao.appendEvent(active.header.morningId, point, MorningEvent.PaymentDeferred)
            dao.appendEvent(active.header.morningId, point, MorningEvent.MorningDismissed)
        } else if (active != null && active.isActivePostFire) {
            return@withLock active
        } else if (active != null && !active.isSummaryDismissed) {
            dao.appendEvent(active.header.morningId, point, MorningEvent.MorningDismissed)
        }
        val graceSet = if (forceGraceNight) {
            setOf(point.at.toEpochMilli())
        } else {
            dao.loadGraceNights().map { it.scheduledAtEpochMs }.toSet()
        }
        val header = createSnapshotHeader(
            scheduledAt = point.at,
            evalPoint = point,
            graceScheduledSet = graceSet,
            priorMornings = dao.loadAllMorningsWithEvents(),
        )
        dao.createMorningWithInitialEvent(
            header = MorningHeaderEntity.fromDomain(header),
            point = point,
            initialEvent = MorningEvent.AlarmFired,
        )
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        computeLiveSnapshot().activeMorning!!
    }

    suspend fun appendActiveEvent(
        event: MorningEvent,
        pointOverride: TimePoint? = null,
    ): DerivedMorning? = reconcileMutex.withLock {
        val snap = computeLiveSnapshot()
        val target = snap.activeMorning ?: return@withLock null
        val point = pointOverride ?: clock.timePoint()
        when (event) {
            MorningEvent.CameraOpened,
            MorningEvent.BrushingPausedMeter,
            MorningEvent.OverrideOpened,
            is MorningEvent.StayUpEarlyOpened,
            -> cameraSessionActiveInProcess = true
            MorningEvent.CameraClosed,
            MorningEvent.BrushCompleted,
            is MorningEvent.OverrideAccepted,
            is MorningEvent.Snoozed,
            is MorningEvent.StayUpEarlyCancelled,
            is MorningEvent.StayUpFacePassed,
            is MorningEvent.RingTimedOut,
            is MorningEvent.Cancelled,
            -> cameraSessionActiveInProcess = false
            else -> Unit
        }
        dao.appendEvent(target.header.morningId, point, event)
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        val shouldBackup = when (event) {
            MorningEvent.BrushCompleted,
            is MorningEvent.OverrideAccepted,
            MorningEvent.GraceNightDismissed,
            MorningEvent.PaymentDeferred,
            MorningEvent.MorningDismissed,
            is MorningEvent.RingTimedOut,
            is MorningEvent.Cancelled,
            is MorningEvent.PaymentRecorded,
            -> true
            else -> false
        }
        if (shouldBackup) {
            onBackupDataChanged?.invoke()
        }
        computeLiveSnapshot().mornings.firstOrNull { it.header.morningId == target.header.morningId }
    }

    suspend fun markPaidThrough(
        expectedTotalPaise: Paise,
        railId: String,
        ref: String,
    ): Paise = reconcileMutex.withLock {
        val point = clock.timePoint()
        val paid = dao.markPaidThrough(
            expectedTotalPaise = expectedTotalPaise,
            railId = railId,
            ref = ref,
            timePoint = point,
        )
        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
        if (paid.isPositive) {
            onBackupDataChanged?.invoke()
        }
        paid
    }

    suspend fun recordPendingSubmission(
        expectedTotalPaise: Paise,
        ref: String,
    ) = reconcileMutex.withLock {
        val snap = computeLiveSnapshot()
        val point = clock.timePoint()
        val active = snap.activeMorning
        val unpaidPriorId = snap.mornings.lastOrNull { it.morningUnpaidPaise.isPositive }?.header?.morningId
        if (active != null) {
            dao.appendEvent(
                morningId = active.header.morningId,
                point = point,
                event = MorningEvent.PaymentPendingSubmitted(expectedTotalPaise, ref),
            )
            if (active.morningUnpaidPaise.isZero && unpaidPriorId != null && unpaidPriorId != active.header.morningId) {
                dao.appendEvent(
                    morningId = unpaidPriorId,
                    point = point,
                    event = MorningEvent.PaymentPendingSubmitted(expectedTotalPaise, ref),
                )
            }
            notifyClockTick()
            onScheduleRefreshNeeded?.invoke()
            onBackupDataChanged?.invoke()
        } else if (unpaidPriorId != null) {
            dao.appendEvent(
                morningId = unpaidPriorId,
                point = point,
                event = MorningEvent.PaymentPendingSubmitted(expectedTotalPaise, ref),
            )
            notifyClockTick()
            onScheduleRefreshNeeded?.invoke()
            onBackupDataChanged?.invoke()
        }
    }

    suspend fun captureBackupDocument(): ObolBackupDocument = reconcileMutex.withLock {
        ensureDefaultScheduleSeeded()
        val rawMornings = dao.loadAllMorningsWithEvents()
        val rawSlots = dao.loadAlarmSlots()
        val rawGraceNights = dao.loadGraceNights()
        val storedPrefs = preferences.current()
        val liveSnap = buildSnapshot(
            rawMornings = rawMornings,
            rawSlots = rawSlots,
            rawGraceNights = rawGraceNights,
            storedPrefs = storedPrefs,
        )
        ObolBackupDocument(
            schemaVersion = ObolBackupCodec.CURRENT_SCHEMA_VERSION,
            exportedAtEpochMs = clock.now().toEpochMilli(),
            summary = ObolBackupCodec.buildSummary(liveSnap, rawMornings.size),
            preferences = BackupPreferencesSnapshot(
                welcomeAccepted = storedPrefs.welcomeAccepted,
                permissionsAccepted = storedPrefs.permissionsAccepted,
                setupCompletedAtEpochMs = storedPrefs.setupCompletedAt?.toEpochMilli(),
                lastReconciledAtEpochMs = storedPrefs.lastReconciledAt?.toEpochMilli(),
                pauseState = storedPrefs.pauseState,
                destinationState = storedPrefs.destinationState,
                alarmSound = storedPrefs.alarmSound,
                lastSystemAlarm = storedPrefs.lastSystemAlarm,
            ),
            alarmSlots = rawSlots.map { s ->
                BackupAlarmSlot(
                    dayOfWeek = s.dayOfWeek,
                    activeEnabled = s.activeEnabled,
                    activeHour = s.activeHour,
                    activeMinute = s.activeMinute,
                    pendingJson = s.pendingJson,
                )
            },
            graceNights = rawGraceNights.map { g ->
                BackupGraceNight(
                    scheduledAtEpochMs = g.scheduledAtEpochMs,
                    calendarMonth = g.calendarMonth,
                    declaredAtEpochMs = g.declaredAtEpochMs,
                )
            },
            mornings = rawMornings.map { m ->
                BackupMorning(
                    header = BackupMorningHeader(
                        morningId = m.header.morningId,
                        calendarDate = m.header.calendarDate,
                        scheduledAtEpochMs = m.header.scheduledAtEpochMs,
                        isTrialDay = m.header.isTrialDay,
                        isGraceNight = m.header.isGraceNight,
                        hadPriorDebt = m.header.hadPriorDebt,
                        priorMonthChargedPaise = m.header.priorMonthChargedPaise,
                        ratePaisePerMin = m.header.ratePaisePerMin,
                        morningCapPaise = m.header.morningCapPaise,
                        monthlyCapPaise = m.header.monthlyCapPaise,
                    ),
                    events = m.events.sortedBy { it.seq }.map { e ->
                        BackupMorningEvent(
                            seq = e.seq,
                            atEpochMs = e.atEpochMs,
                            bootCount = e.bootCount,
                            elapsedMs = e.elapsedMs,
                            payloadJson = e.payloadJson,
                        )
                    },
                )
            },
        )
    }

    suspend fun restoreFromBackupDocument(
        doc: ObolBackupDocument,
        resetPermissionsForNewPhone: Boolean,
    ) = reconcileMutex.withLock {
        val headers = doc.mornings.map { m ->
            MorningHeaderEntity(
                morningId = m.header.morningId,
                calendarDate = m.header.calendarDate,
                scheduledAtEpochMs = m.header.scheduledAtEpochMs,
                isTrialDay = m.header.isTrialDay,
                isGraceNight = m.header.isGraceNight,
                hadPriorDebt = m.header.hadPriorDebt,
                priorMonthChargedPaise = m.header.priorMonthChargedPaise,
                ratePaisePerMin = m.header.ratePaisePerMin,
                morningCapPaise = m.header.morningCapPaise,
                monthlyCapPaise = m.header.monthlyCapPaise,
            )
        }
        val events = doc.mornings.flatMap { m ->
            m.events.sortedBy { it.seq }.map { e ->
                MorningEventEntity(
                    morningId = m.header.morningId,
                    seq = e.seq,
                    atEpochMs = e.atEpochMs,
                    bootCount = e.bootCount,
                    elapsedMs = e.elapsedMs,
                    payloadJson = e.payloadJson,
                )
            }
        }
        val slots = doc.alarmSlots.map { s ->
            AlarmSlotEntity(
                dayOfWeek = s.dayOfWeek,
                activeEnabled = s.activeEnabled,
                activeHour = s.activeHour,
                activeMinute = s.activeMinute,
                pendingJson = s.pendingJson,
            )
        }
        val graceNights = doc.graceNights.map { g ->
            GraceNightEntity(
                scheduledAtEpochMs = g.scheduledAtEpochMs,
                calendarMonth = g.calendarMonth,
                declaredAtEpochMs = g.declaredAtEpochMs,
            )
        }

        dao.replaceAllData(
            headers = headers,
            events = events,
            slots = slots,
            graceNights = graceNights,
        )
        if (slots.isEmpty()) {
            ensureDefaultScheduleSeeded()
        }

        val p = doc.preferences
        preferences.restoreDomainPreferences(
            welcomeAccepted = if (resetPermissionsForNewPhone) true else p.welcomeAccepted,
            permissionsAccepted = if (resetPermissionsForNewPhone) false else p.permissionsAccepted,
            setupCompletedAt = if (resetPermissionsForNewPhone) {
                null
            } else {
                p.setupCompletedAtEpochMs?.let { Instant.ofEpochMilli(it) }
            },
            lastReconciledAt = clock.now(),
            pauseState = p.pauseState,
            destinationState = p.destinationState,
            alarmSound = p.alarmSound,
            lastSystemAlarm = p.lastSystemAlarm,
        )

        notifyClockTick()
        onScheduleRefreshNeeded?.invoke()
    }

    suspend fun recordPaymentFailure(reason: String) = reconcileMutex.withLock {
        val snap = computeLiveSnapshot()
        val targetMorningId = snap.activeMorning?.header?.morningId
        if (targetMorningId != null) {
            dao.appendEvent(
                morningId = targetMorningId,
                point = clock.timePoint(),
                event = MorningEvent.PaymentFailed(reason),
            )
            notifyClockTick()
        }
    }

    private fun createSnapshotHeader(
        scheduledAt: Instant,
        evalPoint: TimePoint,
        graceScheduledSet: Set<Long>,
        priorMornings: List<MorningWithEvents>,
    ): MorningHeader {
        val zone = clock.zone()
        val date = scheduledAt.localDateIn(zone)
        val monthPrefix = scheduledAt.yearMonthIn(zone)

        var priorUnpaid = Paise.ZERO
        var priorMonthCharged = Paise.ZERO
        for (m in priorMornings) {
            val d = MorningDerivation.evaluate(m.header.toDomain(), m.toDomainEvents(), evalPoint)
            priorUnpaid += d.morningUnpaidPaise
            if (m.header.calendarDate.startsWith(monthPrefix)) {
                priorMonthCharged += d.morningChargedPaise
            }
        }

        val existingIds = priorMornings.map { it.header.morningId }.toSet()
        val baseId = date.toString()
        val morningId = if (baseId !in existingIds) {
            baseId
        } else {
            "$baseId-${scheduledAt.toEpochMilli()}"
        }

        return MorningHeader(
            morningId = morningId,
            calendarDate = date,
            scheduledAt = scheduledAt,
            isTrialDay = false,
            isGraceNight = scheduledAt.toEpochMilli() in graceScheduledSet,
            hadPriorDebt = priorUnpaid >= ObolConstants.MIN_DONATION_THRESHOLD_PAISE,
            priorMonthChargedPaise = priorMonthCharged,
            ratePaisePerMin = ObolConstants.RATE_PAISE_PER_MIN,
            morningCapPaise = ObolConstants.MORNING_CAP_PAISE,
            monthlyCapPaise = ObolConstants.MONTHLY_CAP_PAISE,
        )
    }
}
