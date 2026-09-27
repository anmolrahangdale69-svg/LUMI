package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LumiBorder
import com.example.ui.theme.LumiCyan
import com.example.ui.theme.LumiCyanBright
import com.example.ui.theme.LumiSurfaceCard

@Composable
fun DpadAndTrimControls(
    selectedTrim: Int,
    onTrimSelect: (Int) -> Unit,
    onDirectionHold: (x: Int, y: Int) -> Unit,
    onDirectionRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B1120).copy(alpha = 0.85f))
            .border(1.dp, LumiBorder, RoundedCornerShape(16.dp))
            .padding(12.dp)
            .testTag("dpad_trim_container")
    ) {
        // Trim Speed Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "THROTTLE TRIM CAP",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Bold
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(50, 75, 100).forEach { percent ->
                    val isSelected = selectedTrim == percent
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Brush.horizontalGradient(
                                    listOf(LumiCyan, Color(0xFF0284C7))
                                ) else Brush.linearGradient(
                                    listOf(Color(0xFF030712), Color(0xFF1E293B))
                                )
                            )
                            .border(
                                1.dp,
                                if (isSelected) LumiCyanBright else Color(0xFF334155),
                                RoundedCornerShape(8.dp)
                            )
                            .pointerInput(percent) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    onTrimSelect(percent)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("trim_button_$percent"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$percent%",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.Black else Color(0xFF94A3B8)
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // D-pad Cross Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Up / W
                DpadButton(
                    icon = Icons.Default.ArrowUpward,
                    keyLabel = "W",
                    testTag = "dpad_up",
                    onPress = { onDirectionHold(0, 100) },
                    onRelease = onDirectionRelease
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Left / A
                    DpadButton(
                        icon = Icons.Default.ArrowBack,
                        keyLabel = "A",
                        testTag = "dpad_left",
                        onPress = { onDirectionHold(-100, 0) },
                        onRelease = onDirectionRelease
                    )

                    // Center Stop Indicator
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF030712))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "D-PAD",
                            style = TextStyle(
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Right / D
                    DpadButton(
                        icon = Icons.Default.ArrowForward,
                        keyLabel = "D",
                        testTag = "dpad_right",
                        onPress = { onDirectionHold(100, 0) },
                        onRelease = onDirectionRelease
                    )
                }

                // Down / S
                DpadButton(
                    icon = Icons.Default.ArrowDownward,
                    keyLabel = "S",
                    testTag = "dpad_down",
                    onPress = { onDirectionHold(0, -100) },
                    onRelease = onDirectionRelease
                )
            }
        }
    }
}

@Composable
private fun DpadButton(
    icon: ImageVector,
    keyLabel: String,
    testTag: String,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isPressed) LumiCyan.copy(alpha = 0.35f) else Color(0xFF0F172A)
            )
            .border(
                1.dp,
                if (isPressed) LumiCyanBright else Color(0xFF334155),
                RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isPressed = true
                    onPress()
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            isPressed = false
                            onRelease()
                            break
                        }
                    }
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = keyLabel,
                tint = if (isPressed) Color.White else Color(0xFFCBD5E1),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = keyLabel,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = if (isPressed) LumiCyanBright else Color(0xFF64748B),
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
