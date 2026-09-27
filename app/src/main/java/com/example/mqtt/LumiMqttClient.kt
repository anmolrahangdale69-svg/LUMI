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

    private val _status = MutableStateFlow(MqttStatus.OFFLINE)
    val status: StateFlow<MqttStatus> = _status.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _packets = MutableSharedFlow<TelemetryPacket>(replay = 20)
    val packets: SharedFlow<TelemetryPacket> = _packets.asSharedFlow()

    private var pingStartTime: Long = 0L
    private var reconnectJob: Job? = null
    private var pingKeepAliveJob: Job? = null
    private var autoReconnect = true

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
        try {
            webSocket?.send(byteArrayOf(0xE0.toByte(), 0x00.toByte()).toByteString())
            webSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        webSocket = null
        _status.value = MqttStatus.OFFLINE
    }

    fun publish(robotId: String, x: Int, y: Int): Boolean {
        val topic = "lumi/$robotId/control"
        val payload = """{"x":$x,"y":$y}"""
        val packet = createPublishPacket(topic, payload)

        val ws = webSocket
        val success = if (ws != null && _status.value == MqttStatus.ONLINE) {
            ws.send(packet.toByteString())
        } else {
            false
        }

        scope.launch {
            _packets.emit(
                TelemetryPacket(
                    topic = topic,
                    payload = payload,
                    isOutbound = true
                )
            )
        }
        return success
    }

    fun triggerPing() {
        val ws = webSocket
        if (ws != null && _status.value == MqttStatus.ONLINE) {
            pingStartTime = System.currentTimeMillis()
            ws.send(byteArrayOf(0xC0.toByte(), 0x00.toByte()).toByteString())
        }
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
                    triggerPing()
                } else {
                    Log.e(tag, "MQTT CONNACK error code: $returnCode")
                    _status.value = MqttStatus.OFFLINE
                }
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

    // MQTT 3.1.1 CONNECT packet generator
    private fun createConnectPacket(clientId: String): ByteArray {
        val variableHeaderAndPayload = ByteArrayOutputStream().apply {
            // Protocol Name "MQTT"
            write(byteArrayOf(0x00, 0x04))
            write("MQTT".toByteArray(Charsets.UTF_8))
            // Protocol Level 4 (3.1.1)
            write(0x04)
            // Connect Flags (Clean Session = 1)
            write(0x02)
            // Keep Alive (60s)
            write(byteArrayOf(0x00, 0x3C))

            // Client Identifier
            val idBytes = clientId.toByteArray(Charsets.UTF_8)
            write(byteArrayOf((idBytes.size ushr 8).toByte(), (idBytes.size and 0xFF).toByte()))
            write(idBytes)
        }.toByteArray()

        val packet = ByteArrayOutputStream().apply {
            // Fixed header: Packet Type 1 (CONNECT), flags 0
            write(0x10)
            write(encodeRemainingLength(variableHeaderAndPayload.size))
            write(variableHeaderAndPayload)
        }
        return packet.toByteArray()
    }

    // MQTT 3.1.1 PUBLISH packet generator (QoS 0)
    private fun createPublishPacket(topic: String, payload: String): ByteArray {
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)

        val variableHeaderAndPayload = ByteArrayOutputStream().apply {
            // Topic name
            write(byteArrayOf((topicBytes.size ushr 8).toByte(), (topicBytes.size and 0xFF).toByte()))
            write(topicBytes)
            // Payload
            write(payloadBytes)
        }.toByteArray()

        val packet = ByteArrayOutputStream().apply {
            // Fixed header: Packet Type 3 (PUBLISH), QoS 0
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
