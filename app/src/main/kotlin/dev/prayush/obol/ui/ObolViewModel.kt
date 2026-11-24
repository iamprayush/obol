package dev.prayush.obol.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.prayush.obol.BuildConfig
import dev.prayush.obol.alarm.AlarmRingingService
import dev.prayush.obol.data.BackupOpResult
import dev.prayush.obol.data.BackupPreview
import dev.prayush.obol.data.OnboardingStep
import dev.prayush.obol.di.AppContainer
import dev.prayush.obol.domain.model.DerivedMorning
import dev.prayush.obol.domain.model.MorningEvent
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.AlarmSlotConfig
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.vision.CrossingCoordinator
import dev.prayush.obol.vision.DetectedFaceInfo
import dev.prayush.obol.vision.UprightBox
import dev.prayush.obol.vision.VisionFrameAnalysis
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DonorboxPendingReturn(
    val amountPaise: Paise,
    val isTestOnly: Boolean,
)

class ObolViewModel(
    val container: AppContainer,
) : ViewModel() {

    private val repository = container.repository

    private val _overlay = MutableStateFlow<CalmOverlay>(CalmOverlay.None)
    val overlay: StateFlow<CalmOverlay> = _overlay.asStateFlow()

    private val _permissions = MutableStateFlow(readSystemPermissions(container.appContext))
    val permissions: StateFlow<PermissionGrantState> = _permissions.asStateFlow()

    private val _visionUi = MutableStateFlow(LiveVisionUiState())
    val visionUi: StateFlow<LiveVisionUiState> = _visionUi.asStateFlow()

    private val _showAlarmSoundSheet = MutableStateFlow(false)
    val showAlarmSoundSheet: StateFlow<Boolean> = _showAlarmSoundSheet.asStateFlow()

    private val _showDestinationSheet = MutableStateFlow(false)
    val showDestinationSheet: StateFlow<Boolean> = _showDestinationSheet.asStateFlow()

    private val _showBackupSheet = MutableStateFlow(false)
    val showBackupSheet: StateFlow<Boolean> = _showBackupSheet.asStateFlow()

    private val _backupFeedback = MutableStateFlow<String?>(null)
    val backupFeedback: StateFlow<String?> = _backupFeedback.asStateFlow()

    private val _pendingRestorePreview = MutableStateFlow<BackupPreview?>(null)
    val pendingRestorePreview: StateFlow<BackupPreview?> = _pendingRestorePreview.asStateFlow()

    val isBackupSyncing: StateFlow<Boolean> = container.backupManager.isSyncing

    private val _donorboxPending = MutableStateFlow<DonorboxPendingReturn?>(null)
    val donorboxPending: StateFlow<DonorboxPendingReturn?> = _donorboxPending.asStateFlow()

    private val _debugPreviewScreen = MutableStateFlow<ObolScreenModel?>(null)
    val debugPreviewScreen: StateFlow<ObolScreenModel?> = _debugPreviewScreen.asStateFlow()

    private enum class LocalSessionMode {
        CLOSED,
        OPEN,
        OVERRIDE,
    }

    private var coordinatorMorningId: String? = null
    private var lastSyncedEventCount: Int = 0
    @Volatile
    private var minExpectedEventCount: Int = 0
    private var activeSessionMode: LocalSessionMode = LocalSessionMode.CLOSED
    private val pendingSessionMutations = AtomicInteger(0)
    private val sessionMutationChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var crossingCoordinator = CrossingCoordinator()

    val screenModel: StateFlow<ObolScreenModel> = combine(
        combine(
            repository.snapshot,
            _overlay,
            _permissions,
            _visionUi,
            _debugPreviewScreen,
        ) { snap, ov, perms, vis, debugScreen ->
            ScreenResolveInputs(snap, ov, perms, vis, debugScreen)
        },
        container.backupManager.isSyncing,
    ) { inputs, syncing ->
        inputs.debugScreen ?: ObolCopy.resolveScreen(
            snapshot = inputs.snap,
            overlay = inputs.ov,
            permissions = inputs.perms,
            vision = inputs.vis,
            isBackupSyncing = syncing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ObolCopy.resolveScreen(
            snapshot = repository.currentSnapshotNow(),
            overlay = CalmOverlay.None,
            permissions = _permissions.value,
            vision = _visionUi.value,
            isBackupSyncing = container.backupManager.isSyncing.value,
        ),
    )

    private data class ScreenResolveInputs(
        val snap: dev.prayush.obol.data.AppSnapshot,
        val ov: CalmOverlay,
        val perms: PermissionGrantState,
        val vis: LiveVisionUiState,
        val debugScreen: ObolScreenModel?,
    )

    init {
        container.appScope.launch {
            for (mutation in sessionMutationChannel) {
                runCatching { mutation() }
            }
        }

        syncCoordinatorForActiveMorning(repository.currentSnapshotNow().activeMorning)

        viewModelScope.launch {
            repository.snapshot.collect { snap ->
                syncCoordinatorForActiveMorning(snap.activeMorning)
            }
        }

        viewModelScope.launch {
            container.consumeFirstProcessReconcileFlag()
            val res = withContext(Dispatchers.Default) {
                repository.reconcile(isProcessOrBootRestart = false)
            }
            container.alarmScheduler.syncWithReconcileResult(res)
            syncCoordinatorForActiveMorning(res.activeMorning)
            if (res.needsImmediateAlarmResume) {
                AlarmRingingService.start(container.deviceProtectedContext)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                repository.notifyClockTick()
                val active = repository.currentSnapshotNow().activeMorning
                syncCoordinatorForActiveMorning(active)
                if (activeSessionMode == LocalSessionMode.OPEN && crossingCoordinator.isSessionOpen) {
                    val nowPoint = container.clock.timePoint()
                    val inFreeNow = active?.isWithinFreeWindowAt(nowPoint) ?: crossingCoordinator.isInFreeWindow
                    val tickOutcome = crossingCoordinator.onTimeTick(nowPoint, inFreeNow)
                    if (tickOutcome != null) {
                        _visionUi.value = _visionUi.value.copy(
                            accumulatedBrushMs = tickOutcome.accumulatedBrushMs,
                            isWindowPassing = tickOutcome.isWindowPassing,
                            failureReason = tickOutcome.failureReason,
                        )
                        if (tickOutcome.domainEvents.isNotEmpty()) {
                            enqueueSessionMutation {
                                var latest: DerivedMorning? = active
                                for (ev in tickOutcome.domainEvents) {
                                    latest = repository.appendActiveEvent(ev.event, ev.atPoint)
                                }
                                container.audioController.updateForMorning(latest)
                                latest
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enqueueSessionMutation(
        syncLatestToUi: Boolean = true,
        block: suspend () -> DerivedMorning?,
    ) {
        pendingSessionMutations.incrementAndGet()
        val sent = sessionMutationChannel.trySend {
            var latest: DerivedMorning? = null
            try {
                latest = block()
                if (latest != null && latest.eventCount > minExpectedEventCount) {
                    minExpectedEventCount = latest.eventCount
                }
            } finally {
                val remaining = pendingSessionMutations.decrementAndGet()
                if (remaining < 0) {
                    pendingSessionMutations.set(0)
                }
            }
            val resolved = latest
            if (syncLatestToUi && resolved != null) {
                viewModelScope.launch {
                    if (pendingSessionMutations.get() == 0) {
                        syncCoordinatorForActiveMorning(resolved)
                    }
                }
            }
        }
        if (!sent.isSuccess) {
            val remaining = pendingSessionMutations.decrementAndGet()
            if (remaining < 0) {
                pendingSessionMutations.set(0)
            }
        }
    }

    private fun syncCoordinatorForActiveMorning(active: DerivedMorning?) {
        if (active == null) {
            if (pendingSessionMutations.get() > 0) return
            coordinatorMorningId = null
            lastSyncedEventCount = 0
            minExpectedEventCount = 0
            activeSessionMode = LocalSessionMode.CLOSED
            if (crossingCoordinator.isSessionOpen || crossingCoordinator.isInOverride) {
                crossingCoordinator.clearOpenSessionState()
            }
            return
        }
        val nowPoint = container.clock.timePoint()
        val inFree = active.isWithinFreeWindowAt(nowPoint)
        if (active.header.morningId != coordinatorMorningId) {
            coordinatorMorningId = active.header.morningId
            lastSyncedEventCount = active.eventCount
            minExpectedEventCount = active.eventCount
            crossingCoordinator = CrossingCoordinator(
                initialAccumulatedBrushMs = active.verifiedBrushMs,
                startedDuringFreeWindow = inFree,
            )
            activeSessionMode = when {
                active.phase != MorningPhase.BRUSHING -> LocalSessionMode.CLOSED
                active.isInOverrideScreen -> {
                    crossingCoordinator.onOverrideOpened()
                    LocalSessionMode.OVERRIDE
                }
                else -> {
                    crossingCoordinator.onBrushingSessionOpened(
                        point = nowPoint,
                        inFreeWindow = inFree,
                        alreadyPausedInDomain = active.isBrushingSeen,
                    )
                    LocalSessionMode.OPEN
                }
            }
            _visionUi.value = LiveVisionUiState(
                accumulatedBrushMs = crossingCoordinator.accumulatedBrushMs,
                failureReason = if (inFree && !active.isBrushingSeen) {
                    VisionFailureReason.ACQUIRING
                } else {
                    VisionFailureReason.NO_TOOTHBRUSH
                },
            )
        } else {
            val revealedReason = crossingCoordinator.updateInFreeWindow(inFree, nowPoint)
            if (revealedReason != null && _visionUi.value.failureReason == VisionFailureReason.ACQUIRING) {
                _visionUi.value = _visionUi.value.copy(failureReason = revealedReason)
            }
            if (pendingSessionMutations.get() > 0) {
                return
            }
            val floorCount = maxOf(lastSyncedEventCount, minExpectedEventCount)
            if (active.eventCount < floorCount) {
                return
            }
            lastSyncedEventCount = active.eventCount
            when {
                active.phase != MorningPhase.BRUSHING -> {
                    activeSessionMode = LocalSessionMode.CLOSED
                    if (crossingCoordinator.isSessionOpen || crossingCoordinator.isInOverride) {
                        crossingCoordinator.clearOpenSessionState()
                    }
                }
                active.isInOverrideScreen -> {
                    activeSessionMode = LocalSessionMode.OVERRIDE
                    crossingCoordinator.onOverrideOpened()
                }
                else -> {
                    activeSessionMode = LocalSessionMode.OPEN
                    if (!crossingCoordinator.isSessionOpen) {
                        crossingCoordinator.onBrushingSessionOpened(
                            point = nowPoint,
                            inFreeWindow = inFree,
                            alreadyPausedInDomain = active.isBrushingSeen,
                        )
                    }
                }
            }
            if (active.verifiedBrushMs >= ObolConstants.BRUSH_TARGET_MS &&
                crossingCoordinator.accumulatedBrushMs < ObolConstants.BRUSH_TARGET_MS
            ) {
                crossingCoordinator.syncPersistedAccumulatedBrushMs(ObolConstants.BRUSH_TARGET_MS)
                _visionUi.value = _visionUi.value.copy(
                    accumulatedBrushMs = ObolConstants.BRUSH_TARGET_MS,
                )
            }
        }
    }

    override fun onCleared() {
        container.audioController.stopPreview()
        if (activeSessionMode != LocalSessionMode.CLOSED ||
            crossingCoordinator.isSessionOpen ||
            crossingCoordinator.isInOverride
        ) {
            closeBrushingCameraSession(syncUi = false)
        }
        sessionMutationChannel.close()
        super.onCleared()
    }

    fun refreshPermissions() {
        _permissions.value = readSystemPermissions(container.appContext)
    }

    fun onAppResumed() {
        refreshPermissions()
        viewModelScope.launch {
            runCatching { container.backupManager.flushIfDirty() }
        }
    }

    fun onToggleHistoryRange() {
        val current = _overlay.value
        val nextShowEarlier = if (current is CalmOverlay.History) !current.showEarlierMonths else true
        _overlay.value = CalmOverlay.History(showEarlierMonths = nextShowEarlier)
    }

    fun onOpenFullBill(morningId: String) {
        val fromEarlier = (_overlay.value as? CalmOverlay.History)?.showEarlierMonths == true
        _overlay.value = CalmOverlay.FullBill(morningId = morningId, returnToEarlier = fromEarlier)
    }

    fun onBackFromBill() {
        val current = _overlay.value
        val returnToEarlier = (current as? CalmOverlay.FullBill)?.returnToEarlier == true
        _overlay.value = CalmOverlay.History(showEarlierMonths = returnToEarlier)
    }

    fun onSetMeUp() {
        viewModelScope.launch {
            refreshPermissions()
            repository.acceptWelcome()
        }
    }

    fun onContinueFromPermissions() {
        viewModelScope.launch {
            refreshPermissions()
            if (_permissions.value.allGranted) {
                repository.acceptPermissions()
            }
        }
    }

    fun onFinishOnboarding() {
        viewModelScope.launch {
            repository.finishSetup()
            _overlay.value = CalmOverlay.None
        }
    }

    fun onUpdateAlarmSlot(dayOfWeek: DayOfWeek, enabled: Boolean, time: LocalTime) {
        viewModelScope.launch {
            repository.updateAlarmSlot(dayOfWeek, AlarmSlotConfig(enabled = enabled, time = time))
        }
    }

    fun onOpenOverlay(target: CalmOverlay) {
        if (target != CalmOverlay.Settings) {
            _showAlarmSoundSheet.value = false
            container.audioController.stopPreview()
        }
        _overlay.value = target
    }

    fun onDeclareGraceNight() {
        viewModelScope.launch {
            repository.declareGraceNightForNext()
        }
    }

    fun onUndoGraceNight() {
        viewModelScope.launch {
            repository.undoGraceNightForNext()
        }
    }

    fun onTogglePause() {
        viewModelScope.launch {
            val current = repository.currentSnapshotNow().pauseState
            val wantPause = !(current.isPaused || current.pendingPause != null)
            repository.setPauseRequested(wantPause)
        }
    }

    fun onMorningPrimaryAction() {
        val snap = repository.currentSnapshotNow()
        val snapActive = snap.activeMorning
        if (snap.isLoaded && snapActive != null && pendingSessionMutations.get() == 0) {
            handleMorningPrimaryFor(snapActive)
            return
        }
        if (snapActive != null &&
            !snapActive.header.isGraceNight &&
            activeSessionMode == LocalSessionMode.CLOSED &&
            snapActive.isActivePostFire &&
            snapActive.phase != MorningPhase.POST_CHECK_PAY_OR_ZERO
        ) {
            startBrushingCameraSession(snapActive)
            return
        }
        viewModelScope.launch {
            val active = repository.computeLiveSnapshot().activeMorning ?: return@launch
            handleMorningPrimaryFor(active)
        }
    }

    private fun handleMorningPrimaryFor(active: DerivedMorning) {
        when (active.phase) {
            MorningPhase.GRACE_NIGHT_RING -> {
                enqueueSessionMutation {
                    val updated = repository.appendActiveEvent(MorningEvent.GraceNightDismissed)
                    container.audioController.updateForMorning(updated)
                    updated
                }
            }
            MorningPhase.SNOOZED -> {
                if (active.header.isGraceNight) {
                    enqueueSessionMutation {
                        val updated = repository.appendActiveEvent(MorningEvent.GraceNightDismissed)
                        container.audioController.updateForMorning(updated)
                        updated
                    }
                } else {
                    startBrushingCameraSession(active)
                }
            }
            MorningPhase.FREE_WINDOW,
            MorningPhase.METERING,
            MorningPhase.CAPPED_RINGING,
            MorningPhase.BRUSHING,
            -> {
                if (activeSessionMode == LocalSessionMode.CLOSED) {
                    startBrushingCameraSession(active)
                }
            }
            else -> Unit
        }
    }

    private fun startBrushingCameraSession(active: DerivedMorning) {
        if (activeSessionMode != LocalSessionMode.CLOSED) return
        val point = container.clock.timePoint()
        val inFreeWindow = active.isWithinFreeWindowAt(point)
        if (active.header.morningId != coordinatorMorningId) {
            coordinatorMorningId = active.header.morningId
            lastSyncedEventCount = active.eventCount
            minExpectedEventCount = active.eventCount
            crossingCoordinator = CrossingCoordinator(
                initialAccumulatedBrushMs = active.verifiedBrushMs,
                startedDuringFreeWindow = inFreeWindow,
            )
        }
        activeSessionMode = LocalSessionMode.OPEN
        crossingCoordinator.onBrushingSessionOpened(
            point = point,
            inFreeWindow = inFreeWindow,
            alreadyPausedInDomain = false,
        )
        _visionUi.value = _visionUi.value.copy(
            accumulatedBrushMs = crossingCoordinator.accumulatedBrushMs,
            isWindowPassing = null,
            failureReason = if (inFreeWindow) VisionFailureReason.ACQUIRING else VisionFailureReason.NO_TOOTHBRUSH,
        )
        enqueueSessionMutation {
            val updated = repository.appendActiveEvent(MorningEvent.CameraOpened, point)
            container.audioController.updateForMorning(updated)
            updated
        }
    }

    private fun closeBrushingCameraSession(syncUi: Boolean = true) {
        val closePoint = container.clock.timePoint()
        val closeEvents = if (crossingCoordinator.isSessionOpen || crossingCoordinator.isInOverride) {
            crossingCoordinator.onBrushingSessionClosed(closePoint)
        } else {
            emptyList()
        }
        activeSessionMode = LocalSessionMode.CLOSED
        enqueueSessionMutation(syncLatestToUi = syncUi) {
            var latest: DerivedMorning? = null
            if (closeEvents.isNotEmpty()) {
                for (ev in closeEvents) {
                    latest = repository.appendActiveEvent(ev.event, ev.atPoint)
                }
            } else {
                val liveActive = repository.computeLiveSnapshot().activeMorning
                if (liveActive?.phase == MorningPhase.BRUSHING) {
                    if (liveActive.isBrushingSeen) {
                        repository.appendActiveEvent(MorningEvent.BrushingSightLost, closePoint)
                    }
                    latest = repository.appendActiveEvent(MorningEvent.CameraClosed, closePoint)
                }
            }
            container.audioController.updateForMorning(latest ?: repository.computeLiveSnapshot().activeMorning)
            latest
        }
    }

    fun onSnooze() {
        activeSessionMode = LocalSessionMode.CLOSED
        if (crossingCoordinator.isSessionOpen || crossingCoordinator.isInOverride) {
            crossingCoordinator.clearOpenSessionState()
        }
        enqueueSessionMutation {
            val active = repository.computeLiveSnapshot().activeMorning ?: return@enqueueSessionMutation null
            val nextCycle = active.snoozeCycle + 1
            val updated = repository.appendActiveEvent(
                MorningEvent.Snoozed(
                    cycle = nextCycle,
                    startDisplayPaise = active.displayPaise,
                ),
            )
            container.audioController.updateForMorning(updated)
            updated
        }
    }

    fun onOpenOverride() {
        activeSessionMode = LocalSessionMode.OVERRIDE
        crossingCoordinator.onOverrideOpened()
        _visionUi.value = _visionUi.value.copy(overrideTypedText = "")
        enqueueSessionMutation {
            val updated = repository.appendActiveEvent(MorningEvent.OverrideOpened)
            container.audioController.updateForMorning(updated)
            updated
        }
    }

    fun onOverrideTypedChange(newText: String) {
        _visionUi.value = _visionUi.value.copy(overrideTypedText = newText)
    }

    fun onSubmitOverride() {
        if (_visionUi.value.overrideTypedText.trim() != ObolConstants.OVERRIDE_TARGET_SENTENCE) return
        activeSessionMode = LocalSessionMode.CLOSED
        crossingCoordinator.clearOpenSessionState()
        _visionUi.value = _visionUi.value.copy(overrideTypedText = "")
        enqueueSessionMutation {
            val updated = repository.appendActiveEvent(MorningEvent.OverrideAccepted(ObolConstants.OVERRIDE_FEE_PAISE))
            container.audioController.updateForMorning(updated)
            updated
        }
    }

    fun onPostBrushDoneOrLater() {
        viewModelScope.launch {
            val active = repository.computeLiveSnapshot().activeMorning ?: return@launch
            if (active.totalPayableNowPaise < ObolConstants.MIN_DONATION_THRESHOLD_PAISE) {
                repository.appendActiveEvent(MorningEvent.MorningDismissed)
            } else {
                repository.appendActiveEvent(MorningEvent.PaymentDeferred)
            }
        }
    }

    fun onClosedSummaryDone() {
        viewModelScope.launch {
            repository.appendActiveEvent(MorningEvent.MorningDismissed)
            _overlay.value = CalmOverlay.None
        }
    }

    fun onSystemBackPressed(): Boolean {
        if (_debugPreviewScreen.value != null) {
            _debugPreviewScreen.value = null
            return true
        }
        if (_donorboxPending.value != null) {
            onDonorboxReturned(completed = false)
            return true
        }
        if (_pendingRestorePreview.value != null) {
            onCancelRestore()
            return true
        }
        if (_showBackupSheet.value) {
            setShowBackupSheet(false)
            return true
        }
        if (_showAlarmSoundSheet.value) {
            setShowAlarmSoundSheet(false)
            return true
        }
        if (_showDestinationSheet.value) {
            _showDestinationSheet.value = false
            return true
        }
        val currentOv = _overlay.value
        if (currentOv != CalmOverlay.None) {
            container.audioController.stopPreview()
            _overlay.value = when (currentOv) {
                is CalmOverlay.FullBill -> CalmOverlay.History(showEarlierMonths = currentOv.returnToEarlier)
                else -> CalmOverlay.None
            }
            return true
        }

        val active = repository.currentSnapshotNow().activeMorning
        if (activeSessionMode == LocalSessionMode.OVERRIDE ||
            (pendingSessionMutations.get() == 0 && active?.phase == MorningPhase.BRUSHING && active.isInOverrideScreen)
        ) {
            activeSessionMode = LocalSessionMode.OPEN
            crossingCoordinator.onOverrideClosed(container.clock.timePoint())
            enqueueSessionMutation {
                val updated = repository.appendActiveEvent(MorningEvent.OverrideClosed)
                container.audioController.updateForMorning(updated)
                updated
            }
            return true
        }
        if (activeSessionMode == LocalSessionMode.OPEN ||
            crossingCoordinator.isSessionOpen ||
            (pendingSessionMutations.get() == 0 && active?.phase == MorningPhase.BRUSHING)
        ) {
            closeBrushingCameraSession()
            return true
        }

        if (active == null) return false
        return when (active.phase) {
            MorningPhase.CLOSED_DONE,
            MorningPhase.CANCELLED,
            -> {
                onClosedSummaryDone()
                true
            }
            else -> true // Block accidental dismissal on active ringing/payment screens
        }
    }

    fun onActivityStopped(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        container.audioController.stopPreview()
        val snapActive = repository.currentSnapshotNow().activeMorning
        val shouldCloseSession = activeSessionMode != LocalSessionMode.CLOSED ||
            crossingCoordinator.isSessionOpen ||
            crossingCoordinator.isInOverride ||
            (pendingSessionMutations.get() == 0 && snapActive?.phase == MorningPhase.BRUSHING)
        if (shouldCloseSession) {
            closeBrushingCameraSession()
        }
    }

    fun onCameraFrameAnalyzed(analysis: VisionFrameAnalysis) {
        val snapActive = repository.currentSnapshotNow().activeMorning
        val point = container.clock.timePoint()
        if (snapActive != null) {
            syncCoordinatorForActiveMorning(snapActive)
        }
        val canAnalyze = activeSessionMode == LocalSessionMode.OPEN && crossingCoordinator.isSessionOpen
        if (canAnalyze) {
            val inFreeNow = snapActive?.isWithinFreeWindowAt(point) ?: crossingCoordinator.isInFreeWindow
            val outcome = crossingCoordinator.onBrushingFrame(
                analysis = analysis,
                point = point,
                inFreeWindow = inFreeNow,
            )
            if (outcome.isCompleted) {
                activeSessionMode = LocalSessionMode.CLOSED
            }
            _visionUi.value = _visionUi.value.copy(
                accumulatedBrushMs = outcome.accumulatedBrushMs,
                isWindowPassing = outcome.isWindowPassing,
                failureReason = outcome.failureReason,
            )
            if (snapActive?.isWithinFreeWindow == true && !inFreeNow) {
                repository.notifyClockTick()
            }
            if (outcome.domainEvents.isNotEmpty()) {
                enqueueSessionMutation {
                    var latest: DerivedMorning? = snapActive
                    for (item in outcome.domainEvents) {
                        latest = repository.appendActiveEvent(item.event, item.atPoint)
                    }
                    container.audioController.updateForMorning(latest)
                    latest
                }
            }
        }
    }

    fun onCameraHardwareError() {
        val point = container.clock.timePoint()
        val outcome = crossingCoordinator.onCameraError(point)
        _visionUi.value = _visionUi.value.copy(
            cameraErrorUnlockedOverride = outcome.isOverrideUnlocked,
            isWindowPassing = false,
            failureReason = VisionFailureReason.CAMERA_ERROR,
        )
        if (outcome.domainEvents.isNotEmpty()) {
            enqueueSessionMutation {
                var latest = repository.currentSnapshotNow().activeMorning
                for (ev in outcome.domainEvents) {
                    latest = repository.appendActiveEvent(ev.event, ev.atPoint)
                }
                container.audioController.updateForMorning(latest)
                latest
            }
        }
    }

    fun setShowAlarmSoundSheet(visible: Boolean) {
        _showAlarmSoundSheet.value = visible
        if (!visible) {
            container.audioController.stopPreview()
        }
    }

    fun stopAlarmPreview() {
        container.audioController.stopPreview()
    }

    fun onSelectAndPreviewBuiltInChime(chime: BuiltInChime) {
        val choice = AlarmSoundChoice.BuiltIn(chime)
        container.audioController.setAlarmSoundChoice(choice)
        container.audioController.togglePreview(choice)
        viewModelScope.launch {
            repository.updateAlarmSound(choice)
        }
    }

    fun onToggleSystemSoundPreview(choice: AlarmSoundChoice.SystemAlarm) {
        container.audioController.setAlarmSoundChoice(choice)
        container.audioController.togglePreview(choice)
        viewModelScope.launch {
            repository.updateAlarmSound(choice)
        }
    }

    fun onSystemAlarmSoundPicked(uriString: String, title: String) {
        if (uriString.isBlank()) return
        val choice = AlarmSoundChoice.SystemAlarm(
            uriString = uriString,
            title = title.ifBlank { "Pixel alarm" },
        )
        container.audioController.setAlarmSoundChoice(choice)
        container.audioController.startPreview(choice)
        viewModelScope.launch {
            repository.updateAlarmSound(choice)
        }
    }

    fun setShowDestinationSheet(visible: Boolean) {
        _showDestinationSheet.value = visible
    }

    fun setShowBackupSheet(visible: Boolean) {
        _showBackupSheet.value = visible
        if (!visible) {
            _backupFeedback.value = null
            _pendingRestorePreview.value = null
        }
    }

    fun onBackupFileCreated(uri: Uri) {
        viewModelScope.launch {
            _backupFeedback.value = null
            when (val res = container.backupManager.linkAndBackup(uri)) {
                is BackupOpResult.Success -> {
                    val word = if (res.morningCount == 1) "morning" else "mornings"
                    _backupFeedback.value = "Backed up ${res.morningCount} $word to Google Drive."
                }
                is BackupOpResult.Failure -> _backupFeedback.value = res.reason
                BackupOpResult.DeferredLocked -> _backupFeedback.value = "Unlock phone to finish backup."
                BackupOpResult.NotConfigured -> _backupFeedback.value = "No backup file selected."
            }
        }
    }

    fun onBackupNowClicked() {
        viewModelScope.launch {
            _backupFeedback.value = null
            when (val res = container.backupManager.backupNow()) {
                is BackupOpResult.Success -> {
                    val word = if (res.morningCount == 1) "morning" else "mornings"
                    _backupFeedback.value = "Backed up ${res.morningCount} $word."
                }
                is BackupOpResult.Failure -> _backupFeedback.value = res.reason
                BackupOpResult.DeferredLocked -> _backupFeedback.value = "Unlock phone to back up."
                BackupOpResult.NotConfigured -> _backupFeedback.value = "Choose a Google Drive file first."
            }
        }
    }

    fun onDisconnectBackupClicked() {
        viewModelScope.launch {
            container.backupManager.disconnectBackup()
            _backupFeedback.value = "Google Drive backup disconnected."
        }
    }

    fun onRestoreFilePicked(uri: Uri) {
        viewModelScope.launch {
            _backupFeedback.value = null
            val inspected = container.backupManager.inspectBackupUri(uri)
            inspected.onSuccess { preview ->
                _pendingRestorePreview.value = preview
            }.onFailure { err ->
                _showBackupSheet.value = true
                _backupFeedback.value = "Could not read backup: ${err.message ?: "invalid JSON"}"
            }
        }
    }

    fun onConfirmRestore() {
        val preview = _pendingRestorePreview.value ?: return
        viewModelScope.launch {
            refreshPermissions()
            val snap = repository.currentSnapshotNow()
            val isFreshNewPhone = snap.onboardingStep != OnboardingStep.COMPLETE && !_permissions.value.allGranted
            val result = container.backupManager.restoreFromPreview(
                preview = preview,
                resetPermissionsForNewPhone = isFreshNewPhone,
            )
            result.onSuccess { count ->
                val res = withContext(Dispatchers.Default) {
                    repository.reconcile(isProcessOrBootRestart = false)
                }
                container.alarmScheduler.syncWithReconcileResult(res)
                _pendingRestorePreview.value = null
                val word = if (count == 1) "morning" else "mornings"
                if (isFreshNewPhone) {
                    _showBackupSheet.value = false
                    _backupFeedback.value = null
                } else {
                    _backupFeedback.value = "Restored $count $word."
                }
            }.onFailure { err ->
                _pendingRestorePreview.value = null
                _backupFeedback.value = "Restore failed: ${err.message ?: "unknown error"}"
            }
        }
    }

    fun onCancelRestore() {
        _pendingRestorePreview.value = null
    }

    fun savePaymentDestination(destination: PaymentDestination) {
        viewModelScope.launch {
            repository.updatePaymentDestination(destination)
            _showDestinationSheet.value = false
        }
    }

    fun markAwaitingDonorboxReturn(amountPaise: Paise, isTestOnly: Boolean) {
        _donorboxPending.value = DonorboxPendingReturn(amountPaise = amountPaise, isTestOnly = isTestOnly)
    }

    fun onDonorboxReturned(completed: Boolean) {
        val pending = _donorboxPending.value ?: return
        _donorboxPending.value = null
        if (pending.isTestOnly) return
        viewModelScope.launch {
            if (completed) {
                repository.markPaidThrough(
                    expectedTotalPaise = pending.amountPaise,
                    railId = "donorbox_oppia",
                    ref = "donorbox-${container.clock.now().toEpochMilli()}",
                )
            } else {
                repository.recordPaymentFailure("Donorbox not completed")
            }
        }
    }

    fun onUpiPaymentCompleted(amountPaise: Paise, ref: String) {
        viewModelScope.launch {
            repository.markPaidThrough(amountPaise, "upi", ref)
        }
    }

    fun onUpiPaymentSubmitted(amountPaise: Paise, ref: String) {
        viewModelScope.launch {
            repository.recordPendingSubmission(amountPaise, ref)
        }
    }

    fun onPaymentFailed(reason: String) {
        viewModelScope.launch {
            repository.recordPaymentFailure(reason)
        }
    }

    fun onConfirmPendingUpiFromHome() {
        viewModelScope.launch {
            val snap = repository.computeLiveSnapshot()
            val pending = snap.pendingUpiSubmission ?: return@launch
            repository.markPaidThrough(
                expectedTotalPaise = pending.amountPaise,
                railId = "upi",
                ref = pending.ref,
            )
        }
    }

    // ---------- Debug Simulation Hooks ----------
    fun setDebugPreviewScreen(screen: ObolScreenModel?) {
        if (!BuildConfig.DEBUG) return
        _debugPreviewScreen.value = screen
    }

    fun debugAdvanceClockMs(deltaMs: Long) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            container.controllableClock.advanceByMs(deltaMs)
            val res = repository.reconcile(isProcessOrBootRestart = false)
            container.alarmScheduler.syncWithReconcileResult(res)
            container.audioController.updateForMorning(res.activeMorning)
        }
    }

    fun debugSimulateReboot(downtimeMs: Long) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            container.controllableClock.simulateReboot(downtimeMs)
            val res = repository.reconcile(isProcessOrBootRestart = true)
            container.alarmScheduler.syncWithReconcileResult(res)
            if (res.needsImmediateAlarmResume) {
                AlarmRingingService.start(container.deviceProtectedContext)
            }
        }
    }

    fun debugResetClockOffsets() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            container.controllableClock.resetOffsets()
            repository.notifyClockTick()
        }
    }

    fun debugSkipOnboarding() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            repository.finishSetup()
        }
    }

    fun debugTriggerAlarmNow(graceNight: Boolean = false) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            val active = repository.triggerImmediateAlarmNow(forceGraceNight = graceNight)
            coordinatorMorningId = active.header.morningId
            lastSyncedEventCount = active.eventCount
            minExpectedEventCount = active.eventCount
            activeSessionMode = LocalSessionMode.CLOSED
            crossingCoordinator = CrossingCoordinator(
                initialAccumulatedBrushMs = active.verifiedBrushMs,
                startedDuringFreeWindow = active.isWithinFreeWindow,
            )
            _visionUi.value = LiveVisionUiState(
                accumulatedBrushMs = active.verifiedBrushMs,
                failureReason = if (active.isWithinFreeWindow) VisionFailureReason.ACQUIRING else VisionFailureReason.NO_TOOTHBRUSH,
            )
            if (active.requiresAlarmService) {
                AlarmRingingService.start(container.deviceProtectedContext)
            }
        }
    }

    fun debugSimulateBrushingPassComplete() {
        if (!BuildConfig.DEBUG) return
        activeSessionMode = LocalSessionMode.CLOSED
        crossingCoordinator.clearOpenSessionState()
        enqueueSessionMutation {
            val active = repository.computeLiveSnapshot().activeMorning ?: return@enqueueSessionMutation null
            if (active.phase != MorningPhase.BRUSHING) {
                repository.appendActiveEvent(MorningEvent.CameraOpened)
            }
            repository.appendActiveEvent(MorningEvent.BrushingPausedMeter)
            container.controllableClock.advanceByMs(ObolConstants.BRUSH_TARGET_MS)
            _visionUi.value = _visionUi.value.copy(
                accumulatedBrushMs = ObolConstants.BRUSH_TARGET_MS,
                failureReason = VisionFailureReason.NONE,
            )
            val updated = repository.appendActiveEvent(MorningEvent.BrushCompleted)
            container.audioController.updateForMorning(updated)
            updated
        }
    }

    fun debugSimulateBrushingSeenStep() {
        if (!BuildConfig.DEBUG) return
        val face = DetectedFaceInfo(
            bounds = UprightBox(140f, 160f, 340f, 380f),
            mouthMidX = 240f,
            mouthMidY = 320f,
            noseX = 240f,
            noseY = 270f,
            leftEyeOpenProb = 0.9f,
            rightEyeOpenProb = 0.9f,
        )
        repeat(3) { idx ->
            container.controllableClock.advanceByMs(200L)
            val shift = idx * 3f
            onCameraFrameAnalyzed(
                VisionFrameAnalysis(
                    frameWidth = 480,
                    frameHeight = 640,
                    meanLuma = 120f,
                    faces = listOf(face.copy(noseX = 240f + shift)),
                    toothbrushBoxes = listOf(UprightBox(220f + shift, 305f, 310f + shift, 335f)),
                ),
            )
        }
    }

    companion object {
        fun readSystemPermissions(context: Context): PermissionGrantState {
            val am = context.getSystemService(AlarmManager::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)

            val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am?.canScheduleExactAlarms() ?: true
            } else {
                true
            }
            val fsiOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nm?.canUseFullScreenIntent() ?: true
            } else {
                true
            }
            val camOk = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

            val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                nm?.areNotificationsEnabled() ?: true
            }

            val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

            return PermissionGrantState(
                alarmsAllowed = exactOk && fsiOk,
                cameraAllowed = camOk,
                notificationsAllowed = notifOk,
                batteryUnrestricted = batteryOk,
            )
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ObolViewModel(container) as T
                }
            }
    }
}
