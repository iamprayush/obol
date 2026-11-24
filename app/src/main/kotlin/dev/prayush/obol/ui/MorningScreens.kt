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
fun MorningMeterScreen(
    model: ObolScreenModel.MorningMeter,
    onPrimaryAction: () -> Unit,
    onSnoozeAction: () -> Unit,
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

        if (model.heroCountdown != null) {
            val heroSp = ObolCopy.heroSizeForText(model.heroCountdown)
            ObolHeroText(
                text = model.heroCountdown,
                inkColor = stage.skyInk,
                sizeSp = heroSp,
                topDp = 118f,
            )
        }

        if (model.subHeroLabel != null) {
            ObolQuietLabel(
                text = model.subHeroLabel,
                topDp = 278f,
                inkColor = stage.skyInk,
                tag = "sub_hero_quiet",
            )
        }

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.skyInk,
            topDp = model.dryLineTopDp,
        )

        ObolPrimaryPill(
            text = model.primaryButton,
            onClick = onPrimaryAction,
            topDp = 634f,
        )

        if (model.secondaryButton != null) {
            ObolSecondaryPill(
                text = model.secondaryButton,
                onClick = onSnoozeAction,
                topDp = 706f,
            )
        }

        ObolNote(
            text = model.bottomNote,
            topDp = model.bottomNoteTopDp,
        )
    }
}
