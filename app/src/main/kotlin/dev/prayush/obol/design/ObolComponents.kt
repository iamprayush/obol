package dev.prayush.obol.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

@Immutable
data class ObolLayoutScale(
    val sx: Float = 1f,
    val sy: Float = 1f,
    val uniform: Float = 1f,
) {
    fun x(dpValue: Float): Dp = (dpValue * sx).dp
    fun y(dpValue: Float): Dp = (dpValue * sy).dp
    fun u(dpValue: Float): Dp = (dpValue * uniform).dp
}

val LocalObolScale = compositionLocalOf { ObolLayoutScale() }

@Composable
fun ObolScreenFrame(
    stage: ObolSkyStage,
    sunGeometry: SunGeometry? = null,
    haloMode: SunHaloMode = SunHaloMode.NONE,
    showStars: Boolean = false,
    showGround: Boolean = true,
    sunAmountText: String? = null,
    sunLabelText: String? = null,
    viewfinderSpec: ViewfinderVisualSpec? = null,
    viewfinderContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(ObolLayoutScale) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stage.bg),
    ) {
        if (showGround) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(ObolPalette.Ground),
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            val wDp = maxWidth.value.coerceAtLeast(1f)
            val hDp = maxHeight.value.coerceAtLeast(1f)
            val sx = wDp / ObolPalette.DESIGN_WIDTH_DP
            val sy = hDp / ObolPalette.DESIGN_HEIGHT_DP
            val scale = ObolLayoutScale(
                sx = sx,
                sy = sy,
                uniform = min(sx, sy),
            )
            CompositionLocalProvider(LocalObolScale provides scale) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ObolSkyCanvas(
                        stage = stage,
                        sunGeometry = sunGeometry,
                        haloMode = haloMode,
                        showStars = showStars,
                        showGround = showGround,
                        sunAmountText = sunAmountText,
                        sunLabelText = sunLabelText,
                        viewfinderSpec = viewfinderSpec,
                        viewfinderContent = viewfinderContent,
                    )
                    content(scale)
                }
            }
        }
    }
}

@Composable
fun ObolStatusRow(
    leftText: String,
    rightText: String = "",
    inkColor: Color,
    onLeftClick: (() -> Unit)? = null,
    onHistoryClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val scale = LocalObolScale.current
    val statusColor = inkColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.y(20f))
            .padding(horizontal = scale.x(28f))
            .testTag("status_row"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RupeeSansText(
            text = leftText,
            color = statusColor,
            sizeSp = 13f * scale.uniform,
            weight = FontWeight.Medium,
            lineHeightFactor = 1.2f,
            modifier = if (onLeftClick != null) {
                Modifier.clickable(onClick = onLeftClick).testTag("status_left")
            } else {
                Modifier.testTag("status_left")
            },
        )
        if (onHistoryClick != null && onSettingsClick != null && rightText == "History · Settings") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RupeeSansText(
                    text = "History",
                    color = statusColor,
                    sizeSp = 13f * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.2f,
                    modifier = Modifier.clickable(onClick = onHistoryClick).testTag("nav_history"),
                )
                Spacer(modifier = Modifier.width(scale.u(3.5f)))
                RupeeSansText(
                    text = "·",
                    color = statusColor,
                    sizeSp = 13f * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.2f,
                )
                Spacer(modifier = Modifier.width(scale.u(3.5f)))
                RupeeSansText(
                    text = "Settings",
                    color = statusColor,
                    sizeSp = 13f * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.2f,
                    modifier = Modifier.clickable(onClick = onSettingsClick).testTag("nav_settings"),
                )
            }
        } else if (rightText.isNotEmpty()) {
            RupeeSansText(
                text = rightText,
                color = statusColor,
                sizeSp = 13f * scale.uniform,
                weight = FontWeight.Medium,
                lineHeightFactor = 1.2f,
                modifier = Modifier.testTag("status_right"),
            )
        }
    }
}

