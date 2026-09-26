package com.nogirelay.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AiTranslateIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = "重新翻译",
) {
    val semanticModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }

    Canvas(modifier = semanticModifier.size(size)) {
        val w = this.size.width
        val scale = w / 24f
        val strokeWidth = 1.7f * scale

        val starPath = Path().apply {
            moveTo(8.0f * scale, 3.2f * scale)
            cubicTo(
                8.0f * scale, 6.2f * scale,
                9.8f * scale, 8.1f * scale,
                12.4f * scale, 8.1f * scale
            )
            cubicTo(
                9.8f * scale, 8.1f * scale,
                8.0f * scale, 10.0f * scale,
                8.0f * scale, 13.0f * scale
            )
            cubicTo(
                8.0f * scale, 10.0f * scale,
                6.2f * scale, 8.1f * scale,
                3.6f * scale, 8.1f * scale
            )
            cubicTo(
                6.2f * scale, 8.1f * scale,
                8.0f * scale, 6.2f * scale,
                8.0f * scale, 3.2f * scale
            )
            close()
        }
        drawPath(starPath, color = tint, style = Fill)

        val trBracket = Path().apply {
            moveTo(12.8f * scale, 4.85f * scale)
            lineTo(17.0f * scale, 4.85f * scale)
            quadraticBezierTo(19.2f * scale, 4.85f * scale, 19.2f * scale, 7.0f * scale)
            lineTo(19.2f * scale, 9.6f * scale)
        }
        drawPath(
            trBracket,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val blBracket = Path().apply {
            moveTo(4.5f * scale, 13.8f * scale)
            lineTo(4.5f * scale, 17.0f * scale)
            quadraticBezierTo(4.5f * scale, 19.15f * scale, 6.8f * scale, 19.15f * scale)
            lineTo(10.0f * scale, 19.15f * scale)
        }
        drawPath(
            blBracket,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val aLegs = Path().apply {
            moveTo(12.8f * scale, 19.15f * scale)
            lineTo(16.5f * scale, 10.8f * scale)
            lineTo(20.2f * scale, 19.15f * scale)
        }
        drawPath(
            aLegs,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        val aBar = Path().apply {
            moveTo(14.2f * scale, 16.2f * scale)
            lineTo(18.8f * scale, 16.2f * scale)
        }
        drawPath(
            aBar,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

