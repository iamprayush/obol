package dev.prayush.obol.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import dev.prayush.obol.design.LocalObolScale
import dev.prayush.obol.design.ObolChip
import dev.prayush.obol.design.ObolDividerRow
import dev.prayush.obol.design.ObolDryLine
import dev.prayush.obol.design.ObolGhostPill
import dev.prayush.obol.design.ObolHeroText
import dev.prayush.obol.design.ObolNote
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.design.ObolPrimaryPill
import dev.prayush.obol.design.ObolQuietLabel
import dev.prayush.obol.design.ObolRowsContainer
import dev.prayush.obol.design.ObolScreenFrame
import dev.prayush.obol.design.ObolStatusRow
import dev.prayush.obol.design.ObolToggle
import dev.prayush.obol.design.ObolTypography
import dev.prayush.obol.design.RupeeSansText
import dev.prayush.obol.design.SunHaloMode
import java.time.DayOfWeek
import java.time.LocalTime

@Composable
fun WelcomeScreen(
    model: ObolScreenModel.Welcome,
    onSetMeUp: () -> Unit,
    onRestoreFromBackup: () -> Unit = {},
) {
    val stage = ObolPalette.CalmStage
    ObolScreenFrame(
        stage = stage,
        sunGeometry = ObolPalette.RestingSunGeometry,
        haloMode = SunHaloMode.SINGLE_RESTING,
        showStars = true,
        showGround = true,
    ) {
        ObolQuietLabel(text = "Hello. I'm", topDp = 104f, inkColor = stage.skyInk)
        ObolHeroText(text = "obol", inkColor = stage.skyInk, sizeSp = 140f, topDp = 118f)
        ObolDryLine(
            text = "An alarm clock that bills\nyou for staying in bed.",
            inkColor = stage.skyInk,
            topDp = 300f,
        )
        ObolQuietLabel(
            text = "5 free minutes after the alarm.\nThen ₹20 a minute.\nTen seconds of brushing stops it.",
            topDp = 388f,
            inkColor = stage.skyInk,
        )
        ObolPrimaryPill(text = "Set me up", onClick = onSetMeUp, topDp = 634f)
        ObolNote(text = "Up to ₹600 a morning. Three nights off a month.", topDp = 712f)
        ObolNote(
            text = "Restore from backup",
            topDp = 748f,
            underlined = true,
            onClick = onRestoreFromBackup,
            tag = "welcome_restore_link",
        )
    }
}

enum class PermissionKind {
    ALARMS,
    CAMERA,
    NOTIFICATIONS,
    BATTERY,
}