@Composable
fun ObolQuietLabel(
    text: String,
    topDp: Float,
    inkColor: Color,
    sizeSp: Float = 15f,
    centerAlign: Boolean = false,
    onClick: (() -> Unit)? = null,
    tag: String = "quiet_$topDp",
) {
    val scale = LocalObolScale.current
    RupeeSansText(
        text = text,
        color = inkColor.copy(alpha = 0.62f),
        sizeSp = sizeSp * scale.uniform,
        weight = FontWeight.Medium,
        lineHeightFactor = 1.4f,
        textAlign = if (centerAlign) TextAlign.Center else null,
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.y(topDp))
            .padding(horizontal = scale.x(28f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .testTag(tag),
    )
}

@Composable
fun ObolHeroText(
    text: String,
    inkColor: Color,
    sizeSp: Float = 150f,
    topDp: Float = 118f,
    dimmed: Boolean = false,
) {
    val scale = LocalObolScale.current
    val scaledSizeSp = sizeSp * scale.uniform
    val bleedSp = scaledSizeSp * 0.25f
    Box(
        modifier = Modifier
            .offset(x = scale.x(20f), y = scale.y(topDp))
            .height(scaledSizeSp.dp)
            .testTag("hero_text"),
    ) {
        Text(
            text = text,
            color = if (dimmed) inkColor.copy(alpha = 0.40f) else inkColor,
            style = ObolTypography.heroStyle(scaledSizeSp),
            textAlign = TextAlign.Center,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier.layout { measurable, constraints ->
                val bleedPx = bleedSp.sp.roundToPx()
                val naturalWidth = measurable.maxIntrinsicWidth(Constraints.Infinity)
                val paddedWidth = naturalWidth + bleedPx * 2
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = paddedWidth,
                        maxWidth = paddedWidth,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )
                val logicalWidth = naturalWidth.coerceAtLeast(1)
                val targetHeightPx = scaledSizeSp.dp.roundToPx()
                val halfLeading = (placeable.height - targetHeightPx) / 2
                layout(logicalWidth, targetHeightPx) {
                    placeable.placeRelative(-bleedPx, -halfLeading)
                }
            },
        )
    }
}

@Composable
fun ObolDryLine(
    text: String,
    inkColor: Color,
    topDp: Float = 132f,
    small: Boolean = false,
    tag: String = "dry_line",
) {
    val scale = LocalObolScale.current
    Text(
        text = text,
        color = inkColor,
        style = ObolTypography.dryLineStyle(small = small).let { base ->
            base.copy(
                fontSize = base.fontSize * scale.uniform,
                lineHeight = base.lineHeight * scale.uniform,
            )
        },
        overflow = TextOverflow.Visible,
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.y(topDp))
            .padding(start = scale.x(28f), end = scale.x(20f))
            .testTag(tag),
    )
}

@Composable
fun ObolPrimaryPill(
    text: String,
    onClick: () -> Unit,
    topDp: Float = 634f,
    tag: String = "primary_pill",
) {
    val scale = LocalObolScale.current
    Box(
        modifier = Modifier
            .offset(x = scale.x(28f), y = scale.y(topDp))
            .width(scale.x(304f))
            .height(scale.y(60f))
            .clip(RoundedCornerShape(999.dp))
            .background(ObolPalette.Cream)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RupeeSansText(
            text = text,
            color = ObolPalette.Navy,
            sizeSp = 19f * scale.uniform,
            weight = FontWeight.SemiBold,
            lineHeightFactor = 1.1f,
            maxLines = 1,
        )
    }
}

@Composable
fun ObolSecondaryPill(
    text: String,
    onClick: () -> Unit,
    topDp: Float = 706f,
    trailingText: String? = null,
    tag: String = "secondary_pill",
) {
    val scale = LocalObolScale.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .offset(x = scale.x(28f), y = scale.y(topDp))
            .width(scale.x(304f))
            .height(scale.y(54f))
            .clip(shape)
            .border(width = 1.5.dp, color = ObolPalette.Cream.copy(alpha = 0.38f), shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (trailingText != null) scale.x(24f) else 0.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (trailingText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RupeeSansText(
                    text = text,
                    color = ObolPalette.Cream,
                    sizeSp = 16f * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.1f,
                )
                RupeeSansText(
                    text = trailingText,
                    color = ObolPalette.Cream.copy(alpha = 0.55f),
                    sizeSp = 14f * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.1f,
                )
            }
        } else {
            RupeeSansText(
                text = text,
                color = ObolPalette.Cream,
                sizeSp = 16f * scale.uniform,
                weight = FontWeight.Medium,
                lineHeightFactor = 1.1f,
            )
        }
    }
}

@Composable
fun ObolGhostPill(
    text: String,
    topDp: Float = 634f,
    heightDp: Float = 60f,
    fontSizeSp: Float = 17f,
    trailingText: String? = null,
    enabled: Boolean = false,
    onClick: (() -> Unit)? = null,
    tag: String = "ghost_pill",
) {
    val scale = LocalObolScale.current
    Box(
        modifier = Modifier
            .offset(x = scale.x(28f), y = scale.y(topDp))
            .width(scale.x(304f))
            .height(scale.y(heightDp))
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) ObolPalette.Cream else ObolPalette.Cream.copy(alpha = 0.10f))
            .let {
                if (enabled && onClick != null) it.clickable(onClick = onClick) else it
            }
            .padding(horizontal = if (trailingText != null) scale.x(24f) else 0.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (enabled) ObolPalette.Navy else ObolPalette.Cream.copy(alpha = 0.50f)
        if (trailingText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RupeeSansText(
                    text = text,
                    color = fg,
                    sizeSp = fontSizeSp * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.1f,
                )
                RupeeSansText(
                    text = trailingText,
                    color = fg,
                    sizeSp = 14f * scale.uniform,
                    weight = FontWeight.Medium,
                    lineHeightFactor = 1.1f,
                )
            }
        } else {
            RupeeSansText(
                text = text,
                color = fg,
                sizeSp = fontSizeSp * scale.uniform,
                weight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
                lineHeightFactor = 1.1f,
            )
        }
    }
}

