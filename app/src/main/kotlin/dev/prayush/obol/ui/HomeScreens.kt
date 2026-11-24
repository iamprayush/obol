package dev.prayush.obol.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import dev.prayush.obol.design.ObolDryLine
import dev.prayush.obol.design.ObolGhostPill
import dev.prayush.obol.design.ObolHeroText
import dev.prayush.obol.design.ObolNote
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.design.ObolPrimaryPill
import dev.prayush.obol.design.ObolQuietLabel
import dev.prayush.obol.design.ObolScreenFrame
import dev.prayush.obol.design.ObolSecondaryPill
import dev.prayush.obol.design.ObolStatusRow
import dev.prayush.obol.design.ObolTypography
import dev.prayush.obol.design.RupeeSansText
import dev.prayush.obol.design.SunHaloMode

@Composable
fun HomeScreen(
    model: ObolScreenModel.Home,
    onPrimaryAction: () -> Unit,
    onDeclareGraceNight: () -> Unit,
    onUndoGraceNight: () -> Unit = {},
    onConfirmPendingUpi: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stage = ObolPalette.CalmStage
    val heroSize = ObolCopy.heroSizeForText(model.heroText)

    ObolScreenFrame(
        stage = stage,
        sunGeometry = ObolPalette.RestingSunGeometry,
        haloMode = SunHaloMode.SINGLE_RESTING,
        showStars = true,
        showGround = true,
    ) { scale ->
        ObolStatusRow(
            leftText = model.statusLeft,
            rightText = "History · Settings",
            inkColor = stage.skyInk,
            onHistoryClick = onOpenHistory,
            onSettingsClick = onOpenSettings,
        )

        ObolQuietLabel(
            text = model.label,
            topDp = 104f,
            inkColor = stage.skyInk,
        )

        ObolHeroText(
            text = model.heroText,
            inkColor = stage.skyInk,
            sizeSp = heroSize,
            topDp = 118f,
        )

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.skyInk,
            topDp = 300f,
        )

        if (model.subtitle != null) {
            ObolQuietLabel(
                text = model.subtitle,
                topDp = 384f,
                inkColor = stage.skyInk,
            )
        }

        if (model.unpaidAmountText != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = scale.y(446f))
                    .padding(horizontal = scale.x(28f)),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = model.unpaidAmountText,
                        color = ObolPalette.Sun,
                        style = ObolTypography.rowAmountStyle(28f * scale.uniform),
                        overflow = TextOverflow.Visible,
                    )
                    Text(
                        text = model.unpaidSuffix,
                        color = ObolPalette.Cream,
                        style = ObolTypography.rowAmountStyle(28f * scale.uniform),
                        overflow = TextOverflow.Visible,
                    )
                }
                if (model.unpaidNote != null) {
                    Spacer(modifier = Modifier.height(scale.y(10f)))
                    RupeeSansText(
                        text = model.unpaidNote,
                        color = ObolPalette.Cream.copy(alpha = 0.62f),
                        sizeSp = 15f * scale.uniform,
                        weight = FontWeight.Medium,
                        lineHeightFactor = 1.4f,
                    )
                }
                if (model.pendingUpiBanner != null) {
                    Spacer(modifier = Modifier.height(scale.y(8f)))
                    RupeeSansText(
                        text = model.pendingUpiBanner,
                        color = ObolPalette.Sun,
                        sizeSp = 13f * scale.uniform,
                        weight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(onClick = onConfirmPendingUpi),
                    )
                }
            }
        }

        if (model.primaryButton != null) {
            ObolPrimaryPill(
                text = model.primaryButton,
                onClick = onPrimaryAction,
                topDp = 634f,
            )
        }

        if (model.nightOffText != null) {
            if (!model.nightOffEnabled) {
                ObolGhostPill(
                    text = model.nightOffText,
                    topDp = 706f,
                    heightDp = 54f,
                    fontSizeSp = 16f,
                    trailingText = model.nightOffTrailing,
                    enabled = false,
                )
            } else {
                ObolSecondaryPill(
                    text = model.nightOffText,
                    onClick = if (model.nightOffTaken) onUndoGraceNight else onDeclareGraceNight,
                    topDp = 706f,
                    trailingText = model.nightOffTrailing,
                )
            }
        }

        if (model.bottomNote != null) {
            ObolNote(
                text = model.bottomNote,
                topDp = model.bottomNoteTopDp,
                underlined = model.bottomNoteIsLink,
                onClick = if (model.bottomNoteIsLink) onOpenAlarms else null,
            )
        }
    }
}
