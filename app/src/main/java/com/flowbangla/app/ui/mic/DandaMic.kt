package com.flowbangla.app.ui.mic

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Draws the danda-mic centred in the current DrawScope.
 *
 * Geometry (in units, where the disc radius is 28 units):
 *   - capsule (mic head): 6 wide x 14 tall, rounded fully, centred slightly high
 *   - cradle: half-circle of radius 7 under the capsule
 *   - stem (the danda): short vertical stroke hanging below the cradle
 */
fun DrawScope.drawDandaMic(
    disc: Color,    // disc fill, e.g. ivory when idle, vermilion when recording
    glyph: Color,   // glyph colour, e.g. dark on ivory, ivory on vermilion
) {
    val radius = size.minDimension / 2f
    val u = radius / 28f                  // one unit in pixels

    // 1. Disc
    drawCircle(color = disc, radius = radius, center = center)

    // 2. Capsule (mic head). Top-left is centre minus half width, minus 12 units up.
    drawRoundRect(
        color = glyph,
        topLeft = Offset(center.x - 3f * u, center.y - 12f * u),
        size = Size(6f * u, 14f * u),
        cornerRadius = CornerRadius(3f * u)   // fully rounded ends
    )

    // 3. Cradle. A half-arc with centre at (0, +2 units) and radius 7 units.
    //    Compose angles: 0 = three o'clock, sweep 180 goes through six o'clock.
    drawArc(
        color = glyph,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - 7f * u, center.y - 5f * u),
        size = Size(14f * u, 14f * u),
        style = Stroke(width = 1.6f * u, cap = StrokeCap.Round)
    )

    // 4. Stem (the danda). Hangs from the bottom of the cradle.
    drawLine(
        color = glyph,
        start = Offset(center.x, center.y + 9f * u),
        end = Offset(center.x, center.y + 13f * u),
        strokeWidth = 1.6f * u,
        cap = StrokeCap.Round
    )
}
