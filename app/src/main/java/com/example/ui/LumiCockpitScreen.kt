package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.DpadAndTrimControls
import com.example.ui.components.LumiHeader
import com.example.ui.components.RobotPairingDialog
import com.example.ui.components.TelemetryLogViewer
import com.example.ui.components.VectorHud
import com.example.ui.components.VirtualJoystick
import com.example.ui.components.WorldwideCloudBar
import com.example.ui.theme.LumiBgDark

@Composable
fun LumiCockpitScreen(
    viewModel: LumiViewModel,
    modifier: Modifier = Modifier
) {
    val robotId by viewModel.robotId.collectAsStateWithLifecycle()
    val savedRobots by viewModel.savedRobots.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val latencyMs by viewModel.latencyMs.collectAsStateWithLifecycle()
    val robotStatus by viewModel.robotStatus.collectAsStateWithLifecycle()
    val isSoundMuted by viewModel.isSoundMuted.collectAsStateWithLifecycle()
    val speedLimit by viewModel.speedLimitPercent.collectAsStateWithLifecycle()
    val driveVector by viewModel.driveVector.collectAsStateWithLifecycle()
    val telemetryLogs by viewModel.telemetryLogs.collectAsStateWithLifecycle()

    var showPairingDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = LumiBgDark,
        modifier = modifier
            .fillMaxSize()
            .testTag("lumi_cockpit_scaffold")
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF060B18),
                            LumiBgDark
                        ),
                        radius = 1600f
                    )
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Header with Serial ID, Connection Status pill, Ping, Sound and E-Stop
                LumiHeader(
                    robotId = robotId,
                    onRobotIdChange = viewModel::setRobotId,
                    status = connectionStatus,
                    latencyMs = latencyMs,
                    isSoundMuted = isSoundMuted,
                    onToggleSound = viewModel::toggleSound,
                    onPing = viewModel::pingTest,
                    onReconnect = viewModel::reconnect,
                    onEmergencyStop = viewModel::emergencyStop,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Worldwide Cloud Link & Robot Telemetry Bar
                WorldwideCloudBar(
                    robotId = robotId,
                    robotStatus = robotStatus,
                    onOpenPairing = { showPairingDialog = true },
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. Vector HUD Readout
                VectorHud(
                    vector = driveVector,
                    robotId = robotId,
                    modifier = Modifier.fillMaxWidth()
                )

                // 4. Central Stage: Dual-Ring Virtual Joystick
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    VirtualJoystick(
                        joystickDimension = 230.dp,
                        onStart = { x, y -> viewModel.onDriveStart(x, y) },
                        onMove = { x, y -> viewModel.onDriveUpdate(x, y) },
                        onEnd = { viewModel.onDriveEnd() }
                    )
                }

                // 5. D-Pad Directional Fallback & Trim Speed Controls
                DpadAndTrimControls(
                    selectedTrim = speedLimit,
                    onTrimSelect = viewModel::setSpeedLimit,
                    onDirectionHold = { x, y -> viewModel.onDriveStart(x, y) },
                    onDirectionRelease = { viewModel.onDriveEnd() },
                    modifier = Modifier.fillMaxWidth()
                )

                // 6. Live MQTT Telemetry Buffer Viewer
                TelemetryLogViewer(
                    packets = telemetryLogs,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Customer Robot Pairing Dialog
            if (showPairingDialog) {
                RobotPairingDialog(
                    currentRobotId = robotId,
                    savedRobots = savedRobots,
                    onSelectRobot = viewModel::setRobotId,
                    onDeleteRobot = viewModel::removeSavedRobot,
                    onDismiss = { showPairingDialog = false }
                )
            }
        }
    }
}
