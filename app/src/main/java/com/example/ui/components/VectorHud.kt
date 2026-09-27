package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.DriveVector
import com.example.ui.theme.LumiBorder
import com.example.ui.theme.LumiCyan
import com.example.ui.theme.LumiCyanBright
import com.example.ui.theme.LumiEmerald
import com.example.ui.theme.LumiIndigo
import com.example.ui.theme.LumiRose
import com.example.ui.theme.LumiSurfaceCard

@Composable
fun VectorHud(
    vector: DriveVector,
    robotId: String,
    modifier: Modifier = Modifier
) {
    val speedProgress by animateFloatAsState(
        targetValue = vector.speedPercent / 100f,
        label = "speedProgress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B1120).copy(alpha = 0.85f))
            .border(1.dp, LumiBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("vector_hud_card")
    ) {
        // Heading & Stream Meta
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "HEADING STATUS",
                    style = TextStyle(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF64748B),
                        letterSpacing = 1.sp
                    )
                )
                Text(
                    text = vector.heading,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        color = if (vector.heading == "STOPPED") Color(0xFF94A3B8) else LumiCyanBright
                    )
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "TOPIC STREAM (20Hz)",
                    style = TextStyle(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF64748B)
                    )
                )
                Text(
                    text = "lumi/$robotId/control",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LumiCyan
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3-Metric Cockpit Readouts (X, Y, Speed)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HudMetricBox(
                label = "X-AXIS (STEER)",
                value = (if (vector.x > 0) "+${vector.x}" else "${vector.x}"),
                accent = if (vector.x == 0) Color(0xFF64748B) else LumiCyan,
                modifier = Modifier.weight(1f)
            )

            HudMetricBox(
                label = "Y-AXIS (THROTTLE)",
                value = (if (vector.y > 0) "+${vector.y}" else "${vector.y}"),
                accent = if (vector.y == 0) Color(0xFF64748B) else (if (vector.y > 0) LumiEmerald else LumiRose),
                modifier = Modifier.weight(1f)
            )

            HudMetricBox(
                label = "SPEED PWR",
                value = "${vector.speedPercent}%",
                accent = if (vector.speedPercent == 0) Color(0xFF64748B) else LumiIndigo,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Speed percentage visual bar
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DIFFERENTIAL THRUST",
                    style = TextStyle(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF64748B)
                    )
                )
                Text(
                    text = "${vector.speedPercent}/100",
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1E293B))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(speedProgress)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(LumiCyan, LumiEmerald)
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun HudMetricBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF030712))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8)
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = accent
                )
            )
        }
    }
}
