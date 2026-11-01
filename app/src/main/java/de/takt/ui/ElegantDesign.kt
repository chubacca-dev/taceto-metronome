package de.takt.ui

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit

@Composable
internal fun instrumentFont(asset: String, weight: Int): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets, asset, weight) {
        FontFamily(Typeface.Builder(assets, "fonts/$asset.ttf")
            .setFontVariationSettings("'wght' $weight").build())
    }
}

internal fun instrumentTypography(regular: FontFamily, medium: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = regular),
        displayMedium = base.displayMedium.copy(fontFamily = regular),
        displaySmall = base.displaySmall.copy(fontFamily = regular),
        headlineLarge = base.headlineLarge.copy(fontFamily = medium),
        headlineMedium = base.headlineMedium.copy(fontFamily = medium),
        headlineSmall = base.headlineSmall.copy(fontFamily = medium),
        titleLarge = base.titleLarge.copy(fontFamily = medium),
        titleMedium = base.titleMedium.copy(fontFamily = medium),
        titleSmall = base.titleSmall.copy(fontFamily = medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = regular),
        bodyMedium = base.bodyMedium.copy(fontFamily = regular),
        bodySmall = base.bodySmall.copy(fontFamily = regular),
        labelLarge = base.labelLarge.copy(fontFamily = medium),
        labelMedium = base.labelMedium.copy(fontFamily = medium),
        labelSmall = base.labelSmall.copy(fontFamily = medium)
    )
}

// Same seven polygons as the approved SVG study. No external digit font or fallback.
private val digitSegments = listOf("abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg")
private val segmentCoordinates = listOf(
    listOf(6f,0f,30f,0f,34f,4f,30f,8f,6f,8f,2f,4f),
    listOf(32f,7f,36f,3f,36f,29f,32f,33f,28f,29f,28f,11f),
    listOf(32f,35f,36f,39f,36f,61f,32f,65f,28f,61f,28f,39f),
    listOf(6f,60f,30f,60f,34f,64f,30f,68f,6f,68f,2f,64f),
    listOf(4f,35f,8f,39f,8f,57f,4f,61f,0f,65f,0f,39f),
    listOf(4f,7f,8f,11f,8f,29f,4f,33f,0f,29f,0f,3f),
    listOf(6f,30f,30f,30f,34f,34f,30f,38f,6f,38f,2f,34f)
)

@Composable
internal fun SegmentTempo(bpm: Int, height: TextUnit, color: Color) {
    val canvasHeight = with(LocalDensity.current) { height.toDp() }
    Canvas(Modifier.fillMaxWidth().height(canvasHeight)) {
        val text = bpm.toString()
        val units = text.length * 42f - 6f
        val scale = minOf(size.height / 68f, (size.width - 32f).coerceAtLeast(1f) / units)
        val left = (size.width - units * scale) / 2
        val top = (size.height - 68f * scale) / 2
        text.forEachIndexed { digitIndex, digit ->
            segmentCoordinates.forEachIndexed { segment, coordinates ->
                val path = Path().apply {
                    coordinates.chunked(2).forEachIndexed { index, point ->
                        val x = left + (digitIndex * 42 + point[0]) * scale
                        val y = top + point[1] * scale
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path, color.copy(alpha = if (('a' + segment) in digitSegments[digit.digitToInt()]) 1f else .035f))
            }
        }
    }
}

/** Audio-position envelope, in seconds; no independent animation clock. */
internal fun beatLight(phase: Double, bpm: Int, reduced: Boolean): Float {
    val duration = minOf(.240, 60.0 / bpm * .65)
    val elapsed = phase * 60.0 / bpm
    if (reduced) return if (elapsed < duration * .5) .85f else 0f
    val hold = minOf(.045, duration * .25)
    return (.85 * if (elapsed <= hold) 1.0 else (1 - (elapsed - hold) / (duration - hold)).coerceIn(0.0, 1.0)).toFloat()
}
