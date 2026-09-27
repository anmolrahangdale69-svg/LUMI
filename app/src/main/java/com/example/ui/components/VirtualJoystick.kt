package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LumiCyan
import com.example.ui.theme.LumiCyanBright
import com.example.ui.theme.LumiCyanGlow
import com.example.ui.theme.LumiEmerald
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()
    val thumbOffsetAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var isDragging by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val textPx = with(density) { 11.dp.toPx() }

    // Pre-allocated Paint for smooth 120fps rendering without GC allocations
    val textPaint = remember(textPx) {
        android.graphics.Paint().apply {
            textSize = textPx
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
            )
            isAntiAlias = true
        }
    }

    val dashEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

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

                        val initialOffset = Offset(
                            x = cos(angle) * clampedDist,
                            y = sin(angle) * clampedDist
                        )
                        coroutineScope.launch {
                            thumbOffsetAnim.snapTo(initialOffset)
                        }
                        isDragging = true

                        // Deadband filter: within 3% radius treated as 0,0
                        val normX = if (clampedDist < maxRadius * 0.03f) 0 else {
                            ((initialOffset.x / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                        }
                        val normY = if (clampedDist < maxRadius * 0.03f) 0 else {
                            ((-initialOffset.y / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                        }
                        onStart(normX, normY)

                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null || !change.pressed) {
                                // Released - snap smoothly back to center with responsive spring
                                isDragging = false
                                coroutineScope.launch {
                                    thumbOffsetAnim.animateTo(
                                        targetValue = Offset.Zero,
                                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 800f)
                                    )
                                }
                                onEnd()
                                break
                            }

                            val curDx = change.position.x - center.x
                            val curDy = change.position.y - center.y
                            val curDist = hypot(curDx, curDy)
                            val curClamped = curDist.coerceAtMost(maxRadius)
                            val curAngle = atan2(curDy, curDx)

                            val updatedOffset = Offset(
                                x = cos(curAngle) * curClamped,
                                y = sin(curAngle) * curClamped
                            )
                            coroutineScope.launch {
                                thumbOffsetAnim.snapTo(updatedOffset)
                            }

                            val curNormX = if (curClamped < maxRadius * 0.03f) 0 else {
                                ((updatedOffset.x / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                            }
                            val curNormY = if (curClamped < maxRadius * 0.03f) 0 else {
                                ((-updatedOffset.y / maxRadius) * 100).roundToInt().coerceIn(-100, 100)
                            }
                            onMove(curNormX, curNormY)
                            change.consume()
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.width / 2f - 12.dp.toPx()
            val maxBoundRadius = outerRadius * 0.75f
            val thumbRadius = 34.dp.toPx()
            val currentOffset = thumbOffsetAnim.value

            // 1. Outer base glow aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDragging) LumiCyanGlow else Color(0x1506B6D4),
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
                style = Stroke(width = 1.2.dp.toPx(), pathEffect = dashEffect)
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
            textPaint.color = if (isDragging) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY
            drawContext.canvas.nativeCanvas.apply {
                drawText("FWD", center.x, center.y - outerRadius + 22.dp.toPx(), textPaint)
                drawText("REV", center.x, center.y + outerRadius - 12.dp.toPx(), textPaint)
                drawText("L", center.x - outerRadius + 18.dp.toPx(), center.y + 4.dp.toPx(), textPaint)
                drawText("R", center.x + outerRadius - 18.dp.toPx(), center.y + 4.dp.toPx(), textPaint)
            }

            // 7. Dynamic vector tracer line between center and thumbstick
            val thumbCenter = Offset(center.x + currentOffset.x, center.y + currentOffset.y)
            if (currentOffset.x != 0f || currentOffset.y != 0f) {
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

            drawCircle(
                color = if (isDragging) LumiCyanBright else Color(0xFF64748B),
                radius = thumbRadius,
                center = thumbCenter,
                style = Stroke(width = 2.dp.toPx())
            )

            drawCircle(
                color = if (isDragging) LumiEmerald else Color(0xFF94A3B8),
                radius = thumbRadius * 0.45f,
                center = thumbCenter,
                style = Stroke(width = 1.5.dp.toPx())
            )

            drawCircle(
                color = if (isDragging) Color.White else Color(0xFF38BDF8),
                radius = 4.dp.toPx(),
                center = thumbCenter
            )
        }
    }
}
