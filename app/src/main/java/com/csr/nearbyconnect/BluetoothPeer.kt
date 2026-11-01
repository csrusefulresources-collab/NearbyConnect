package com.csr.nearbyconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Bluetooth Classic RFCOMM transport. One connected peer is supported at a time. */
class BluetoothPeer(private val adapter: BluetoothAdapter, private val listener: Listener) {
    interface Listener {
        fun onStatus(message: String)
        fun onMessage(message: String, mine: Boolean)
        fun onVoiceNote(bytes: ByteArray)
        fun onCallAudio(bytes: ByteArray)
        fun onCallState(active: Boolean)
    }

    companion object {
        private val APP_UUID: UUID = UUID.fromString("b8b2c1c0-4b3a-4c2d-9e1f-4d6a7b8c9d10")
        private const val MAX_PACKET = 8 * 1024 * 1024
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private val callActive = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val writing = Any()

    @SuppressLint("MissingPermission")
    fun listen() {
        if (isConnected() || connecting.get()) {
            listener.onStatus("Already connected or connecting to a Bluetooth device")
            return
        }
        if (!listening.compareAndSet(false, true)) {
            listener.onStatus("Already waiting for an incoming Bluetooth connection")
            return
        }
        Thread({
            var server: BluetoothServerSocket? = null
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("Nearby Connect", APP_UUID)
                serverSocket = server
                listener.onStatus("Waiting for connection. Keep Nearby Connect open on this phone.")
                val accepted = server.accept()
                serverSocket = null
                attach(accepted)
            } catch (e: Exception) {
                if (listening.get()) listener.onStatus("Bluetooth listening failed: ${e.message ?: "Check permissions and try again"}")
            } finally {
                try { server?.close() } catch (_: Exception) { }
                serverSocket = null
                listening.set(false)
            }
        }, "NearbyConnect-Accept").apply { isDaemon = true; start() }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (isConnected() || listening.get()) {
            listener.onStatus("Disconnect or stop the current connection attempt before connecting again")
            return
        }
        if (!connecting.compareAndSet(false, true)) {
            listener.onStatus("A Bluetooth connection attempt is already running")
            return
        }
        Thread({
            var candidate: BluetoothSocket? = null
            try {
                listener.onStatus("Connecting to ${device.name ?: "Bluetooth device"}…")
                adapter.cancelDiscovery()
                candidate = device.createRfcommSocketToServiceRecord(APP_UUID)
                candidate.connect()
                attach(candidate)
                candidate = null
            } catch (e: Exception) {
                try { candidate?.close() } catch (_: Exception) { }
                listener.onStatus("Could not connect: ${e.message ?: "On the other phone, press Wait for Incoming Connection"}")
            } finally {
                connecting.set(false)
            }
        }, "NearbyConnect-Connect").apply { isDaemon = true; start() }
    }

    private fun attach(newSocket: BluetoothSocket) {
        try {
            try { socket?.close() } catch (_: Exception) { }
            socket = newSocket
            output = DataOutputStream(newSocket.outputStream)
            listener.onStatus("Connected to ${try { newSocket.remoteDevice.name ?: "Bluetooth device" } catch (_: Exception) { "Bluetooth device" }}")
            val input = DataInputStream(newSocket.inputStream)
            Thread({
                try {
                    while (socket === newSocket && newSocket.isConnected) {
                        val type = input.readInt()
                        val size = input.readInt()
                        if (size < 0 || size > MAX_PACKET) throw IOException("Invalid packet size: $size")
                        val data = ByteArray(size)
                        input.readFully(data)
                        when (type) {
                            1 -> listener.onMessage(String(data, Charsets.UTF_8), false)
                            2 -> listener.onVoiceNote(data)
                            3 -> if (callActive.get()) listener.onCallAudio(data)
                            4 -> { callActive.set(true); listener.onCallState(true); listener.onStatus("Incoming audio call connected") }
                            5 -> { callActive.set(false); listener.onCallState(false); listener.onStatus("Call ended") }
                            else -> throw IOException("Unknown packet type: $type")
                        }
                    }
                } catch (e: Exception) {
                    if (socket === newSocket) {
                        callActive.set(false)
                        listener.onCallState(false)
                        listener.onStatus("Bluetooth connection closed: ${e.message ?: "Connection ended"}")
                        try { newSocket.close() } catch (_: Exception) { }
                        socket = null
                        output = null
                    }
                }
            }, "NearbyConnect-Reader").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            try { newSocket.close() } catch (_: Exception) { }
            if (socket === newSocket) { socket = null; output = null }
            listener.onStatus("Connection setup failed: ${e.message ?: "Unknown error"}")
        }
    }

    private fun sendPacket(type: Int, bytes: ByteArray) {
        if (bytes.size > MAX_PACKET) {
            listener.onStatus("Message or voice note is too large to send. Please use a shorter recording.")
            return
        }
        try {
            synchronized(writing) {
                val stream = output ?: throw IOException("Not connected")
                stream.writeInt(type)
                stream.writeInt(bytes.size)
                stream.write(bytes)
                stream.flush()
            }
        } catch (e: Exception) {
            listener.onStatus("Send failed: ${e.message ?: "Bluetooth is not connected"}")
        }
    }

    fun sendMessage(message: String) = sendPacket(1, message.toByteArray(Charsets.UTF_8))
    fun sendVoiceNote(bytes: ByteArray) = sendPacket(2, bytes)
    fun startCall() { callActive.set(true); sendPacket(4, ByteArray(0)); listener.onCallState(true) }
    fun endCall() { callActive.set(false); sendPacket(5, ByteArray(0)); listener.onCallState(false) }
    fun sendCallAudio(bytes: ByteArray) { if (callActive.get()) sendPacket(3, bytes) }
    fun isCallActive() = callActive.get()
    fun isConnected(): Boolean = try { socket?.isConnected == true } catch (_: Exception) { false }

    fun disconnect() {
        callActive.set(false)
        listening.set(false)
        connecting.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        serverSocket = null
        socket = null
        output = null
        listener.onCallState(false)
        listener.onStatus("Disconnected")
    }
}
