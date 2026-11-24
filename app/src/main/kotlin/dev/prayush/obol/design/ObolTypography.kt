package dev.prayush.obol.design

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.graphics.fonts.FontVariationAxis
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.prayush.obol.R
import java.util.concurrent.ConcurrentHashMap

object ObolTypography {

    private data class FrauncesKey(
        val italic: Boolean,
        val weight: Int,
        val opszBucket: Int,
    )

    private val frauncesCache = ConcurrentHashMap<FrauncesKey, FontFamily>()

    @Volatile
    private var cachedInstrumentSans: FontFamily? = null

    @Volatile
    private var cachedInterFallback: FontFamily? = null

    private val composeInterFallback = FontFamily(
        Font(R.font.inter_fallback, weight = FontWeight.Normal),
        Font(R.font.inter_fallback, weight = FontWeight.Medium),
        Font(R.font.inter_fallback, weight = FontWeight.SemiBold),
    )

    fun frauncesFamily(
        context: Context,
        italic: Boolean,
        weight: Int,
        opszSp: Float,
    ): FontFamily {
        val bucket = opszSp.toInt().coerceIn(9, 144)
        val key = FrauncesKey(italic = italic, weight = weight, opszBucket = bucket)
        return frauncesCache.getOrPut(key) {
            runCatching {
                val resId = if (italic) R.font.fraunces_italic else R.font.fraunces
                val axes = arrayOf(
                    FontVariationAxis("wght", weight.toFloat()),
                    FontVariationAxis("opsz", bucket.toFloat()),
                    FontVariationAxis("SOFT", 100f),
                    FontVariationAxis("WONK", 1f),
                )
                val platformFont = PlatformFont.Builder(context.resources, resId)
                    .setWeight(weight)
                    .setSlant(
                        if (italic) {
                            PlatformFontStyle.FONT_SLANT_ITALIC
                        } else {
                            PlatformFontStyle.FONT_SLANT_UPRIGHT
                        },
                    )
                    .setFontVariationSettings(axes)
                    .build()
                val family = PlatformFontFamily.Builder(platformFont).build()
                val typeface = Typeface.CustomFallbackBuilder(family).build()
                FontFamily(typeface)
            }.getOrElse {
                val resId = if (italic) R.font.fraunces_italic else R.font.fraunces
                FontFamily(
                    Font(
                        resId = resId,
                        weight = FontWeight(weight),
                        style = if (italic) FontStyle.Italic else FontStyle.Normal,
                    ),
                )
            }
        }
    }

    fun instrumentSansFamily(context: Context): FontFamily {
        cachedInstrumentSans?.let { return it }
        val built = runCatching {
            val primary500 = PlatformFont.Builder(context.resources, R.font.instrument_sans)
                .setWeight(500)
                .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(arrayOf(FontVariationAxis("wght", 500f)))
                .build()
            val primary600 = PlatformFont.Builder(context.resources, R.font.instrument_sans)
                .setWeight(600)
                .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(arrayOf(FontVariationAxis("wght", 600f)))
                .build()
            val primaryFamily = PlatformFontFamily.Builder(primary500)
                .addFont(primary600)
                .build()

            val fallback500 = PlatformFont.Builder(context.resources, R.font.inter_fallback)
                .setWeight(500)
                .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(arrayOf(FontVariationAxis("wght", 500f)))
                .build()
            val fallback600 = PlatformFont.Builder(context.resources, R.font.inter_fallback)
                .setWeight(600)
                .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(arrayOf(FontVariationAxis("wght", 600f)))
                .build()
            val fallbackFamily = PlatformFontFamily.Builder(fallback500)
                .addFont(fallback600)
                .build()

            val typeface = Typeface.CustomFallbackBuilder(primaryFamily)
                .addCustomFallback(fallbackFamily)
                .build()
            FontFamily(typeface)
        }.getOrElse {
            FontFamily(
                Font(R.font.instrument_sans, weight = FontWeight.Medium),
                Font(R.font.instrument_sans, weight = FontWeight.SemiBold),
            )
        }
        cachedInstrumentSans = built
        return built
    }

