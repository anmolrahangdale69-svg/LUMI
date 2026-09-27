package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mqtt.LumiMqttClient
import com.example.mqtt.MqttStatus
import com.example.mqtt.RobotStatus
import com.example.mqtt.TelemetryPacket
import com.example.util.LumiFeedbackManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

data class DriveVector(
    val x: Int = 0,
    val y: Int = 0,
    val speedPercent: Int = 0,
    val heading: String = "STOPPED",
    val angleDegrees: Double = 0.0
)

class LumiViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("lumi_prefs", Context.MODE_PRIVATE)
    private val feedback = LumiFeedbackManager(application)
    private val mqttClient = LumiMqttClient(viewModelScope)

    val connectionStatus: StateFlow<MqttStatus> = mqttClient.status
    val latencyMs: StateFlow<Long?> = mqttClient.latencyMs
    val robotStatus: StateFlow<RobotStatus> = mqttClient.robotStatus

    private val _robotId = MutableStateFlow(prefs.getString("robot_id", "LUMI-7042") ?: "LUMI-7042")
    val robotId: StateFlow<String> = _robotId.asStateFlow()

    private val _savedRobots = MutableStateFlow<List<String>>(
        prefs.getStringSet("saved_robots", setOf("LUMI-7042", "LUMI-TEST-1"))?.toList() ?: listOf("LUMI-7042")
    )
    val savedRobots: StateFlow<List<String>> = _savedRobots.asStateFlow()

    private val _isSoundMuted = MutableStateFlow(!feedback.isSoundEnabled)
    val isSoundMuted: StateFlow<Boolean> = _isSoundMuted.asStateFlow()

    private val _speedLimitPercent = MutableStateFlow(100)
    val speedLimitPercent: StateFlow<Int> = _speedLimitPercent.asStateFlow()

    private val _driveVector = MutableStateFlow(DriveVector())
    val driveVector: StateFlow<DriveVector> = _driveVector.asStateFlow()

    private val _telemetryLogs = MutableStateFlow<List<TelemetryPacket>>(emptyList())
    val telemetryLogs: StateFlow<List<TelemetryPacket>> = _telemetryLogs.asStateFlow()

    private var driveTickerJob: Job? = null
    private var isActivelyDriving = false

    init {
        // Collect telemetry logs
        viewModelScope.launch {
            mqttClient.packets.collect { packet ->
                _telemetryLogs.value = (listOf(packet) + _telemetryLogs.value).take(30)
            }
        }
        // Initialize target robot ID and connect
        mqttClient.setTargetRobotId(_robotId.value)
        mqttClient.connect()
    }

    fun setRobotId(newId: String) {
        val sanitized = newId.trim().uppercase()
        if (sanitized.isNotEmpty()) {
            _robotId.value = sanitized
            prefs.edit().putString("robot_id", sanitized).apply()
            addRobotToSaved(sanitized)
            mqttClient.setTargetRobotId(sanitized)
            feedback.playBeep()
        }
    }

    fun addRobotToSaved(id: String) {
        val clean = id.trim().uppercase()
        val current = _savedRobots.value.toMutableList()
        if (!current.contains(clean)) {
            current.add(0, clean)
            _savedRobots.value = current
            prefs.edit().putStringSet("saved_robots", current.toSet()).apply()
        }
    }

    fun removeSavedRobot(id: String) {
        val current = _savedRobots.value.toMutableList()
        current.remove(id)
        _savedRobots.value = current
        prefs.edit().putStringSet("saved_robots", current.toSet()).apply()
    }

    fun reconnect() {
        feedback.playBeep()
        mqttClient.connect()
    }

    fun disconnect() {
        feedback.playBeep()
        mqttClient.disconnect()
    }

    fun toggleSound() {
        val muted = !_isSoundMuted.value
        _isSoundMuted.value = muted
        feedback.isSoundEnabled = !muted
        if (!muted) {
            feedback.playBeep()
        }
    }

    fun setSpeedLimit(percent: Int) {
        feedback.triggerHapticLight()
        _speedLimitPercent.value = percent
    }

    fun pingTest() {
        feedback.playBeep()
        mqttClient.triggerPing()
    }

    fun onDriveStart(x: Int, y: Int) {
        feedback.triggerHapticLight()
        isActivelyDriving = true
        updateVector(x, y)
        sendDrivePacket()
        startDriveTicker()
    }

    fun onDriveUpdate(x: Int, y: Int) {
        updateVector(x, y)
    }

    fun onDriveEnd() {
        isActivelyDriving = false
        stopDriveTicker()
        updateVector(0, 0)
        sendDrivePacket()
        feedback.triggerHapticLight()
    }

    fun emergencyStop() {
        feedback.triggerHapticHeavy()
        feedback.playAlert()
        isActivelyDriving = false
        stopDriveTicker()
        updateVector(0, 0)
        viewModelScope.launch {
            repeat(3) {
                mqttClient.publish(_robotId.value, 0, 0)
                delay(30)
            }
        }
    }

    private fun updateVector(rawX: Int, rawY: Int) {
        val limit = _speedLimitPercent.value / 100f
        val scaledX = (rawX * limit).roundToInt().coerceIn(-100, 100)
        val scaledY = (rawY * limit).roundToInt().coerceIn(-100, 100)

        val mag = hypot(scaledX.toDouble(), scaledY.toDouble()).roundToInt().coerceAtMost(100)
        val heading = calculateHeading(scaledX, scaledY)
        val angle = Math.toDegrees(atan2(scaledY.toDouble(), scaledX.toDouble()))

        _driveVector.value = DriveVector(
            x = scaledX,
            y = scaledY,
            speedPercent = mag,
            heading = heading,
            angleDegrees = angle
        )
    }

    private fun calculateHeading(x: Int, y: Int): String {
        if (x == 0 && y == 0) return "STOPPED"
        return when {
            y > 30 && x in -30..30 -> "FORWARD"
            y < -30 && x in -30..30 -> "REVERSE"
            x > 30 && y in -30..30 -> "RIGHT"
            x < -30 && y in -30..30 -> "LEFT"
            y > 0 && x > 0 -> "FORWARD RIGHT"
            y > 0 && x < 0 -> "FORWARD LEFT"
            y < 0 && x > 0 -> "REVERSE RIGHT"
            y < 0 && x < 0 -> "REVERSE LEFT"
            else -> "DRIFT"
        }
    }

    private fun startDriveTicker() {
        driveTickerJob?.cancel()
        driveTickerJob = viewModelScope.launch {
            while (isActive && isActivelyDriving) {
                delay(50) // 20Hz transmission rate
                sendDrivePacket()
            }
        }
    }

    private fun stopDriveTicker() {
        driveTickerJob?.cancel()
        driveTickerJob = null
    }

    private fun sendDrivePacket() {
        val vec = _driveVector.value
        mqttClient.publish(_robotId.value, vec.x, vec.y)
    }

    override fun onCleared() {
        super.onCleared()
        emergencyStop()
        mqttClient.disconnect()
        feedback.release()
    }
}
