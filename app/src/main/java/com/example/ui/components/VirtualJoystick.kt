package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LumiCyan
import com.example.ui.theme.LumiCyanBright
import com.example.ui.theme.LumiCyanGlow
import com.example.ui.theme.LumiEmerald
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    joystickDimension: Dp = 230.dp,
    onMove: (x: Int, y: Int) -> Unit,
    onStart: (x: Int, y: Int) -> Unit,
    onEnd: () -> Unit
) {
    var thumbOffsetX by remember { mutableFloatStateOf(0f) }
    var thumbOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(joystickDimension)
            .testTag("virtual_joystick_container"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(joystickDimension)
                .testTag("virtual_joystick_canvas")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = (size.width / 2f) * 0.72f

                        // Initial touch point
                        val dx = down.position.x - center.x
                        val dy = down.position.y - center.y
                        val distance = hypot(dx, dy)
                        val clampedDist = distance.coerceAtMost(maxRadius)
                        val angle = atan2(dy, dx)

                        thumbOffsetX = (cos(angle) * clampedDist)
                        thumbOffsetY = (sin(angle) * clampedDist)
                        isDragging = true

                        // Normalized vector: X is -100..100, Y is -100..100 (Up is +100)
                        val normX = ((thumbOffsetX / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                        val normY = ((-thumbOffsetY / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                        onStart(normX, normY)

                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || !change.pressed) {
                                // Released or canceled
                                thumbOffsetX = 0f
                                thumbOffsetY = 0f
                                isDragging = false
                                onEnd()
                                break
                            }

                            val curDx = change.position.x - center.x
                            val curDy = change.position.y - center.y
                            val curDist = hypot(curDx, curDy)
                            val curClamped = curDist.coerceAtMost(maxRadius)
                            val curAngle = atan2(curDy, curDx)

                            thumbOffsetX = (cos(curAngle) * curClamped)
                            thumbOffsetY = (sin(curAngle) * curClamped)

                            val updatedX = ((thumbOffsetX / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                            val updatedY = ((-thumbOffsetY / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                            onMove(updatedX, updatedY)
                            change.consume()
                        }
                    }
                }
        ) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val outerRadius = this.size.width / 2f - 12.dp.toPx()
            val maxBoundRadius = outerRadius * 0.75f
            val thumbRadius = 34.dp.toPx()

            // 1. Outer glow aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDragging) LumiCyanGlow else Color(0x1A06B6D4),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outerRadius * 1.05f
                ),
                radius = outerRadius,
                center = center
            )

            // 2. Base plate dark glassmorphic fill
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF020617)),
                    center = center,
                    radius = outerRadius
                ),
                radius = outerRadius,
                center = center
            )

            // 3. Outer ring border
            drawCircle(
                color = if (isDragging) LumiCyanBright else Color(0xFF1E293B),
                radius = outerRadius,
                center = center,
                style = Stroke(width = if (isDragging) 2.5.dp.toPx() else 1.8.dp.toPx())
            )

            // 4. Subtle inner boundary limit ring
            drawCircle(
                color = Color(0x3338BDF8),
                radius = maxBoundRadius,
                center = center,
                style = Stroke(
                    width = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            )

            // 5. Crosshair grid lines
            drawLine(
                color = Color(0x2294A3B8),
                start = Offset(center.x - outerRadius * 0.9f, center.y),
                end = Offset(center.x + outerRadius * 0.9f, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color(0x2294A3B8),
                start = Offset(center.x, center.y - outerRadius * 0.9f),
                end = Offset(center.x, center.y + outerRadius * 0.9f),
                strokeWidth = 1.dp.toPx()
            )

            // 6. Directional Labels (FWD, REV, L, R)
            val textPaint = android.graphics.Paint().apply {
                color = if (isDragging) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY
                textSize = 11.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                isAntiAlias = true
            }

            drawContext.canvas.nativeCanvas.apply {
                drawText("FWD", center.x, center.y - outerRadius + 22.dp.toPx(), textPaint)
                drawText("REV", center.x, center.y + outerRadius - 12.dp.toPx(), textPaint)
                drawText("L", center.x - outerRadius + 18.dp.toPx(), center.y + 4.dp.toPx(), textPaint)
                drawText("R", center.x + outerRadius - 18.dp.toPx(), center.y + 4.dp.toPx(), textPaint)
            }

            // 7. Dynamic vector tracer line between center and thumbstick
            val thumbCenter = Offset(center.x + thumbOffsetX, center.y + thumbOffsetY)
            if (isDragging && (thumbOffsetX != 0f || thumbOffsetY != 0f)) {
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(LumiCyanGlow, LumiCyanBright),
                        start = center,
                        end = thumbCenter
                    ),
                    start = center,
                    end = thumbCenter,
                    strokeWidth = 3.dp.toPx()
                )
            }

            // 8. Thumb Stick Handle
            // Outer glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDragging) LumiCyan else Color(0x3306B6D4),
                        Color.Transparent
                    ),
                    center = thumbCenter,
                    radius = thumbRadius * 1.2f
                ),
                radius = thumbRadius * 1.15f,
                center = thumbCenter
            )

            // Thumbstick body gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isDragging) {
                        listOf(Color(0xFF0284C7), Color(0xFF0F172A))
                    } else {
                        listOf(Color(0xFF334155), Color(0xFF1E293B))
                    },
                    center = thumbCenter,
                    radius = thumbRadius
                ),
                radius = thumbRadius,
                center = thumbCenter
            )

            // Thumbstick stroke rim
            drawCircle(
                color = if (isDragging) LumiCyanBright else Color(0xFF64748B),
                radius = thumbRadius,
                center = thumbCenter,
                style = Stroke(width = 2.dp.toPx())
            )

            // Tactile inner grip ring
            drawCircle(
                color = if (isDragging) LumiEmerald else Color(0xFF94A3B8),
                radius = thumbRadius * 0.45f,
                center = thumbCenter,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Center glowing dot
            drawCircle(
                color = if (isDragging) Color.White else Color(0xFF38BDF8),
                radius = 4.dp.toPx(),
                center = thumbCenter
            )
        }
    }
}