@Composable
fun PermissionsScreen(
    model: ObolScreenModel.Permissions,
    onRequestPermission: (PermissionKind) -> Unit,
    onContinue: () -> Unit,
) {
    val stage = ObolPalette.CalmStage
    val scale = LocalObolScale.current
    val grants = model.grants

    ObolScreenFrame(
        stage = stage,
        sunGeometry = ObolPalette.RestingSunGeometry,
        haloMode = SunHaloMode.SINGLE_RESTING,
        showStars = false,
        showGround = true,
    ) {
        ObolQuietLabel(text = "Before we start", topDp = 104f, inkColor = stage.skyInk)
        ObolDryLine(
            text = "I need four things. None\nof them are your contacts.",
            inkColor = stage.skyInk,
            topDp = 132f,
        )

        val items = listOf(
            Triple("Alarms", grants.alarmsAllowed, PermissionKind.ALARMS),
            Triple("Camera", grants.cameraAllowed, PermissionKind.CAMERA),
            Triple("Notifications", grants.notificationsAllowed, PermissionKind.NOTIFICATIONS),
            Triple("Battery, unrestricted", grants.batteryUnrestricted, PermissionKind.BATTERY),
        )

        ObolRowsContainer(topDp = 232f) {
            items.forEach { (title, allowed, kind) ->
                ObolDividerRow(heightDp = 64f, inkColor = stage.skyInk) {
                    RupeeSansText(
                        text = title,
                        color = stage.skyInk,
                        sizeSp = 15f * scale.uniform,
                        weight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (allowed) {
                        RupeeSansText(
                            text = "Allowed",
                            color = stage.skyInk.copy(alpha = 0.58f),
                            sizeSp = 14f * scale.uniform,
                            weight = FontWeight.Medium,
                        )
                    } else {
                        ObolChip(
                            text = "Allow",
                            onClick = { onRequestPermission(kind) },
                        )
                    }
                }
            }
        }

        if (grants.allGranted) {
            ObolPrimaryPill(text = "Continue", onClick = onContinue, topDp = 634f)
            ObolNote(text = "All four allowed.", topDp = 712f)
        } else {
            ObolGhostPill(text = "Continue", topDp = 634f, enabled = false)
            ObolNote(text = "${grants.remainingCount} to go.", topDp = 712f)
        }
    }
}

@Composable
fun AlarmsScreen(
    model: ObolScreenModel.Alarms,
    onBack: () -> Unit,
    onUpdateSlot: (DayOfWeek, Boolean, LocalTime) -> Unit,
    onFinishOnboarding: () -> Unit,
) {
    val stage = ObolPalette.CalmStage
    val context = LocalContext.current

    ObolScreenFrame(
        stage = stage,
        haloMode = SunHaloMode.NONE,
        showStars = false,
        showGround = false,
    ) { scale ->
        ObolStatusRow(
            leftText = if (model.isOnboarding) "Setup" else "‹ Home",
            inkColor = stage.skyInk,
            onLeftClick = if (model.isOnboarding) null else onBack,
        )
        ObolDryLine(
            text = "Your week, on your terms.\nStill costs ₹20 a minute.",
            inkColor = stage.skyInk,
            topDp = 96f,
        )

        ObolRowsContainer(topDp = 184f) {
            model.rows.forEach { row ->
                ObolDividerRow(heightDp = 58f, inkColor = stage.skyInk) {
                    Column {
                        RupeeSansText(
                            text = row.dayName,
                            color = stage.skyInk,
                            sizeSp = 15f * scale.uniform,
                            weight = FontWeight.Medium,
                            lineHeightFactor = 1.25f,
                        )
                        if (row.pendingCaption != null) {
                            RupeeSansText(
                                text = row.pendingCaption,
                                color = stage.skyInk.copy(alpha = 0.60f),
                                sizeSp = 12f * scale.uniform,
                                weight = FontWeight.Medium,
                                lineHeightFactor = 1.2f,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    val alarmBleedSp = 8f * scale.uniform
                    Text(
                        text = row.timeText,
                        color = if (row.enabled) stage.skyInk else stage.skyInk.copy(alpha = 0.35f),
                        style = ObolTypography.alarmListTimeStyle().let { s ->
                            s.copy(
                                fontSize = s.fontSize * scale.uniform,
                                lineHeight = s.lineHeight * scale.uniform,
                            )
                        },
                        textAlign = TextAlign.Center,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier
                            .padding(end = scale.x(18f))
                            .clickable {
                                runCatching {
                                    TimePickerDialog(
                                        context,
                                        { _, h, m ->
                                            onUpdateSlot(row.dayOfWeek, true, LocalTime.of(h, m))
                                        },
                                        row.hour,
                                        row.minute,
                                        true,
                                    ).show()
                                }
                            }
                            .layout { measurable, constraints ->
                                val bleedPx = alarmBleedSp.sp.roundToPx()
                                val naturalWidth = measurable.maxIntrinsicWidth(Constraints.Infinity)
                                val paddedWidth = naturalWidth + bleedPx * 2
                                val placeable = measurable.measure(
                                    constraints.copy(minWidth = paddedWidth, maxWidth = paddedWidth),
                                )
                                val logicalWidth = naturalWidth.coerceAtLeast(1)
                                layout(logicalWidth, placeable.height) {
                                    placeable.placeRelative(-bleedPx, 0)
                                }
                            },
                    )
                    ObolToggle(
                        checked = row.enabled && !row.hasPendingDisable,
                        onToggle = {
                            val desiredEnabled = if (row.hasPendingDisable) true else !row.enabled
                            onUpdateSlot(
                                row.dayOfWeek,
                                desiredEnabled,
                                LocalTime.of(row.hour, row.minute),
                            )
                        },
                    )
                }
            }
        }

        ObolQuietLabel(
            text = "Changes take effect right away.",
            topDp = 614f,
            inkColor = stage.skyInk,
        )

        if (model.isOnboarding) {
            ObolPrimaryPill(
                text = "Done",
                onClick = onFinishOnboarding,
                topDp = 688f,
            )
        }
    }
}

data class ObolScreenCallbacks(
    val onSetMeUp: () -> Unit = {},
    val onRestoreFromBackup: () -> Unit = {},
    val onRequestPermission: (PermissionKind) -> Unit = {},
    val onContinuePermissions: () -> Unit = {},
    val onBackToHome: () -> Unit = {},
    val onUpdateSlot: (DayOfWeek, Boolean, LocalTime) -> Unit = { _, _, _ -> },
    val onFinishOnboarding: () -> Unit = {},
    val onHomePrimary: () -> Unit = {},
    val onDeclareGraceNight: () -> Unit = {},
    val onUndoGraceNight: () -> Unit = {},
    val onConfirmPendingUpi: () -> Unit = {},
    val onOpenAlarms: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onMorningPrimary: () -> Unit = {},
    val onSnooze: () -> Unit = {},
    val onOpenOverride: () -> Unit = {},
    val onOverrideTypedChange: (String) -> Unit = {},
    val onSubmitOverride: () -> Unit = {},
    val onSettlePrimary: () -> Unit = {},
    val onSettleLater: () -> Unit = {},
    val onClosedDone: () -> Unit = {},
    val onSeeFullBill: (String) -> Unit = {},
    val onShowEarlierHistory: () -> Unit = {},
    val onBackFromBill: () -> Unit = {},
    val onOpenAlarmSoundPicker: () -> Unit = {},
    val onOpenDestinationSetup: () -> Unit = {},
    val onOpenBackupSheet: () -> Unit = {},
    val onTogglePause: () -> Unit = {},
)

@Composable
fun ObolScreenRenderer(
    model: ObolScreenModel,
    callbacks: ObolScreenCallbacks = ObolScreenCallbacks(),
    cameraContent: (@Composable () -> Unit)? = null,
    isStaticPreview: Boolean = false,
) {
    when (model) {
        is ObolScreenModel.Welcome -> WelcomeScreen(
            model = model,
            onSetMeUp = callbacks.onSetMeUp,
            onRestoreFromBackup = callbacks.onRestoreFromBackup,
        )
        is ObolScreenModel.Permissions -> PermissionsScreen(
            model = model,
            onRequestPermission = callbacks.onRequestPermission,
            onContinue = callbacks.onContinuePermissions,
        )
        is ObolScreenModel.Alarms -> AlarmsScreen(
            model = model,
            onBack = callbacks.onBackToHome,
            onUpdateSlot = callbacks.onUpdateSlot,
            onFinishOnboarding = callbacks.onFinishOnboarding,
        )
        is ObolScreenModel.Home -> HomeScreen(
            model = model,
            onPrimaryAction = callbacks.onHomePrimary,
            onDeclareGraceNight = callbacks.onDeclareGraceNight,
            onUndoGraceNight = callbacks.onUndoGraceNight,
            onConfirmPendingUpi = callbacks.onConfirmPendingUpi,
            onOpenAlarms = callbacks.onOpenAlarms,
            onOpenHistory = callbacks.onOpenHistory,
            onOpenSettings = callbacks.onOpenSettings,
        )
        is ObolScreenModel.MorningMeter -> MorningMeterScreen(
            model = model,
            onPrimaryAction = callbacks.onMorningPrimary,
            onSnoozeAction = callbacks.onSnooze,
        )
        is ObolScreenModel.BrushingCamera -> BrushingCameraScreen(
            model = model,
            onOpenOverride = callbacks.onOpenOverride,
            cameraContent = cameraContent,
        )
        is ObolScreenModel.OverrideTyping -> OverrideTypingScreen(
            model = model,
            onTypedTextChange = callbacks.onOverrideTypedChange,
            onSubmitOverride = callbacks.onSubmitOverride,
            isStaticPreview = isStaticPreview,
        )
        is ObolScreenModel.PostBrushSettle -> PostBrushSettleScreen(
            model = model,
            onPrimaryAction = callbacks.onSettlePrimary,
            onLaterAction = callbacks.onSettleLater,
        )
        is ObolScreenModel.ClosedSummary -> ClosedSummaryScreen(
            model = model,
            onDone = callbacks.onClosedDone,
            onSeeFullBill = callbacks.onSeeFullBill,
            onOpenHistory = callbacks.onOpenHistory,
            onOpenSettings = callbacks.onOpenSettings,
        )
        is ObolScreenModel.HistoryList -> HistoryScreen(
            model = model,
            onBack = callbacks.onBackToHome,
            onSelectMorning = callbacks.onSeeFullBill,
            onShowEarlier = callbacks.onShowEarlierHistory,
        )
        is ObolScreenModel.FullBill -> FullBillScreen(
            model = model,
            onBack = callbacks.onBackFromBill,
        )
        is ObolScreenModel.Settings -> SettingsScreen(
            model = model,
            onBack = callbacks.onBackToHome,
            onOpenAlarmSoundPicker = callbacks.onOpenAlarmSoundPicker,
            onOpenDestinationSetup = callbacks.onOpenDestinationSetup,
            onOpenBackupSheet = callbacks.onOpenBackupSheet,
            onTogglePause = callbacks.onTogglePause,
        )
    }
}
