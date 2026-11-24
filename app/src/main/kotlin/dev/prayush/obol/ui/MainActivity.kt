package dev.prayush.obol.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.prayush.obol.ObolApplication
import dev.prayush.obol.debug.DebugDrawerOverlay
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.PaymentDestination
import dev.prayush.obol.payment.DestinationSetupSheet
import dev.prayush.obol.payment.DonorboxReturnSheet
import dev.prayush.obol.payment.PaymentController
import dev.prayush.obol.payment.UpiParseResult
import dev.prayush.obol.vision.CameraViewfinder

class MainActivity : ComponentActivity() {

    private val viewModel: ObolViewModel by viewModels {
        ObolViewModel.factory((application as ObolApplication).container)
    }

    private var pendingUpiAmount: Paise? = null
    private var pendingUpiIsTest: Boolean = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissions()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissions()
        }

    private val settingsResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissions()
        }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val pickedUri = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } ?: return@registerForActivityResult

            runCatching {
                contentResolver.takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val rawTitle = runCatching {
                RingtoneManager.getRingtone(this, pickedUri)?.getTitle(this)
            }.getOrNull()?.trim().orEmpty()
            val queryTitle = runCatching { pickedUri.getQueryParameter("title") }.getOrNull()?.trim().orEmpty()
            val title = when {
                rawTitle.isNotBlank() && !rawTitle.contains("Unknown", ignoreCase = true) -> rawTitle
                queryTitle.isNotBlank() -> queryTitle
                pickedUri == Settings.System.DEFAULT_ALARM_ALERT_URI -> "Default Pixel alarm"
                else -> "Pixel alarm"
            }

            viewModel.onSystemAlarmSoundPicked(pickedUri.toString(), title)
        }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.onBackupFileCreated(uri)
        }

    private val openBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            viewModel.onRestoreFilePicked(uri)
        }

    private val upiResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val expected = pendingUpiAmount
            val isTest = pendingUpiIsTest
            pendingUpiAmount = null
            pendingUpiIsTest = false
            if (expected == null || isTest) return@registerForActivityResult

            val parsed = PaymentController.parseUpiResult(
                resultCode = result.resultCode,
                data = result.data,
                fallbackTimestampMs = viewModel.container.clock.now().toEpochMilli(),
            )
            when (parsed) {
                is UpiParseResult.Success -> viewModel.onUpiPaymentCompleted(expected, parsed.txnRef)
                is UpiParseResult.Submitted -> viewModel.onUpiPaymentSubmitted(expected, parsed.txnRef)
                is UpiParseResult.Failed -> viewModel.onPaymentFailed(parsed.reason)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(KEY_PENDING_UPI_PAISE)) {
                val raw = savedInstanceState.getLong(KEY_PENDING_UPI_PAISE, -1L)
                if (raw >= 0L) {
                    pendingUpiAmount = Paise(raw)
                }
            }
            pendingUpiIsTest = savedInstanceState.getBoolean(KEY_PENDING_UPI_IS_TEST, false)
            if (savedInstanceState.containsKey(KEY_PENDING_DONORBOX_PAISE) &&
                viewModel.donorboxPending.value == null
            ) {
                val raw = savedInstanceState.getLong(KEY_PENDING_DONORBOX_PAISE, -1L)
                if (raw >= 0L) {
                    viewModel.markAwaitingDonorboxReturn(
                        amountPaise = Paise(raw),
                        isTestOnly = savedInstanceState.getBoolean(KEY_PENDING_DONORBOX_IS_TEST, false),
                    )
                }
            }
        }

        setContent {
            val snapshot by viewModel.container.repository.snapshot.collectAsStateWithLifecycle()
            val screenModel by viewModel.screenModel.collectAsStateWithLifecycle()
            val showSoundSheet by viewModel.showAlarmSoundSheet.collectAsStateWithLifecycle()
            val previewingSound by viewModel.container.audioController.previewingChoice.collectAsStateWithLifecycle()
            val showDestSheet by viewModel.showDestinationSheet.collectAsStateWithLifecycle()
            val showBackupSheet by viewModel.showBackupSheet.collectAsStateWithLifecycle()
            val isBackupSyncing by viewModel.isBackupSyncing.collectAsStateWithLifecycle()
            val backupFeedback by viewModel.backupFeedback.collectAsStateWithLifecycle()
            val pendingRestorePreview by viewModel.pendingRestorePreview.collectAsStateWithLifecycle()
            val donorboxPending by viewModel.donorboxPending.collectAsStateWithLifecycle()

            val active = snapshot.activeMorning
            val keepScreenOn = active?.isActivePostFire == true
            LaunchedEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val interceptBack = showSoundSheet ||
                showDestSheet ||
                showBackupSheet ||
                pendingRestorePreview != null ||
                donorboxPending != null ||
                snapshot.activeMorning != null ||
                screenModel is ObolScreenModel.Alarms ||
                screenModel is ObolScreenModel.HistoryList ||
                screenModel is ObolScreenModel.FullBill ||
                screenModel is ObolScreenModel.Settings

            BackHandler(enabled = interceptBack) {
                viewModel.onSystemBackPressed()
            }

            val callbacks = ObolScreenCallbacks(
                onSetMeUp = viewModel::onSetMeUp,
                onRestoreFromBackup = ::launchOpenBackupPicker,
                onRequestPermission = ::handlePermissionRequest,
                onContinuePermissions = viewModel::onContinueFromPermissions,
                onBackToHome = { viewModel.onOpenOverlay(CalmOverlay.None) },
                onUpdateSlot = viewModel::onUpdateAlarmSlot,
                onFinishOnboarding = viewModel::onFinishOnboarding,
                onHomePrimary = {
                    val m = screenModel as? ObolScreenModel.Home
                    if (m?.isPaused == true) {
                        viewModel.onTogglePause()
                    } else if (snapshot.totalUnpaidPaise >= ObolConstants.MIN_DONATION_THRESHOLD_PAISE) {
                        initiateMorningPayment(snapshot.totalUnpaidPaise)
                    }
                },
                onDeclareGraceNight = viewModel::onDeclareGraceNight,
                onUndoGraceNight = viewModel::onUndoGraceNight,
                onConfirmPendingUpi = viewModel::onConfirmPendingUpiFromHome,
                onOpenAlarms = { viewModel.onOpenOverlay(CalmOverlay.Alarms) },
                onOpenHistory = { viewModel.onOpenOverlay(CalmOverlay.History()) },
                onOpenSettings = { viewModel.onOpenOverlay(CalmOverlay.Settings) },
                onMorningPrimary = viewModel::onMorningPrimaryAction,
                onSnooze = viewModel::onSnooze,
                onOpenOverride = viewModel::onOpenOverride,
                onOverrideTypedChange = viewModel::onOverrideTypedChange,
                onSubmitOverride = viewModel::onSubmitOverride,
                onSettlePrimary = {
                    val m = screenModel as? ObolScreenModel.PostBrushSettle
                    if (m?.isNothingOwed == true) {
                        viewModel.onPostBrushDoneOrLater()
                    } else {
                        val payable = snapshot.activeMorning?.totalPayableNowPaise ?: snapshot.totalUnpaidPaise
                        initiateMorningPayment(payable)
                    }
                },
                onSettleLater = viewModel::onPostBrushDoneOrLater,
                onClosedDone = viewModel::onClosedSummaryDone,
                onSeeFullBill = viewModel::onOpenFullBill,
                onShowEarlierHistory = viewModel::onToggleHistoryRange,
                onBackFromBill = viewModel::onBackFromBill,
                onOpenAlarmSoundPicker = { viewModel.setShowAlarmSoundSheet(true) },
                onOpenDestinationSetup = { viewModel.setShowDestinationSheet(true) },
                onOpenBackupSheet = { viewModel.setShowBackupSheet(true) },
                onTogglePause = viewModel::onTogglePause,
            )

            val needsLiveCamera = viewModel.debugPreviewScreen.collectAsStateWithLifecycle().value == null &&
                screenModel is ObolScreenModel.BrushingCamera &&
                viewModel.permissions.collectAsStateWithLifecycle().value.cameraAllowed

            Box(modifier = Modifier.fillMaxSize()) {
                ObolScreenRenderer(
                    model = screenModel,
                    callbacks = callbacks,
                    cameraContent = if (needsLiveCamera) {
                        {
                            CameraViewfinder(
                                detector = viewModel.container.visionDetector,
                                clock = viewModel.container.clock,
                                detectToothbrush = true,
                                onFrameAnalyzed = viewModel::onCameraFrameAnalyzed,
                                onCameraError = viewModel::onCameraHardwareError,
                            )
                        }
                    } else {
                        null
                    },
                )

                if (showSoundSheet) {
                    val savedSystemAlarm = (snapshot.alarmSound as? AlarmSoundChoice.SystemAlarm)
                        ?: snapshot.lastSystemAlarm
                    AlarmSoundSheet(
                        selectedChoice = snapshot.alarmSound,
                        previewingChoice = previewingSound,
                        onSelectAndPreviewBuiltIn = viewModel::onSelectAndPreviewBuiltInChime,
                        onToggleSystemPreview = viewModel::onToggleSystemSoundPreview,
                        onLaunchSystemPicker = {
                            launchSystemAlarmPicker(savedSystemAlarm ?: snapshot.alarmSound)
                        },
                        onDismiss = { viewModel.setShowAlarmSoundSheet(false) },
                        systemAlarmOption = savedSystemAlarm,
                    )
                }

                if (showDestSheet) {
                    DestinationSetupSheet(
                        destinationState = snapshot.destinationState,
                        onSaveUpi = { vpa, name ->
                            viewModel.savePaymentDestination(PaymentDestination.Upi(vpa = vpa, payeeName = name))
                        },
                        onSaveDonorbox = {
                            viewModel.savePaymentDestination(PaymentDestination.DonorboxOppia)
                        },
                        onSendTenRupeeTestUpi = { vpa, name ->
                            PaymentController.runAfterKeyguardDismissed(
                                activity = this@MainActivity,
                                onUnlocked = {
                                    launchUpiIntent(
                                        destination = PaymentDestination.Upi(vpa = vpa, payeeName = name),
                                        amountPaise = ObolConstants.TEST_PAYMENT_PAISE,
                                        txRef = "obol-test-${viewModel.container.clock.now().toEpochMilli()}",
                                        isTest = true,
                                    )
                                },
                                onDismissFailed = {},
                            )
                        },
                        onSendTenRupeeTestDonorbox = {
                            PaymentController.runAfterKeyguardDismissed(
                                activity = this@MainActivity,
                                onUnlocked = {
                                    launchDonorboxFlow(
                                        amountPaise = ObolConstants.DONORBOX_TEST_PAYMENT_PAISE,
                                        isTest = true,
                                    )
                                },
                                onDismissFailed = {},
                            )
                        },
                        onDismiss = { viewModel.setShowDestinationSheet(false) },
                    )
                }

                if (showBackupSheet || pendingRestorePreview != null) {
                    BackupSheet(
                        backupUri = snapshot.backupUri,
                        backupDisplayName = snapshot.backupDisplayName,
                        lastBackupAt = snapshot.lastBackupAt,
                        lastBackupError = snapshot.lastBackupError,
                        isSyncing = isBackupSyncing,
                        morningCount = snapshot.mornings.size,
                        zone = snapshot.zone,
                        feedbackMessage = backupFeedback,
                        pendingRestorePreview = pendingRestorePreview,
                        onChooseBackupFile = ::launchCreateBackupPicker,
                        onBackupNow = viewModel::onBackupNowClicked,
                        onPickRestoreFile = ::launchOpenBackupPicker,
                        onConfirmRestore = viewModel::onConfirmRestore,
                        onCancelRestore = viewModel::onCancelRestore,
                        onDisconnect = viewModel::onDisconnectBackupClicked,
                        onDismiss = { viewModel.setShowBackupSheet(false) },
                    )
                }

                val pendingDonorbox = donorboxPending
                if (pendingDonorbox != null) {
                    DonorboxReturnSheet(
                        amountPaise = pendingDonorbox.amountPaise,
                        isTestOnly = pendingDonorbox.isTestOnly,
                        onConfirmedPaid = { viewModel.onDonorboxReturned(completed = true) },
                        onNotCompleted = { viewModel.onDonorboxReturned(completed = false) },
                    )
                }

                DebugDrawerOverlay(viewModel = viewModel)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingUpiAmount?.let {
            outState.putLong(KEY_PENDING_UPI_PAISE, it.value)
            outState.putBoolean(KEY_PENDING_UPI_IS_TEST, pendingUpiIsTest)
        }
        viewModel.donorboxPending.value?.let { pending ->
            outState.putLong(KEY_PENDING_DONORBOX_PAISE, pending.amountPaise.value)
            outState.putBoolean(KEY_PENDING_DONORBOX_IS_TEST, pending.isTestOnly)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    override fun onStop() {
        viewModel.onActivityStopped(isChangingConfigurations)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isMuteOrDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        val isVolumeUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val requiresAlarm = viewModel.container.repository.currentSnapshotNow().activeMorning?.requiresAlarmService == true
        if ((isMuteOrDown && requiresAlarm) ||
            ((isMuteOrDown || isVolumeUp) && viewModel.container.audioController.isChimeAudible)
        ) {
            viewModel.container.audioController.enforceMinimumAlarmVolume()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchCreateBackupPicker() {
        PaymentController.runAfterKeyguardDismissed(
            activity = this,
            onUnlocked = {
                runCatching {
                    createBackupLauncher.launch("obol-backup.json")
                }
            },
            onDismissFailed = {},
        )
    }

    private fun launchOpenBackupPicker() {
        PaymentController.runAfterKeyguardDismissed(
            activity = this,
            onUnlocked = {
                runCatching {
                    openBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            },
            onDismissFailed = {},
        )
    }

    private fun launchSystemAlarmPicker(currentSound: AlarmSoundChoice) {
        viewModel.stopAlarmPreview()
        val existingUri = (currentSound as? AlarmSoundChoice.SystemAlarm)
            ?.uriString
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        PaymentController.runAfterKeyguardDismissed(
            activity = this,
            onUnlocked = {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
                }
                runCatching {
                    ringtonePickerLauncher.launch(intent)
                }
            },
            onDismissFailed = {},
        )
    }

    @SuppressLint("BatteryLife")
    private fun handlePermissionRequest(kind: PermissionKind) {
        val pkgUri = Uri.parse("package:$packageName")
        when (kind) {
            PermissionKind.ALARMS -> {
                val am = getSystemService(AlarmManager::class.java)
                val nm = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am?.canScheduleExactAlarms() == false) {
                    runCatching {
                        settingsResultLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
                        )
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    nm?.canUseFullScreenIntent() == false
                ) {
                    runCatching {
                        settingsResultLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri),
                        )
                    }
                } else {
                    viewModel.refreshPermissions()
                }
            }
            PermissionKind.CAMERA -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            PermissionKind.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        settingsResultLauncher.launch(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            },
                        )
                    }
                }
            }
            PermissionKind.BATTERY -> {
                runCatching {
                    settingsResultLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                    )
                }.recoverCatching {
                    settingsResultLauncher.launch(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                }
            }
        }
    }

    private fun initiateMorningPayment(amountPaise: Paise) {
        if (amountPaise.isZero) return
        val snap = viewModel.container.repository.currentSnapshotNow()
        val activeDest = snap.destinationState.active
        if (activeDest == PaymentDestination.Unset) {
            viewModel.setShowDestinationSheet(true)
            return
        }
        val txRef = snap.activeMorning?.header?.morningId
            ?: "obol-${viewModel.container.clock.now().toEpochMilli()}"

        PaymentController.runAfterKeyguardDismissed(
            activity = this,
            onUnlocked = {
                when (activeDest) {
                    is PaymentDestination.Upi -> launchUpiIntent(
                        destination = activeDest,
                        amountPaise = amountPaise,
                        txRef = txRef,
                        isTest = false,
                    )
                    PaymentDestination.DonorboxOppia -> launchDonorboxFlow(
                        amountPaise = amountPaise,
                        isTest = false,
                    )
                    PaymentDestination.Unset -> viewModel.setShowDestinationSheet(true)
                }
            },
            onDismissFailed = { reason ->
                viewModel.onPaymentFailed(reason)
            },
        )
    }

    private fun launchUpiIntent(
        destination: PaymentDestination.Upi,
        amountPaise: Paise,
        txRef: String,
        isTest: Boolean,
    ) {
        val uri = runCatching {
            PaymentController.buildUpiPayUri(destination, amountPaise, txRef)
        }.getOrElse {
            if (!isTest) viewModel.onPaymentFailed("Invalid UPI destination")
            return
        }
        pendingUpiAmount = amountPaise
        pendingUpiIsTest = isTest
        try {
            upiResultLauncher.launch(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            pendingUpiAmount = null
            pendingUpiIsTest = false
            if (!isTest) {
                viewModel.onPaymentFailed("No UPI app installed")
            }
        }
    }

    private fun launchDonorboxFlow(
        amountPaise: Paise,
        isTest: Boolean,
    ) {
        val launched = PaymentController.launchDonorboxCustomTab(this, amountPaise)
        if (launched) {
            viewModel.markAwaitingDonorboxReturn(amountPaise = amountPaise, isTestOnly = isTest)
        } else if (!isTest) {
            viewModel.onPaymentFailed("Browser unavailable")
        }
    }

    companion object {
        const val KEY_PENDING_UPI_PAISE = "pending_upi_paise"
        const val KEY_PENDING_UPI_IS_TEST = "pending_upi_is_test"
        const val KEY_PENDING_DONORBOX_PAISE = "pending_donorbox_paise"
        const val KEY_PENDING_DONORBOX_IS_TEST = "pending_donorbox_is_test"
    }
}