@Composable
fun ObolNote(
    text: String,
    topDp: Float = 770f,
    underlined: Boolean = false,
    onClick: (() -> Unit)? = null,
    tag: String = "bottom_note",
) {
    val scale = LocalObolScale.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.y(topDp))
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RupeeSansText(
            text = text,
            color = ObolPalette.Cream.copy(alpha = 0.60f),
            sizeSp = 13f * scale.uniform,
            weight = FontWeight.Medium,
            lineHeightFactor = 1.15f,
            textAlign = TextAlign.Center,
            textDecoration = if (underlined) TextDecoration.Underline else null,
            modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        )
    }
}

@Composable
fun ObolRowsContainer(
    topDp: Float,
    maxHeightDp: Float? = null,
    content: @Composable () -> Unit,
) {
    val scale = LocalObolScale.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.y(topDp))
            .padding(horizontal = scale.x(28f))
            .let { mod ->
                if (maxHeightDp != null) {
                    mod
                        .heightIn(max = scale.y(maxHeightDp))
                        .verticalScroll(rememberScrollState())
                } else {
                    mod
                }
            }
            .testTag("rows_container"),
    ) {
        content()
    }
}

@Composable
fun ObolDividerRow(
    heightDp: Float,
    inkColor: Color = ObolPalette.Cream,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scale = LocalObolScale.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(scale.y(heightDp)),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(inkColor.copy(alpha = 0.12f)),
        )
    }
}

@Composable
fun ObolToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = LocalObolScale.current
    val w = scale.u(42f)
    val h = scale.u(24f)
    val knobSize = scale.u(18f)
    val knobOffset = if (checked) scale.u(21f) else scale.u(3f)
    Box(
        modifier = modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(99.dp))
            .background(if (checked) ObolPalette.Sun else ObolPalette.Cream.copy(alpha = 0.18f))
            .clickable(onClick = onToggle),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = scale.u(3f))
                .size(knobSize)
                .clip(CircleShape)
                .background(ObolPalette.Cream),
        )
    }
}

@Composable
fun ObolChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = LocalObolScale.current
    val shape = RoundedCornerShape(99.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.5.dp, ObolPalette.Cream.copy(alpha = 0.38f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = scale.u(16f), vertical = scale.u(6f)),
        contentAlignment = Alignment.Center,
    ) {
        RupeeSansText(
            text = text,
            color = ObolPalette.Cream,
            sizeSp = 14f * scale.uniform,
            weight = FontWeight.SemiBold,
            lineHeightFactor = 1.1f,
        )
    }
}

@Composable
fun ObolCameraStatusLine(
    dotLit: Boolean,
    message: String,
) {
    val scale = LocalObolScale.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.y(612f))
            .padding(start = scale.x(28f), end = scale.x(20f))
            .testTag("camera_status_line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dotSize = scale.u(9f)
        if (dotLit) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(ObolPalette.Sun),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .border(1.5.dp, ObolPalette.Cream.copy(alpha = 0.60f), CircleShape),
            )
        }
        Spacer(modifier = Modifier.width(scale.u(9f)))
        RupeeSansText(
            text = message,
            color = ObolPalette.Cream,
            sizeSp = 15f * scale.uniform,
            weight = FontWeight.SemiBold,
            lineHeightFactor = 1.25f,
        )
    }
}

@Composable
fun ObolOutlineBox(
    topDp: Float,
    outlined: Boolean,
    minHeightDp: Float = 0f,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scale = LocalObolScale.current
    val shape = RoundedCornerShape(scale.u(22f))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .offset(y = scale.y(topDp))
            .padding(horizontal = scale.x(28f))
            .let { if (minHeightDp > 0f) it.height(scale.y(minHeightDp)) else it }
            .clip(shape)
            .let {
                if (outlined) {
                    it.border(1.5.dp, ObolPalette.Cream.copy(alpha = 0.38f), shape)
                } else {
                    it.background(ObolPalette.Ground.copy(alpha = 0.45f))
                }
            }
            .padding(horizontal = scale.x(20f), vertical = scale.y(18f))
            .testTag("outline_box_$topDp"),
        content = content,
    )
}
