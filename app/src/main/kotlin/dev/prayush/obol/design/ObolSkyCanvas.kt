package dev.prayush.obol.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.min

enum class SunHaloMode {
    NONE,
    SINGLE_RESTING,
    DOUBLE_ACTIVE,
}

@Composable
fun ObolSkyCanvas(
    stage: ObolSkyStage,
    sunGeometry: SunGeometry?,
    haloMode: SunHaloMode,
    showStars: Boolean,
    showGround: Boolean,
    sunAmountText: String? = null,
    sunLabelText: String? = null,
    viewfinderSpec: ViewfinderVisualSpec? = null,
    viewfinderContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scale = LocalObolScale.current

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val sx = size.width / ObolPalette.DESIGN_WIDTH_DP
            val sy = size.height / ObolPalette.DESIGN_HEIGHT_DP
            val uniform = min(sx, sy)

            drawRect(color = stage.bg)

            if (showStars) {
                val starColor = ObolPalette.Cream.copy(alpha = 0.55f)
                drawCircle(starColor, radius = 1.5f * uniform, center = Offset(304f * sx, 96f * sy))
                drawCircle(starColor, radius = 1.1f * uniform, center = Offset(262f * sx, 150f * sy))
                drawCircle(starColor, radius = 1.3f * uniform, center = Offset(326f * sx, 214f * sy))
                drawCircle(starColor, radius = 1.0f * uniform, center = Offset(236f * sx, 62f * sy))
            }

            if (sunGeometry != null && haloMode != SunHaloMode.NONE) {
                val cx = 180f * sx
                val cy = sunGeometry.centerYDp * sy
                val k = (sunGeometry.radiusDp / 118f) * uniform
                if (haloMode == SunHaloMode.DOUBLE_ACTIVE) {
                    drawSunBlob(cx, cy, 1.72f * k, stage.h2)
                    drawSunBlob(cx, cy, 1.34f * k, stage.h1)
                } else if (haloMode == SunHaloMode.SINGLE_RESTING) {
                    drawSunBlob(cx, cy, 1.34f * k, stage.h2)
                }
                drawSunBlob(cx, cy, k, stage.sun)
            }

            if (viewfinderSpec != null) {
                drawViewfinderDecor(
                    spec = viewfinderSpec,
                    stage = stage,
                    sx = sx,
                    sy = sy,
                    uniform = uniform,
                    drawSilhouette = viewfinderContent == null,
                )
            }

            if (showGround) {
                val horizon = Path().apply {
                    moveTo(0f, 596f * sy)
                    cubicTo(80f * sx, 578f * sy, 150f * sx, 608f * sy, 230f * sx, 594f * sy)
                    cubicTo(290f * sx, 584f * sy, 330f * sx, 588f * sy, 360f * sx, 582f * sy)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = horizon, color = ObolPalette.Ground)
            }
        }

        if (viewfinderSpec != null && viewfinderContent != null) {
            val diameterDp = (236f * scale.uniform).dp
            val leftDp = (180f * scale.sx - 118f * scale.uniform).dp
            val topDp = (viewfinderSpec.centerYDp * scale.sy - 118f * scale.uniform).dp
            Box(
                modifier = Modifier
                    .offset(x = leftDp, y = topDp)
                    .size(diameterDp)
                    .clip(CircleShape),
            ) {
                viewfinderContent()
            }
        }

        if (sunGeometry != null && sunAmountText != null && sunGeometry.figureSizeSp > 0f) {
            val topDesign = sunGeometry.centerYDp - sunGeometry.radiusDp
            val bottomDesign = min(ObolPalette.HORIZON_Y_DP, sunGeometry.centerYDp + sunGeometry.radiusDp)
            val boxHeightDesign = (bottomDesign - topDesign).coerceAtLeast(48f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = (topDesign * scale.sy).dp)
                    .height((boxHeightDesign * scale.sy).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = sunAmountText,
                    color = ObolPalette.Navy,
                    style = ObolTypography.sunAmountStyle(sunGeometry.figureSizeSp * scale.uniform),
                    textAlign = TextAlign.Center,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!sunLabelText.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height((4f * scale.sy).dp))
                    RupeeSansText(
                        text = sunLabelText,
                        color = ObolPalette.Navy.copy(alpha = 0.72f),
                        sizeSp = 13f * scale.uniform,
                        weight = FontWeight.SemiBold,
                        lineHeightFactor = 1.1f,
                    )
                }
            }
        }
    }
}

