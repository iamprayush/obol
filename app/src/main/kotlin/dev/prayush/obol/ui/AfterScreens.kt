package dev.prayush.obol.ui

import androidx.compose.runtime.Composable
import dev.prayush.obol.design.ObolDryLine
import dev.prayush.obol.design.ObolHeroText
import dev.prayush.obol.design.ObolNote
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.design.ObolPrimaryPill
import dev.prayush.obol.design.ObolQuietLabel
import dev.prayush.obol.design.ObolScreenFrame
import dev.prayush.obol.design.ObolSecondaryPill
import dev.prayush.obol.design.ObolStatusRow
import dev.prayush.obol.design.SunHaloMode

@Composable
fun PostBrushSettleScreen(
    model: ObolScreenModel.PostBrushSettle,
    onPrimaryAction: () -> Unit,
    onLaterAction: () -> Unit,
) {
    val stage = ObolPalette.stage(model.heatFraction)
    val sunGeo = if (model.isCalmSun) {
        ObolPalette.CalmMeterSunGeometry
    } else {
        ObolPalette.activeSunGeometry(model.heatFraction)
    }

    ObolScreenFrame(
        stage = stage,
        sunGeometry = sunGeo,
        haloMode = SunHaloMode.DOUBLE_ACTIVE,
        showStars = false,
        showGround = true,
        sunAmountText = model.sunAmount,
        sunLabelText = model.sunLabel,
    ) {
        ObolStatusRow(
            leftText = model.statusLeft,
            rightText = model.statusRight,
            inkColor = stage.skyInk,
        )

        ObolQuietLabel(
            text = model.label,
            topDp = 104f,
            inkColor = stage.skyInk,
        )

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.skyInk,
            topDp = 132f,
        )

        ObolPrimaryPill(
            text = model.primaryButton,
            onClick = onPrimaryAction,
            topDp = 634f,
        )

        if (model.secondaryButton != null) {
            ObolSecondaryPill(
                text = model.secondaryButton,
                onClick = onLaterAction,
                topDp = 706f,
            )
        }

        if (model.bottomNote.isNotEmpty()) {
            ObolNote(
                text = model.bottomNote,
                topDp = model.bottomNoteTopDp,
            )
        }
    }
}

@Composable
fun ClosedSummaryScreen(
    model: ObolScreenModel.ClosedSummary,
    onDone: () -> Unit,
    onSeeFullBill: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stage = ObolPalette.CalmStage

    ObolScreenFrame(
        stage = stage,
        sunGeometry = ObolPalette.RestingSunGeometry,
        haloMode = SunHaloMode.SINGLE_RESTING,
        showStars = false,
        showGround = true,
    ) {
        ObolStatusRow(
            leftText = model.statusLeft,
            rightText = if (model.showTopNav) "History · Settings" else "",
            inkColor = stage.skyInk,
            onHistoryClick = if (model.showTopNav) onOpenHistory else null,
            onSettingsClick = if (model.showTopNav) onOpenSettings else null,
        )

        ObolQuietLabel(
            text = "This morning cost",
            topDp = 104f,
            inkColor = stage.skyInk,
        )

        ObolHeroText(
            text = model.heroAmount,
            inkColor = stage.skyInk,
            sizeSp = 132f,
            topDp = 124f,
        )

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.skyInk,
            topDp = 300f,
        )

        ObolQuietLabel(
            text = model.subtitle,
            topDp = 384f,
            inkColor = stage.skyInk,
            tag = "closed_subtitle",
        )

        ObolPrimaryPill(
            text = "Done",
            onClick = onDone,
            topDp = 634f,
        )

        if (model.showFullBillButton && model.morningId != null) {
            ObolSecondaryPill(
                text = "See the full bill",
                onClick = { onSeeFullBill(model.morningId) },
                topDp = 706f,
            )
        }
    }
}
