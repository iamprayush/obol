package dev.prayush.obol.design

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

data class ObolSkyStage(
    val fraction: Float,
    val bg: Color,
    val h1: Color,
    val h2: Color,
    val sun: Color,
    val skyInk: Color,
    val groundInk: Color = ObolPalette.Cream,
)

data class SunGeometry(
    val centerYDp: Float,
    val radiusDp: Float,
    val figureSizeSp: Float,
)

object ObolPalette {
    val Cream = Color(0xFFF6EBD9)
    val Navy = Color(0xFF0F1730)
    val Ground = Color(0xFF0A1022)
    val Sun = Color(0xFFFF7A2F)
    val ViewfinderWell = Color(0xFF070B18)
    val ViewfinderSilhouette = Color(0xFF161D36)

    const val DESIGN_WIDTH_DP = 360f
    const val DESIGN_HEIGHT_DP = 800f
    const val HORIZON_Y_DP = 590f

    private data class KeyStop(
        val f: Float,
        val bg: Int,
        val h1: Int,
        val h2: Int,
        val sun: Int,
    )

    private val KEY_STOPS = listOf(
        KeyStop(0.00f, 0x0F1730, 0x161F45, 0x121A3A, 0xFF7A2F),
        KeyStop(0.30f, 0x2B1930, 0x6E3038, 0x47233A, 0xFF7A2F),
        KeyStop(0.65f, 0x7A2F2C, 0xC2522E, 0x9C3F2E, 0xFF7A2F),
        KeyStop(1.00f, 0xFFC27A, 0xFF9A4A, 0xFFAF63, 0xFF5A1F),
    )

    fun stage(rawFraction: Float): ObolSkyStage {
        val f = rawFraction.coerceIn(0f, 1f)
        for (i in 0 until KEY_STOPS.lastIndex) {
            val s0 = KEY_STOPS[i]
            val s1 = KEY_STOPS[i + 1]
            if (f <= s1.f || i == KEY_STOPS.lastIndex - 1) {
                val span = (s1.f - s0.f).coerceAtLeast(1e-6f)
                val t = ((f - s0.f) / span).coerceIn(0f, 1f)
                return ObolSkyStage(
                    fraction = f,
                    bg = mixRgb(s0.bg, s1.bg, t),
                    h1 = mixRgb(s0.h1, s1.h1, t),
                    h2 = mixRgb(s0.h2, s1.h2, t),
                    sun = mixRgb(s0.sun, s1.sun, t),
                    skyInk = if (f >= 0.80f) Navy else Cream,
                    groundInk = Cream,
                )
            }
        }
        return stage(0f)
    }

    val CalmStage: ObolSkyStage = stage(0f)

    fun activeSunGeometry(rawFraction: Float): SunGeometry {
        val f = rawFraction.coerceIn(0f, 1f)
        return SunGeometry(
            centerYDp = 560f - 190f * f,
            radiusDp = 130f + 25f * f,
            figureSizeSp = 84f + 30f * f,
        )
    }

    val CalmMeterSunGeometry = SunGeometry(
        centerYDp = 606f,
        radiusDp = 118f,
        figureSizeSp = 30f,
    )

    val RestingSunGeometry = SunGeometry(
        centerYDp = 700f,
        radiusDp = 118f,
        figureSizeSp = 0f,
    )

    private fun mixRgb(rgbA: Int, rgbB: Int, t: Float): Color {
        val rA = (rgbA shr 16) and 0xFF
        val gA = (rgbA shr 8) and 0xFF
        val bA = rgbA and 0xFF

        val rB = (rgbB shr 16) and 0xFF
        val gB = (rgbB shr 8) and 0xFF
        val bB = rgbB and 0xFF

        val r = (rA + (rB - rA) * t).roundToInt().coerceIn(0, 255)
        val g = (gA + (gB - gA) * t).roundToInt().coerceIn(0, 255)
        val b = (bA + (bB - bA) * t).roundToInt().coerceIn(0, 255)
        return Color(red = r, green = g, blue = b, alpha = 255)
    }
}
