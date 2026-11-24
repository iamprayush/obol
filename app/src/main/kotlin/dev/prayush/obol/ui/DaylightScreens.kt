package dev.prayush.obol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.prayush.obol.data.BackupPreview
import dev.prayush.obol.design.ObolDividerRow
import dev.prayush.obol.design.ObolDryLine
import dev.prayush.obol.design.ObolHeroText
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.design.ObolQuietLabel
import dev.prayush.obol.design.ObolRowsContainer
import dev.prayush.obol.design.ObolScreenFrame
import dev.prayush.obol.design.ObolStatusRow
import dev.prayush.obol.design.ObolToggle
import dev.prayush.obol.design.ObolTypography
import dev.prayush.obol.design.RupeeSansText
import dev.prayush.obol.design.SunHaloMode
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(
    model: ObolScreenModel.HistoryList,
    onBack: () -> Unit,
    onSelectMorning: (String) -> Unit,
    onShowEarlier: () -> Unit,
) {
    val stage = ObolPalette.CalmStage

    ObolScreenFrame(
        stage = stage,
        haloMode = SunHaloMode.NONE,
        showStars = false,
        showGround = false,
    ) { scale ->
        ObolStatusRow(
            leftText = "‹ Home",
            inkColor = stage.skyInk,
            onLeftClick = onBack,
        )

        ObolQuietLabel(
            text = model.monthTitle,
            topDp = 104f,
            inkColor = stage.skyInk,
        )

        ObolHeroText(
            text = model.monthTotalText,
            inkColor = stage.skyInk,
            sizeSp = 132f,
            topDp = 124f,
        )

        ObolQuietLabel(
            text = model.capSubtext,
            topDp = 268f,
            inkColor = stage.skyInk,
            tag = "cap_subtext",
        )

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.skyInk,
            topDp = 306f,
        )

        val showToggleLink = model.hasEarlier || model.showingAllTime
        ObolRowsContainer(
            topDp = 366f,
            maxHeightDp = if (showToggleLink) 322f else 414f,
        ) {
            model.rows.forEach { row ->
                ObolDividerRow(
                    heightDp = 52f,
                    inkColor = stage.skyInk,
                    onClick = { onSelectMorning(row.morningId) },
                ) {
                    RupeeSansText(
                        text = row.dateShort,
                        color = stage.skyInk,
                        sizeSp = 15f * scale.uniform,
                        weight = FontWeight.Medium,
                        modifier = Modifier.width(scale.x(if (model.showingAllTime) 72f else 56f)),
                    )
                    RupeeSansText(
                        text = row.summary,
                        color = stage.skyInk.copy(alpha = 0.58f),
                        sizeSp = 14f * scale.uniform,
                        weight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.amountText,
                        color = if (row.isFreeDimmed) stage.skyInk.copy(alpha = 0.50f) else stage.skyInk,
                        style = ObolTypography.rowAmountStyle(19f * scale.uniform),
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }

        if (showToggleLink) {
            ObolQuietLabel(
                text = if (model.showingAllTime) "This month" else "Earlier",
                topDp = 700f,
                inkColor = stage.skyInk,
                centerAlign = true,
                onClick = onShowEarlier,
                tag = "earlier_link",
            )
        }
    }
}

@Composable
fun FullBillScreen(
    model: ObolScreenModel.FullBill,
    onBack: () -> Unit,
) {
    val stage = ObolPalette.CalmStage

    ObolScreenFrame(
        stage = stage,
        haloMode = SunHaloMode.NONE,
        showStars = false,
        showGround = false,
    ) { scale ->
        ObolStatusRow(
            leftText = model.backLabel,
            inkColor = stage.skyInk,
            onLeftClick = onBack,
        )

        ObolQuietLabel(
            text = model.dateLabel,
            topDp = 104f,
            inkColor = stage.skyInk,
        )

        ObolHeroText(
            text = model.heroTotal,
            inkColor = stage.skyInk,
            sizeSp = 132f,
            topDp = 124f,
        )

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.skyInk,
            topDp = 272f,
        )

        ObolRowsContainer(
            topDp = 326f,
            maxHeightDp = 454f,
        ) {
            model.rows.forEach { row ->
                ObolDividerRow(heightDp = 46f, inkColor = stage.skyInk) {
                    RupeeSansText(
                        text = row.timeText,
                        color = stage.skyInk.copy(alpha = 0.60f),
                        sizeSp = 15f * scale.uniform,
                        weight = FontWeight.Medium,
                        modifier = Modifier.width(scale.x(44f)),
                    )
                    RupeeSansText(
                        text = row.eventText,
                        color = stage.skyInk,
                        sizeSp = 14f * scale.uniform,
                        weight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.amountText.isNotEmpty()) {
                        Text(
                            text = row.amountText,
                            color = stage.skyInk,
                            style = ObolTypography.rowAmountStyle(19f * scale.uniform),
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    model: ObolScreenModel.Settings,
    onBack: () -> Unit,
    onOpenAlarmSoundPicker: () -> Unit = {},
    onOpenDestinationSetup: () -> Unit,
    onOpenBackupSheet: () -> Unit = {},
    onTogglePause: () -> Unit,
) {
    val stage = ObolPalette.CalmStage

    ObolScreenFrame(
        stage = stage,
        haloMode = SunHaloMode.NONE,
        showStars = false,
        showGround = false,
    ) { scale ->
        ObolStatusRow(
            leftText = "‹ Home",
            inkColor = stage.skyInk,
            onLeftClick = onBack,
        )

        ObolDryLine(
            text = "Settings",
            inkColor = stage.skyInk,
            topDp = 92f,
        )

        val staticRows = listOf(
            Triple("Rate", "₹20 a minute", false),
            Triple("Free minutes", "5", false),
            Triple("Morning cap", "₹600", false),
            Triple("Monthly cap", "₹6,000", false),
            Triple("Donation minimum", "₹300 tab", false),
            Triple("Brushing", "10 seconds", false),
            Triple("Alarm sound", model.alarmSoundLabel, false),
            Triple("Money goes to", model.destinationLabel, model.destinationIsUnset),
            Triple("Google Drive backup", model.backupLabel, model.backupNeedsAttention),
        )

        ObolRowsContainer(topDp = 142f, maxHeightDp = 472f) {
            staticRows.forEach { (key, value, isHot) ->
                val rowAction: (() -> Unit)? = when (key) {
                    "Alarm sound" -> onOpenAlarmSoundPicker
                    "Money goes to" -> onOpenDestinationSetup
                    "Google Drive backup" -> onOpenBackupSheet
                    else -> null
                }
                ObolDividerRow(
                    heightDp = 46f,
                    inkColor = stage.skyInk,
                    onClick = rowAction,
                ) {
                    RupeeSansText(
                        text = key,
                        color = stage.skyInk,
                        sizeSp = 15f * scale.uniform,
                        weight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    RupeeSansText(
                        text = value,
                        color = if (isHot) ObolPalette.Sun else stage.skyInk.copy(alpha = 0.58f),
                        sizeSp = 14f * scale.uniform,
                        weight = FontWeight.Medium,
                    )
                }
            }

            ObolDividerRow(heightDp = 46f, inkColor = stage.skyInk) {
                RupeeSansText(
                    text = "Pause",
                    color = stage.skyInk,
                    sizeSp = 15f * scale.uniform,
                    weight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.weight(1f))
                ObolToggle(
                    checked = model.pauseToggleOn,
                    onToggle = onTogglePause,
                )
            }
        }

        ObolQuietLabel(
            text = model.pauseNote,
            topDp = 626f,
            inkColor = stage.skyInk,
            sizeSp = 13f,
            tag = "pause_note",
        )
    }
}

@Composable
fun AlarmSoundSheet(
    selectedChoice: AlarmSoundChoice,
    previewingChoice: AlarmSoundChoice?,
    onSelectAndPreviewBuiltIn: (BuiltInChime) -> Unit,
    onToggleSystemPreview: (AlarmSoundChoice.SystemAlarm) -> Unit,
    onLaunchSystemPicker: () -> Unit,
    onDismiss: () -> Unit,
    systemAlarmOption: AlarmSoundChoice.SystemAlarm? = selectedChoice as? AlarmSoundChoice.SystemAlarm,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("alarm_sound_sheet_scrim"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(ObolPalette.Navy)
                .pointerInput(Unit) { detectTapGestures {} }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = "Alarm sound",
                color = ObolPalette.Cream,
                style = ObolTypography.dryLineStyle(small = true),
            )
            Spacer(modifier = Modifier.height(6.dp))
            RupeeSansText(
                text = "Tap a chime to hear it. Volume escalates if you snooze.",
                color = ObolPalette.Cream.copy(alpha = 0.62f),
                sizeSp = 13f,
                weight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            BuiltInChime.entries.forEachIndexed { idx, chime ->
                val choice = AlarmSoundChoice.BuiltIn(chime)
                val isSelected = selectedChoice == choice
                val isPlaying = previewingChoice == choice
                SoundOptionCard(
                    title = chime.displayName,
                    subtitle = chime.subtitle,
                    isSelected = isSelected,
                    isPlaying = isPlaying,
                    tag = "sound_option_${chime.name.lowercase()}",
                    onClick = { onSelectAndPreviewBuiltIn(chime) },
                )
                if (idx < BuiltInChime.entries.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (systemAlarmOption != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SoundOptionCard(
                    title = systemAlarmOption.label,
                    subtitle = "Pixel system alarm",
                    isSelected = selectedChoice == systemAlarmOption,
                    isPlaying = previewingChoice == systemAlarmOption,
                    tag = "sound_option_system_active",
                    onClick = { onToggleSystemPreview(systemAlarmOption) },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            SoundSheetButton(
                text = if (systemAlarmOption != null) {
                    "Change Pixel alarm sound…"
                } else {
                    "Pick from Pixel alarm sounds…"
                },
                primary = false,
                onClick = onLaunchSystemPicker,
                tag = "pick_pixel_alarm_button",
            )

            Spacer(modifier = Modifier.height(10.dp))

            SoundSheetButton(
                text = "Done",
                primary = true,
                onClick = onDismiss,
                tag = "alarm_sound_done_button",
            )
        }
    }
}

@Composable
private fun SoundOptionCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (isSelected || isPlaying) {
        ObolPalette.Sun
    } else {
        ObolPalette.Cream.copy(alpha = 0.20f)
    }
    val statusText = when {
        isPlaying -> "Playing · Stop"
        isSelected -> "Selected"
        else -> "Preview"
    }
    val statusColor = if (isSelected || isPlaying) {
        ObolPalette.Sun
    } else {
        ObolPalette.Cream.copy(alpha = 0.52f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObolPalette.Ground)
            .border(if (isSelected || isPlaying) 1.5.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RupeeSansText(
                text = title,
                color = ObolPalette.Cream,
                sizeSp = 15f,
                weight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            RupeeSansText(
                text = subtitle,
                color = ObolPalette.Cream.copy(alpha = 0.58f),
                sizeSp = 12.5f,
                weight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        RupeeSansText(
            text = statusText,
            color = statusColor,
            sizeSp = 13f,
            weight = if (isSelected || isPlaying) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun SoundSheetButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val shape = RoundedCornerShape(999.dp)
    val bg = if (primary) ObolPalette.Cream else Color.Transparent
    val fg = if (primary) ObolPalette.Navy else ObolPalette.Cream
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(bg)
            .let {
                if (!primary) {
                    it.border(1.5.dp, ObolPalette.Cream.copy(alpha = 0.38f), shape)
                } else {
                    it
                }
            }
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RupeeSansText(
            text = text,
            color = fg,
            sizeSp = 15f,
            weight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private val BACKUP_TIMESTAMP_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

@Composable
fun BackupSheet(
    backupUri: String?,
    backupDisplayName: String?,
    lastBackupAt: Instant?,
    lastBackupError: String?,
    isSyncing: Boolean,
    morningCount: Int,
    zone: ZoneId,
    feedbackMessage: String?,
    pendingRestorePreview: BackupPreview?,
    onChooseBackupFile: () -> Unit,
    onBackupNow: () -> Unit,
    onPickRestoreFile: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isConnected = !backupUri.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("backup_sheet_scrim"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f))
                .clickable(onClick = {
                    if (pendingRestorePreview != null) onCancelRestore() else onDismiss()
                }),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(ObolPalette.Navy)
                .pointerInput(Unit) { detectTapGestures {} }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (pendingRestorePreview != null) {
                val exportedText = pendingRestorePreview.exportedAt.atZone(zone).format(BACKUP_TIMESTAMP_FMT)
                val morningWord = if (pendingRestorePreview.morningCount == 1) "morning" else "mornings"
                Text(
                    text = "Restore backup?",
                    color = ObolPalette.Cream,
                    style = ObolTypography.dryLineStyle(small = true),
                )
                Spacer(modifier = Modifier.height(6.dp))
                RupeeSansText(
                    text = "This replaces your local history, alarms, and settings with the contents of this file.",
                    color = ObolPalette.Cream.copy(alpha = 0.68f),
                    sizeSp = 13f,
                    weight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                BackupStatusCard(
                    title = pendingRestorePreview.displayName,
                    subtitle = "${pendingRestorePreview.morningCount} $morningWord · ${pendingRestorePreview.alarmCount} active alarms\nSaved $exportedText",
                    badgeText = "Valid JSON",
                    isError = false,
                )
                Spacer(modifier = Modifier.height(16.dp))
                SoundSheetButton(
                    text = "Replace local data",
                    primary = true,
                    onClick = onConfirmRestore,
                    tag = "backup_confirm_restore_button",
                )
                Spacer(modifier = Modifier.height(10.dp))
                SoundSheetButton(
                    text = "Cancel",
                    primary = false,
                    onClick = onCancelRestore,
                    tag = "backup_cancel_restore_button",
                )
            } else {
                Text(
                    text = "Google Drive backup",
                    color = ObolPalette.Cream,
                    style = ObolTypography.dryLineStyle(small = true),
                )
                Spacer(modifier = Modifier.height(6.dp))
                RupeeSansText(
                    text = "Saves your full history, alarms, and settings as a single JSON file in Google Drive. Auto-updates after changes.",
                    color = ObolPalette.Cream.copy(alpha = 0.62f),
                    sizeSp = 13f,
                    weight = FontWeight.Medium,
                )

                Spacer(modifier = Modifier.height(16.dp))

                val morningWord = if (morningCount == 1) "morning" else "mornings"
                val cardTitle = if (isConnected) {
                    backupDisplayName?.ifBlank { "obol-backup.json" } ?: "obol-backup.json"
                } else {
                    "Not connected"
                }
                val cardSubtitle = when {
                    isSyncing -> "Writing and verifying backup…"
                    lastBackupError != null -> lastBackupError
                    isConnected && lastBackupAt != null ->
                        "Last backed up ${lastBackupAt.atZone(zone).format(BACKUP_TIMESTAMP_FMT)}\n$morningCount $morningWord stored"
                    isConnected -> "Connected · $morningCount $morningWord ready to back up"
                    else -> "Pick Google Drive in the file picker to create obol-backup.json ($morningCount $morningWord)."
                }
                val badge = when {
                    isSyncing -> "Syncing"
                    lastBackupError != null -> "Attention"
                    isConnected && lastBackupAt != null -> "Synced"
                    isConnected -> "Linked"
                    else -> "Off"
                }

                BackupStatusCard(
                    title = cardTitle,
                    subtitle = cardSubtitle,
                    badgeText = badge,
                    isError = lastBackupError != null,
                )

                if (!feedbackMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    RupeeSansText(
                        text = feedbackMessage,
                        color = ObolPalette.Sun,
                        sizeSp = 13f,
                        weight = FontWeight.Medium,
                        modifier = Modifier.testTag("backup_feedback_text"),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isConnected) {
                    SoundSheetButton(
                        text = if (isSyncing) "Backing up…" else "Back up now",
                        primary = true,
                        onClick = { if (!isSyncing) onBackupNow() },
                        tag = "backup_now_button",
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SoundSheetButton(
                        text = "Change Google Drive file…",
                        primary = false,
                        onClick = onChooseBackupFile,
                        tag = "backup_choose_file_button",
                    )
                } else {
                    SoundSheetButton(
                        text = "Save to Google Drive…",
                        primary = true,
                        onClick = onChooseBackupFile,
                        tag = "backup_choose_file_button",
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                SoundSheetButton(
                    text = "Restore from backup…",
                    primary = false,
                    onClick = onPickRestoreFile,
                    tag = "backup_restore_button",
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isConnected) Arrangement.SpaceBetween else Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isConnected) {
                        RupeeSansText(
                            text = "Disconnect",
                            color = ObolPalette.Cream.copy(alpha = 0.52f),
                            sizeSp = 13f,
                            weight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable(onClick = onDisconnect)
                                .padding(vertical = 6.dp)
                                .testTag("backup_disconnect_link"),
                        )
                    }
                    RupeeSansText(
                        text = "Done",
                        color = ObolPalette.Cream.copy(alpha = 0.80f),
                        sizeSp = 14f,
                        weight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("backup_done_button"),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupStatusCard(
    title: String,
    subtitle: String,
    badgeText: String,
    isError: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (isError) ObolPalette.Sun else ObolPalette.Cream.copy(alpha = 0.22f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObolPalette.Ground)
            .border(if (isError) 1.5.dp else 1.dp, borderColor, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RupeeSansText(
                text = title,
                color = ObolPalette.Cream,
                sizeSp = 15f,
                weight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(3.dp))
            RupeeSansText(
                text = subtitle,
                color = if (isError) ObolPalette.Sun else ObolPalette.Cream.copy(alpha = 0.62f),
                sizeSp = 12.5f,
                weight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        RupeeSansText(
            text = badgeText,
            color = if (isError) ObolPalette.Sun else ObolPalette.Cream.copy(alpha = 0.65f),
            sizeSp = 13f,
            weight = FontWeight.SemiBold,
        )
    }
}