    fun interFallbackFamily(context: Context): FontFamily {
        cachedInterFallback?.let { return it }
        val built = runCatching {
            val f500 = PlatformFont.Builder(context.resources, R.font.inter_fallback)
                .setWeight(500)
                .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(arrayOf(FontVariationAxis("wght", 500f)))
                .build()
            val f600 = PlatformFont.Builder(context.resources, R.font.inter_fallback)
                .setWeight(600)
                .setSlant(PlatformFontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(arrayOf(FontVariationAxis("wght", 600f)))
                .build()
            val fam = PlatformFontFamily.Builder(f500).addFont(f600).build()
            FontFamily(Typeface.CustomFallbackBuilder(fam).build())
        }.getOrElse {
            composeInterFallback
        }
        cachedInterFallback = built
        return built
    }

    fun withRupeeFallback(
        text: String,
        fallbackFamily: FontFamily,
        weight: FontWeight,
    ): AnnotatedString {
        if ('₹' !in text) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            text.forEachIndexed { index, ch ->
                if (ch == '₹') {
                    addStyle(
                        SpanStyle(
                            fontFamily = fallbackFamily,
                            fontWeight = weight,
                        ),
                        start = index,
                        end = index + 1,
                    )
                }
            }
        }
    }

    private val tightPlatformStyle = PlatformTextStyle(includeFontPadding = false)
    private val trimmedLineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Proportional,
        trim = LineHeightStyle.Trim.Both,
    )

    @Composable
    fun heroStyle(sizeSp: Float = 150f): TextStyle {
        val context = LocalContext.current
        val family = remember(sizeSp) { frauncesFamily(context, italic = true, weight = 300, opszSp = sizeSp) }
        return TextStyle(
            fontFamily = family,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Light,
            fontSize = sizeSp.sp,
            lineHeight = sizeSp.sp,
            letterSpacing = (-0.035).em,
            platformStyle = tightPlatformStyle,
            lineHeightStyle = trimmedLineHeightStyle,
        )
    }

    @Composable
    fun dryLineStyle(small: Boolean = false): TextStyle {
        val context = LocalContext.current
        val sizeSp = if (small) 21f else 26f
        val family = remember(small) { frauncesFamily(context, italic = true, weight = 400, opszSp = sizeSp) }
        return TextStyle(
            fontFamily = family,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Normal,
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.22f).sp,
            platformStyle = tightPlatformStyle,
        )
    }

    @Composable
    fun sunAmountStyle(sizeSp: Float): TextStyle {
        val context = LocalContext.current
        val family = remember(sizeSp.toInt()) { frauncesFamily(context, italic = true, weight = 400, opszSp = sizeSp) }
        return TextStyle(
            fontFamily = family,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Normal,
            fontSize = sizeSp.sp,
            lineHeight = sizeSp.sp,
            letterSpacing = (-0.03).em,
            platformStyle = tightPlatformStyle,
            lineHeightStyle = trimmedLineHeightStyle,
        )
    }

    @Composable
    fun alarmListTimeStyle(): TextStyle {
        val context = LocalContext.current
        val family = remember { frauncesFamily(context, italic = true, weight = 300, opszSp = 28f) }
        return TextStyle(
            fontFamily = family,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Light,
            fontSize = 28.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.02).em,
            platformStyle = tightPlatformStyle,
        )
    }

    @Composable
    fun rowAmountStyle(sizeSp: Float = 19f): TextStyle {
        val context = LocalContext.current
        val family = remember(sizeSp.toInt()) { frauncesFamily(context, italic = false, weight = 400, opszSp = sizeSp) }
        return TextStyle(
            fontFamily = family,
            fontStyle = FontStyle.Normal,
            fontWeight = FontWeight.Normal,
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * 1.25f).sp,
            platformStyle = tightPlatformStyle,
        )
    }

    @Composable
    fun sansStyle(
        sizeSp: Float,
        weight: FontWeight = FontWeight.Medium,
        lineHeightFactor: Float = 1.35f,
    ): TextStyle {
        val context = LocalContext.current
        val family = remember { instrumentSansFamily(context) }
        return TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * lineHeightFactor).sp,
            platformStyle = tightPlatformStyle,
        )
    }
}

@Composable
fun RupeeSansText(
    text: String,
    color: Color,
    sizeSp: Float,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Medium,
    lineHeightFactor: Float = 1.35f,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val context = LocalContext.current
    val baseStyle = ObolTypography.sansStyle(sizeSp = sizeSp, weight = weight, lineHeightFactor = lineHeightFactor)
    val fallback = remember { ObolTypography.interFallbackFamily(context) }
    val annotated = remember(text, weight) {
        ObolTypography.withRupeeFallback(text = text, fallbackFamily = fallback, weight = weight)
    }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        style = baseStyle,
        textAlign = textAlign,
        textDecoration = textDecoration,
        maxLines = maxLines,
        overflow = TextOverflow.Visible,
    )
}
