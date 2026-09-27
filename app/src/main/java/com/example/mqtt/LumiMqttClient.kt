package com.example.mqtt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class MqttStatus {
    OFFLINE,
    CONNECTING,
    ONLINE
}

data class TelemetryPacket(
    val timestamp: Long = System.currentTimeMillis(),
    val topic: String,
    val payload: String,
    val isOutbound: Boolean = true
)

data class RobotStatus(
    val isRobotConnected: Boolean = false,
    val batteryPercent: Int = 100,
    val voltage: Float = 7.4f,
    val rssi: Int = -55,
    val statusText: String = "SEARCHING CLOUD...",
    val uptimeSeconds: Long = 0,
    val lastSeenTimestamp: Long = 0L
)

class LumiMqttClient(
    private val scope: CoroutineScope
) {
    private val tag = "LumiMqttClient"
    private val brokerUrl = "wss://broker.hivemq.com:8884/mqtt"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val clientId = "lumi-app-" + UUID.randomUUID().toString().substring(0, 8)
    private var activeRobotId: String = "LUMI-7042"

    private val _status = MutableStateFlow(MqttStatus.OFFLINE)
    val status: StateFlow<MqttStatus> = _status.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _packets = MutableSharedFlow<TelemetryPacket>(replay = 20, extraBufferCapacity = 50)
    val packets: SharedFlow<TelemetryPacket> = _packets.asSharedFlow()

    private val _robotStatus = MutableStateFlow(RobotStatus())
    val robotStatus: StateFlow<RobotStatus> = _robotStatus.asStateFlow()

    private var pingStartTime: Long = 0L
    private var reconnectJob: Job? = null
    private var pingKeepAliveJob: Job? = null
    private var robotHeartbeatCheckJob: Job? = null
    private var autoReconnect = true
    private var lastLogEmitTime = 0L

    private var cachedRobotId: String = ""
    private var cachedTopicBytes: ByteArray = ByteArray(0)

    fun setTargetRobotId(newId: String) {
        val sanitized = newId.trim().uppercase()
        if (sanitized != activeRobotId) {
            activeRobotId = sanitized
            cachedRobotId = "" // Invalidate cached topic bytes
            _robotStatus.value = RobotStatus(statusText = "SEARCHING CLOUD...")
            if (_status.value == MqttStatus.ONLINE) {
                subscribeToRobotStatus(activeRobotId)
            }
        }
    }

    fun connect() {
        if (_status.value == MqttStatus.ONLINE || _status.value == MqttStatus.CONNECTING) return
        _status.value = MqttStatus.CONNECTING
        autoReconnect = true

        val request = Request.Builder()
            .url(brokerUrl)
            .addHeader("Sec-WebSocket-Protocol", "mqtt")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket connection established, sending MQTT CONNECT")
                val connectBytes = createConnectPacket(clientId)
                webSocket.send(connectBytes.toByteString())
                startKeepAlive()
                startHeartbeatWatchdog()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingBytes(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closing: $code / $reason")
                _status.value = MqttStatus.OFFLINE
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code / $reason")
                _status.value = MqttStatus.OFFLINE
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WebSocket failure: ${t.localizedMessage}")
                _status.value = MqttStatus.OFFLINE
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        pingKeepAliveJob?.cancel()
        robotHeartbeatCheckJob?.cancel()
        try {
            webSocket?.send(byteArrayOf(0xE0.toByte(), 0x00.toByte()).toByteString())
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        webSocket = null
        _status.value = MqttStatus.OFFLINE
    }

    fun publish(robotId: String, x: Int, y: Int): Boolean {
        val payload = """{"x":$x,"y":$y}"""
        val packet = getFastPublishPacket(robotId, payload)

        val ws = webSocket
        val success = if (ws != null && _status.value == MqttStatus.ONLINE) {
            ws.send(packet.toByteString())
        } else {
            false
        }

        // Throttle UI log emission to 4Hz (every 250ms) or on stop so UI thread stays completely smooth
        val now = System.currentTimeMillis()
        if (now - lastLogEmitTime > 250 || (x == 0 && y == 0)) {
            lastLogEmitTime = now
            _packets.tryEmit(
                TelemetryPacket(
                    topic = "lumi/$robotId/control",
                    payload = payload,
                    isOutbound = true
                )
            )
        }
        return success
    }

    private fun getFastPublishPacket(robotId: String, payload: String): ByteArray {
        if (robotId != cachedRobotId || cachedTopicBytes.isEmpty()) {
            cachedRobotId = robotId
            cachedTopicBytes = "lumi/$robotId/control".toByteArray(Charsets.UTF_8)
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val varLen = 2 + cachedTopicBytes.size + payloadBytes.size
        val remBytes = encodeRemainingLength(varLen)
        val totalSize = 1 + remBytes.size + varLen
        val buffer = ByteArray(totalSize)

        var idx = 0
        buffer[idx++] = 0x30.toByte() // PUBLISH QoS 0
        for (b in remBytes) buffer[idx++] = b
        buffer[idx++] = (cachedTopicBytes.size ushr 8).toByte()
        buffer[idx++] = (cachedTopicBytes.size and 0xFF).toByte()
        System.arraycopy(cachedTopicBytes, 0, buffer, idx, cachedTopicBytes.size)
        idx += cachedTopicBytes.size
        System.arraycopy(payloadBytes, 0, buffer, idx, payloadBytes.size)
        return buffer
    }

    fun triggerPing() {
        val ws = webSocket
        if (ws != null && _status.value == MqttStatus.ONLINE) {
            pingStartTime = System.currentTimeMillis()
            ws.send(byteArrayOf(0xC0.toByte(), 0x00.toByte()).toByteString())
        }
    }

    private fun subscribeToRobotStatus(robotId: String) {
        val topic = "lumi/$robotId/status"
        val subPacket = createSubscribePacket(topic)
        webSocket?.send(subPacket.toByteString())
        Log.d(tag, "Subscribed to global robot status topic: $topic")
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val packetType = (bytes[0].toInt() and 0xF0) ushr 4
        when (packetType) {
            2 -> { // CONNACK
                val returnCode = if (bytes.size >= 4) bytes[3].toInt() else 0
                if (returnCode == 0) {
                    Log.d(tag, "MQTT CONNACK Received successfully")
                    _status.value = MqttStatus.ONLINE
                    subscribeToRobotStatus(activeRobotId)
                    triggerPing()
                } else {
                    Log.e(tag, "MQTT CONNACK error code: $returnCode")
                    _status.value = MqttStatus.OFFLINE
                }
            }
            3 -> { // PUBLISH from robot anywhere in the world!
                handleIncomingPublish(bytes)
            }
            9 -> { // SUBACK
                Log.d(tag, "MQTT SUBACK received, subscribed to cloud telemetry!")
            }
            13 -> { // PINGRESP
                if (pingStartTime > 0) {
                    val rtt = System.currentTimeMillis() - pingStartTime
                    _latencyMs.value = rtt
                    pingStartTime = 0L
                }
            }
        }
    }

    private fun handleIncomingPublish(bytes: ByteArray) {
        try {
            var index = 1
            // decode remaining length
            var multiplier = 1
            var remainingLength = 0
            do {
                val digit = bytes[index].toInt()
                remainingLength += (digit and 127) * multiplier
                multiplier *= 128
                index++
            } while ((digit and 128) != 0 && index < bytes.size)

            if (index + 2 > bytes.size) return
            val topicLen = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
            index += 2

            if (index + topicLen > bytes.size) return
            val topic = String(bytes, index, topicLen, Charsets.UTF_8)
            index += topicLen

            val payloadLen = bytes.size - index
            if (payloadLen <= 0) return
            val payload = String(bytes, index, payloadLen, Charsets.UTF_8)

            scope.launch {
                _packets.emit(
                    TelemetryPacket(
                        topic = topic,
                        payload = payload,
                        isOutbound = false
                    )
                )
            }

            if (topic.endsWith("/status")) {
                parseRobotStatusJson(payload)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing incoming PUBLISH: ${e.message}")
        }
    }

    private fun parseRobotStatusJson(jsonString: String) {
        val trimmed = jsonString.trim()
        if (trimmed.equals("ONLINE", ignoreCase = true)) {
            _robotStatus.value = RobotStatus(
                isRobotConnected = true,
                batteryPercent = 100,
                voltage = 7.4f,
                rssi = -50,
                statusText = "ONLINE",
                uptimeSeconds = 0,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            return
        }
        try {
            val obj = JSONObject(jsonString)
            val battery = obj.optInt("battery", 100)
            val volt = obj.optDouble("volt", 7.4).toFloat()
            val rssi = obj.optInt("rssi", -55)
            val status = obj.optString("status", "ONLINE")
            val uptime = obj.optLong("uptime", 0)

            _robotStatus.value = RobotStatus(
                isRobotConnected = true,
                batteryPercent = battery.coerceIn(0, 100),
                voltage = volt,
                rssi = rssi,
                statusText = status,
                uptimeSeconds = uptime,
                lastSeenTimestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            _robotStatus.value = _robotStatus.value.copy(
                isRobotConnected = true,
                statusText = "ONLINE",
                lastSeenTimestamp = System.currentTimeMillis()
            )
        }
    }

    private fun startHeartbeatWatchdog() {
        robotHeartbeatCheckJob?.cancel()
        robotHeartbeatCheckJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(4000)
                val lastSeen = _robotStatus.value.lastSeenTimestamp
                if (lastSeen > 0 && System.currentTimeMillis() - lastSeen > 8000) {
                    // Robot haven't sent status in >8 seconds
                    _robotStatus.value = _robotStatus.value.copy(
                        isRobotConnected = false,
                        statusText = "STANDBY (OFFLINE)"
                    )
                }
            }
        }
    }

    private fun startKeepAlive() {
        pingKeepAliveJob?.cancel()
        pingKeepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000)
                if (_status.value == MqttStatus.ONLINE) {
                    triggerPing()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            if (_status.value != MqttStatus.ONLINE) {
                Log.d(tag, "Attempting reconnect...")
                connect()
            }
        }
    }

    // MQTT 3.1.1 CONNECT
    private fun createConnectPacket(clientId: String): ByteArray {
        val variableHeaderAndPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x04))
            write("MQTT".toByteArray(Charsets.UTF_8))
            write(0x04) // Level 4
            write(0x02) // Clean Session
            write(byteArrayOf(0x00, 0x3C)) // 60s
            val idBytes = clientId.toByteArray(Charsets.UTF_8)
            write(byteArrayOf((idBytes.size ushr 8).toByte(), (idBytes.size and 0xFF).toByte()))
            write(idBytes)
        }.toByteArray()

        val packet = ByteArrayOutputStream().apply {
            write(0x10)
            write(encodeRemainingLength(variableHeaderAndPayload.size))
            write(variableHeaderAndPayload)
        }
        return packet.toByteArray()
    }

    // MQTT 3.1.1 SUBSCRIBE (QoS 0)
    private fun createSubscribePacket(topic: String): ByteArray {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val variableHeaderAndPayload = ByteArrayOutputStream().apply {
            // Packet Identifier (1)
            write(byteArrayOf(0x00, 0x01))
            // Topic Length + Topic
            write(byteArrayOf((topicBytes.size ushr 8).toByte(), (topicBytes.size and 0xFF).toByte()))
            write(topicBytes)
            // Requested QoS (0)
            write(0x00)
        }.toByteArray()

        val packet = ByteArrayOutputStream().apply {
            write(0x82) // SUBSCRIBE QoS 1
            write(encodeRemainingLength(variableHeaderAndPayload.size))
            write(variableHeaderAndPayload)
        }
        return packet.toByteArray()
    }

    // MQTT 3.1.1 PUBLISH (QoS 0)
    private fun createPublishPacket(topic: String, payload: String): ByteArray {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)

        val variableHeaderAndPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf((topicBytes.size ushr 8).toByte(), (topicBytes.size and 0xFF).toByte()))
            write(topicBytes)
            write(payloadBytes)
        }.toByteArray()

        val packet = ByteArrayOutputStream().apply {
            write(0x30)
            write(encodeRemainingLength(variableHeaderAndPayload.size))
            write(variableHeaderAndPayload)
        }
        return packet.toByteArray()
    }

    private fun encodeRemainingLength(length: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var x = length
        do {
            var encodedByte = (x % 128).toByte()
            x /= 128
            if (x > 0) {
                encodedByte = (encodedByte.toInt() or 128).toByte()
            }
            bytes.add(encodedByte)
        } while (x > 0)
        return bytes.toByteArray()
    }
}
