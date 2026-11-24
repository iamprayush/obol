package dev.prayush.obol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.prayush.obol.design.ObolCameraStatusLine
import dev.prayush.obol.design.ObolDryLine
import dev.prayush.obol.design.ObolGhostPill
import dev.prayush.obol.design.ObolHeroText
import dev.prayush.obol.design.ObolNote
import dev.prayush.obol.design.ObolOutlineBox
import dev.prayush.obol.design.ObolPalette
import dev.prayush.obol.design.ObolPrimaryPill
import dev.prayush.obol.design.ObolQuietLabel
import dev.prayush.obol.design.ObolScreenFrame
import dev.prayush.obol.design.ObolStatusRow
import dev.prayush.obol.design.ObolTypography
import dev.prayush.obol.design.SunHaloMode
import dev.prayush.obol.design.ViewfinderVisualSpec

@Composable
fun BrushingCameraScreen(
    model: ObolScreenModel.BrushingCamera,
    onOpenOverride: () -> Unit,
    cameraContent: (@Composable () -> Unit)? = null,
) {
    val stage = ObolPalette.stage(model.heatFraction)
    val heroSp = ObolCopy.heroSizeForText(model.heroRemaining)

    ObolScreenFrame(
        stage = stage,
        haloMode = SunHaloMode.NONE,
        showStars = false,
        showGround = true,
        viewfinderSpec = ViewfinderVisualSpec(
            progress = model.ringProgress,
            active = model.ringActive,
            showBrushHint = model.showBrushHint,
        ),
        viewfinderContent = cameraContent,
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

        ObolHeroText(
            text = model.heroRemaining,
            inkColor = stage.skyInk,
            sizeSp = heroSp,
            topDp = 118f,
            dimmed = model.heroDimmed,
        )

        ObolCameraStatusLine(
            dotLit = model.dotLit,
            message = model.statusBanner,
        )

        ObolDryLine(
            text = model.dryLine,
            inkColor = stage.groundInk,
            topDp = 650f,
            small = true,
        )

        if (model.showOverrideLink) {
            ObolNote(
                text = "Camera not working?",
                topDp = 770f,
                underlined = true,
                onClick = onOpenOverride,
            )
        }
    }
}

@Composable
fun OverrideTypingScreen(
    model: ObolScreenModel.OverrideTyping,
    onTypedTextChange: (String) -> Unit,
    onSubmitOverride: () -> Unit,
    isStaticPreview: Boolean = false,
) {
    val stage = ObolPalette.stage(model.heatFraction)

    ObolScreenFrame(
        stage = stage,
        haloMode = SunHaloMode.NONE,
        showStars = false,
        showGround = true,
    ) { scale ->
        ObolStatusRow(
            leftText = model.statusLeft,
            rightText = model.statusRight,
            inkColor = stage.skyInk,
        )

        ObolQuietLabel(
            text = "Camera not working",
            topDp = 104f,
            inkColor = stage.skyInk,
        )

        ObolDryLine(
            text = "Fine. Type this,\nword for word.",
            inkColor = stage.skyInk,
            topDp = 132f,
        )

        ObolOutlineBox(
            topDp = 224f,
            outlined = true,
        ) {
            Text(
                text = model.targetSentence,
                color = stage.skyInk,
                style = ObolTypography.rowAmountStyle(20f * scale.uniform).let { base ->
                    base.copy(lineHeight = (26f * scale.uniform).let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) })
                },
                overflow = TextOverflow.Visible,
            )
        }

        ObolOutlineBox(
            topDp = 366f,
            outlined = false,
            minHeightDp = 96f,
        ) {
            val inputStyle = ObolTypography.sansStyle(
                sizeSp = 17f * scale.uniform,
                weight = FontWeight.Medium,
                lineHeightFactor = 1.3f,
            ).copy(color = ObolPalette.Cream)

            if (isStaticPreview) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.typedText,
                        style = inputStyle,
                        overflow = TextOverflow.Visible,
                    )
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(scale.y(20f))
                            .background(ObolPalette.Sun),
                    )
                }
            } else {
                BasicTextField(
                    value = model.typedText,
                    onValueChange = onTypedTextChange,
                    textStyle = inputStyle,
                    cursorBrush = SolidColor(ObolPalette.Sun),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (model.canSubmit) {
            ObolPrimaryPill(
                text = model.primaryButton,
                onClick = onSubmitOverride,
                topDp = 634f,
            )
        } else {
            ObolGhostPill(
                text = model.primaryButton,
                topDp = 634f,
                enabled = false,
            )
        }

        ObolNote(
            text = "₹20 adds to this morning's bill.",
            topDp = 712f,
        )
    }
}
