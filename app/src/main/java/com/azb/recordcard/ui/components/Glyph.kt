package com.azb.recordcard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small hand-drawn icon set (24x24 grid) so the app does not depend on the
 * extended Material icon pack for every glyph.
 */
enum class Glyph {
    CARD,
    BLUETOOTH,
    BATTERY,
    STORAGE,
    SYNC,
    WIFI,
    FOLDER,
    SETTINGS,
    MIC,
    PLAY,
    PAUSE,
    STOP,
    INFO,
    MORE,
    PLUS,
    CLOSE,
    CHECK,
    REFRESH,
    UPLOAD,
    DOWNLOAD,
    WARNING,
    DELETE,
    SHARE,
    CHEVRON_RIGHT,
    ARROW_BACK,
    WAVE,
    SEARCH,
    COPY,
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    strokeWidth: Dp = 1.8.dp,
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val scale = size.minDimension / 24f
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawGlyph(glyph, tint, scale, stroke)
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color, s: Float, stroke: Stroke) {
    fun o(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 0f) {
        drawLine(
            color = tint,
            start = o(x1, y1),
            end = o(x2, y2),
            strokeWidth = if (width > 0f) width * s else stroke.width,
            cap = StrokeCap.Round,
        )
    }

    when (glyph) {
        Glyph.CARD -> {
            drawRoundRect(
                color = tint,
                topLeft = o(2.5f, 5f),
                size = Size(19f * s, 14f * s),
                cornerRadius = CornerRadius(3.5f * s, 3.5f * s),
                style = stroke,
            )
            line(7f, 13.5f, 7f, 16f)
            line(10f, 11f, 10f, 16f)
            line(13f, 9f, 13f, 16f)
            line(16f, 12.5f, 16f, 16f)
        }

        Glyph.BLUETOOTH -> {
            val path = Path().apply {
                moveTo(9f * s, 3.5f * s)
                lineTo(15.5f * s, 8.5f * s)
                lineTo(9f * s, 12f * s)
                lineTo(15.5f * s, 15.5f * s)
                lineTo(9f * s, 20.5f * s)
                moveTo(9f * s, 3.5f * s)
                lineTo(9f * s, 20.5f * s)
            }
            drawPath(path, tint, style = stroke)
        }

        Glyph.BATTERY -> {
            drawRoundRect(
                color = tint,
                topLeft = o(2.5f, 8f),
                size = Size(17f * s, 9f * s),
                cornerRadius = CornerRadius(2.5f * s, 2.5f * s),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = o(20.5f, 10.5f),
                size = Size(2f * s, 4f * s),
                cornerRadius = CornerRadius(1f * s, 1f * s),
            )
            drawRoundRect(
                color = tint,
                topLeft = o(4.8f, 10.3f),
                size = Size(9f * s, 4.4f * s),
                cornerRadius = CornerRadius(1f * s, 1f * s),
            )
        }

        Glyph.STORAGE -> {
            drawRoundRect(
                color = tint,
                topLeft = o(3f, 4f),
                size = Size(18f * s, 4.2f * s),
                cornerRadius = CornerRadius(1.6f * s, 1.6f * s),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = o(3f, 10f),
                size = Size(18f * s, 4.2f * s),
                cornerRadius = CornerRadius(1.6f * s, 1.6f * s),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = o(3f, 16f),
                size = Size(18f * s, 4.2f * s),
                cornerRadius = CornerRadius(1.6f * s, 1.6f * s),
                style = stroke,
            )
        }

        Glyph.SYNC -> {
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = o(4f, 4f),
                size = Size(16f * s, 16f * s),
                style = stroke,
            )
            val angle = Math.toRadians((-55.0 + 260.0))
            val cx = 12f
            val cy = 12f
            val r = 8f
            val ex = cx + r * cos(angle).toFloat()
            val ey = cy + r * sin(angle).toFloat()
            line(ex, ey, ex - 3.4f, ey - 0.6f)
            line(ex, ey, ex - 0.6f, ey + 3.4f)
        }

        Glyph.WIFI -> {
            drawArc(
                color = tint,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = o(3f, 10f),
                size = Size(18f * s, 18f * s),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = o(6.5f, 13.5f),
                size = Size(11f * s, 11f * s),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = o(9.7f, 16.7f),
                size = Size(4.6f * s, 4.6f * s),
                style = stroke,
            )
            drawCircle(color = tint, radius = 1.2f * s, center = o(12f, 20.4f))
        }

        Glyph.FOLDER -> {
            val path = Path().apply {
                moveTo(3f * s, 7f * s)
                lineTo(9.5f * s, 7f * s)
                lineTo(11.5f * s, 9.2f * s)
                lineTo(21f * s, 9.2f * s)
                lineTo(21f * s, 19.5f * s)
                lineTo(3f * s, 19.5f * s)
                close()
            }
            drawPath(path, tint, style = stroke)
        }

        Glyph.SETTINGS -> {
            line(3.5f, 7f, 7f, 7f)
            drawCircle(color = tint, radius = 2.1f * s, center = o(9.2f, 7f), style = stroke)
            line(11.3f, 7f, 20.5f, 7f)
            line(3.5f, 12f, 12.3f, 12f)
            drawCircle(color = tint, radius = 2.1f * s, center = o(14.5f, 12f), style = stroke)
            line(16.6f, 12f, 20.5f, 12f)
            line(3.5f, 17f, 7f, 17f)
            drawCircle(color = tint, radius = 2.1f * s, center = o(9.2f, 17f), style = stroke)
            line(11.3f, 17f, 20.5f, 17f)
        }

        Glyph.MIC -> {
            drawRoundRect(
                color = tint,
                topLeft = o(9f, 3f),
                size = Size(6f * s, 10.5f * s),
                cornerRadius = CornerRadius(3f * s, 3f * s),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = o(6f, 9f),
                size = Size(12f * s, 9f * s),
                style = stroke,
            )
            line(12f, 18f, 12f, 21f)
            line(9f, 21f, 15f, 21f)
        }

        Glyph.PLAY -> {
            val path = Path().apply {
                moveTo(8f * s, 5.5f * s)
                lineTo(18.5f * s, 12f * s)
                lineTo(8f * s, 18.5f * s)
                close()
            }
            drawPath(path, tint)
        }

        Glyph.PAUSE -> {
            drawRoundRect(
                color = tint,
                topLeft = o(7.5f, 5f),
                size = Size(3.6f * s, 14f * s),
                cornerRadius = CornerRadius(1.4f * s, 1.4f * s),
            )
            drawRoundRect(
                color = tint,
                topLeft = o(13.2f, 5f),
                size = Size(3.6f * s, 14f * s),
                cornerRadius = CornerRadius(1.4f * s, 1.4f * s),
            )
        }

        Glyph.STOP -> {
            drawRoundRect(
                color = tint,
                topLeft = o(6.5f, 6.5f),
                size = Size(11f * s, 11f * s),
                cornerRadius = CornerRadius(2f * s, 2f * s),
            )
        }

        Glyph.INFO -> {
            drawCircle(color = tint, radius = 9f * s, center = o(12f, 12f), style = stroke)
            drawCircle(color = tint, radius = 1.1f * s, center = o(12f, 8f))
            line(12f, 11f, 12f, 17f)
        }

        Glyph.MORE -> {
            drawCircle(color = tint, radius = 1.5f * s, center = o(6f, 12f))
            drawCircle(color = tint, radius = 1.5f * s, center = o(12f, 12f))
            drawCircle(color = tint, radius = 1.5f * s, center = o(18f, 12f))
        }

        Glyph.PLUS -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }

        Glyph.CLOSE -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }

        Glyph.CHECK -> {
            val path = Path().apply {
                moveTo(4.5f * s, 12.5f * s)
                lineTo(9.5f * s, 17.5f * s)
                lineTo(19.5f * s, 6.5f * s)
            }
            drawPath(path, tint, style = stroke)
        }

        Glyph.REFRESH -> {
            drawArc(
                color = tint,
                startAngle = -40f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = o(4f, 4f),
                size = Size(16f * s, 16f * s),
                style = stroke,
            )
            line(19f, 3.5f, 19.5f, 8.5f)
            line(19f, 3.5f, 14.5f, 4.5f)
        }

        Glyph.UPLOAD -> {
            line(12f, 16f, 12f, 4.5f)
            line(7.5f, 9f, 12f, 4.5f)
            line(16.5f, 9f, 12f, 4.5f)
            line(5f, 19.5f, 19f, 19.5f)
        }

        Glyph.DOWNLOAD -> {
            line(12f, 4.5f, 12f, 16f)
            line(7.5f, 11.5f, 12f, 16f)
            line(16.5f, 11.5f, 12f, 16f)
            line(5f, 19.5f, 19f, 19.5f)
        }

        Glyph.WARNING -> {
            val path = Path().apply {
                moveTo(12f * s, 4f * s)
                lineTo(21f * s, 20f * s)
                lineTo(3f * s, 20f * s)
                close()
            }
            drawPath(path, tint, style = stroke)
            line(12f, 9.5f, 12f, 14.5f)
            drawCircle(color = tint, radius = 1f * s, center = o(12f, 17f))
        }

        Glyph.DELETE -> {
            line(4.5f, 6.5f, 19.5f, 6.5f)
            line(9.5f, 6.5f, 9.5f, 4f)
            line(14.5f, 6.5f, 14.5f, 4f)
            line(9.5f, 4f, 14.5f, 4f)
            val body = Path().apply {
                moveTo(6.5f * s, 8f * s)
                lineTo(7.5f * s, 20.5f * s)
                lineTo(16.5f * s, 20.5f * s)
                lineTo(17.5f * s, 8f * s)
            }
            drawPath(body, tint, style = stroke)
            line(10.5f, 11f, 10.5f, 17.5f)
            line(13.5f, 11f, 13.5f, 17.5f)
        }

        Glyph.SHARE -> {
            drawCircle(color = tint, radius = 1.7f * s, center = o(6f, 12f))
            drawCircle(color = tint, radius = 1.7f * s, center = o(17f, 5.8f))
            drawCircle(color = tint, radius = 1.7f * s, center = o(17f, 18.2f))
            line(7.4f, 11.2f, 15.6f, 6.6f, width = 1.5f)
            line(7.4f, 12.8f, 15.6f, 17.4f, width = 1.5f)
        }

        Glyph.CHEVRON_RIGHT -> {
            val path = Path().apply {
                moveTo(9f * s, 5f * s)
                lineTo(16f * s, 12f * s)
                lineTo(9f * s, 19f * s)
            }
            drawPath(path, tint, style = stroke)
        }

        Glyph.ARROW_BACK -> {
            line(20f, 12f, 4.5f, 12f)
            line(10.5f, 5.5f, 4f, 12f)
            line(10.5f, 18.5f, 4f, 12f)
        }

        Glyph.WAVE -> {
            line(4f, 9.5f, 4f, 14.5f)
            line(8f, 6f, 8f, 18f)
            line(12f, 3.5f, 12f, 20.5f)
            line(16f, 6.5f, 16f, 17.5f)
            line(20f, 9.5f, 20f, 14.5f)
        }

        Glyph.SEARCH -> {
            drawCircle(color = tint, radius = 5.6f * s, center = o(10.5f, 10.5f), style = stroke)
            line(14.8f, 14.8f, 20f, 20f)
        }

        Glyph.COPY -> {
            drawRoundRect(
                color = tint,
                topLeft = o(4f, 4f),
                size = Size(11f * s, 14f * s),
                cornerRadius = CornerRadius(2f * s, 2f * s),
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = o(9f, 8f),
                size = Size(11f * s, 13f * s),
                cornerRadius = CornerRadius(2f * s, 2f * s),
                style = stroke,
            )
        }
    }
}