data class ViewfinderVisualSpec(
    val progress: Float,
    val active: Boolean,
    val showBrushHint: Boolean,
    val centerYDp: Float = 432f,
)

private fun DrawScope.drawSunBlob(
    cx: Float,
    cy: Float,
    scale: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val path = Path().apply {
        moveTo(0f, -118f)
        cubicTo(68f, -120f, 120f, -66f, 118f, 2f)
        cubicTo(116f, 68f, 64f, 118f, 0f, 118f)
        cubicTo(-66f, 118f, -118f, 68f, -118f, 0f)
        cubicTo(-118f, -66f, -68f, -116f, 0f, -118f)
        close()
    }
    withTransform({
        translate(left = cx, top = cy)
        scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
    }) {
        drawPath(path = path, color = color)
    }
}

private fun DrawScope.drawViewfinderDecor(
    spec: ViewfinderVisualSpec,
    stage: ObolSkyStage,
    sx: Float,
    sy: Float,
    uniform: Float,
    drawSilhouette: Boolean,
) {
    val cx = 180f * sx
    val cy = spec.centerYDp * sy
    val innerR = 118f * uniform
    val outerR = 132f * uniform
    val ringStroke = 6f * uniform

    drawCircle(color = ObolPalette.ViewfinderWell, radius = innerR, center = Offset(cx, cy))

    if (drawSilhouette) {
        val clipCircle = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    center = Offset(cx, cy),
                    radius = innerR,
                ),
            )
        }
        clipPath(clipCircle) {
            drawCircle(
                color = ObolPalette.ViewfinderSilhouette,
                radius = 40f * uniform,
                center = Offset(cx, cy - 22f * uniform),
            )
            val shoulders = Path().apply {
                moveTo(cx - 92f * uniform, cy + 130f * uniform)
                cubicTo(
                    cx - 92f * uniform,
                    cy + 58f * uniform,
                    cx - 50f * uniform,
                    cy + 34f * uniform,
                    cx,
                    cy + 34f * uniform,
                )
                cubicTo(
                    cx + 50f * uniform,
                    cy + 34f * uniform,
                    cx + 92f * uniform,
                    cy + 58f * uniform,
                    cx + 92f * uniform,
                    cy + 130f * uniform,
                )
                close()
            }
            drawPath(path = shoulders, color = ObolPalette.ViewfinderSilhouette)
        }
        if (spec.showBrushHint) {
            drawLine(
                color = ObolPalette.Cream.copy(alpha = 0.55f),
                start = Offset(cx + 14f * uniform, cy - 4f * uniform),
                end = Offset(cx + 64f * uniform, cy + 10f * uniform),
                strokeWidth = ringStroke,
                cap = StrokeCap.Round,
            )
        }
    }

    drawCircle(
        color = ObolPalette.Cream.copy(alpha = 0.14f),
        radius = outerR,
        center = Offset(cx, cy),
        style = Stroke(width = ringStroke),
    )

    val p = spec.progress.coerceIn(0f, 1f)
    if (p > 0f) {
        val arcColor = if (spec.active) stage.sun else ObolPalette.Cream.copy(alpha = 0.45f)
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = 360f * p,
            useCenter = false,
            topLeft = Offset(cx - outerR, cy - outerR),
            size = Size(outerR * 2f, outerR * 2f),
            style = Stroke(width = ringStroke, cap = StrokeCap.Round),
        )
    }
}
