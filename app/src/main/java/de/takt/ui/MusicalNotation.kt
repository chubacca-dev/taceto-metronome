package de.takt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class NoteShape(val denominator: Int, val dotted: Boolean) {
    val flags: Int get() = when (denominator) { 8 -> 1; 16 -> 2; 32 -> 3; else -> 0 }
}

internal fun noteShape(quarters: Double): NoteShape {
    val values = listOf(4.0, 2.0, 1.0, .5, .25, .125)
    val plain = values.indexOfFirst { abs(it - quarters) < 1e-8 }
    if (plain >= 0) return NoteShape(1 shl plain, false)
    val dotted = values.indexOfFirst { abs(it * 1.5 - quarters) < 1e-8 }
    return NoteShape(if (dotted >= 0) 1 shl dotted else 4, dotted >= 0)
}

/** Drawn notation avoids missing music glyphs in device fonts. Labels provide the accessible name. */
@Composable
internal fun NoteGlyph(quarters: Double, modifier: Modifier = Modifier, description: String? = null) {
    val color = LocalContentColor.current
    val shape = noteShape(quarters)
    Canvas(modifier.size(if (shape.dotted) 28.dp else 22.dp, 32.dp)
        .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        scale(size.width / (if (shape.dotted) 28f else 22f), size.height / 36f, Offset.Zero) {
            drawNote(shape, color)
        }
    }
}

private fun DrawScope.drawNote(shape: NoteShape, color: Color) {
    val cy = if (shape.denominator == 1) 21f else 28f
    rotate(-18f, Offset(8f, cy)) {
        if (shape.denominator <= 2) drawOval(color, Offset(2f, cy - 3.6f), Size(12f, 7.2f), style = Stroke(1.8f))
        else drawOval(color, Offset(2f, cy - 3.6f), Size(12f, 7.2f))
    }
    if (shape.denominator != 1) {
        drawLine(color, Offset(13f, 27f), Offset(13f, 3f), 1.6f)
        repeat(shape.flags) { flag ->
            val y = 3f + flag * 6f
            val path = Path().apply {
                moveTo(13f, y); cubicTo(14f, y + 5f, 25f, y + 5f, 19f, y + 14f)
                cubicTo(21f, y + 7f, 13f, y + 8f, 13f, y + 4f); close()
            }
            drawPath(path, color)
        }
    }
    if (shape.dotted) drawCircle(color, 1.6f, Offset(23f, cy - 1f))
}

/** Long-short motif requested for swing: dotted eighth + sixteenth, or dotted sixteenth + 32nd. */
@Composable
internal fun SwingGlyph(sixteenths: Boolean, description: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(58.dp, 32.dp).semantics { contentDescription = description }) {
        scale(size.width / 58f, size.height / 36f, Offset.Zero) {
            listOf(8f, 42f).forEach { x ->
                rotate(-18f, Offset(x, 28f)) { drawOval(color, Offset(x - 6f, 24.4f), Size(12f, 7.2f)) }
                drawLine(color, Offset(x + 5f, 27f), Offset(x + 5f, 3f), 1.6f)
            }
            drawCircle(color, 1.6f, Offset(22f, 27f))
            repeat(if (sixteenths) 2 else 1) { beam ->
                drawLine(color, Offset(13f, 4f + beam * 6), Offset(47f, 4f + beam * 6), 3f)
            }
            drawLine(color, Offset(37f, if (sixteenths) 16f else 10f),
                Offset(47f, if (sixteenths) 16f else 10f), 3f)
        }
    }
}
