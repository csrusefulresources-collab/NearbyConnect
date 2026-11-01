package com.csr.nearbyconnect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), BluetoothPeer.Listener {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: BluetoothAdapter
    private var peer: BluetoothPeer? = null
    private val found = linkedMapOf<String, BluetoothDevice>()
    private var username = ""
    private var tab = "Chats"
    private var recorder: MediaRecorder? = null
    private var noteFile: File? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val callRecording = AtomicBoolean(false)
    private val sampleRate = 16000
    private var pendingScan = false
    private var pendingListen = false
    private var pendingDiscoverable = false
    private var pendingConnectDevice: BluetoothDevice? = null

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { found[it.address] = it; if (tab == "Chats") showChats() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    val count = found.size
                    setStatus(if (count == 0) "Scan finished. No devices found. Make the other phone discoverable and scan again." else "Scan finished. $count device(s) found. Select one to connect.")
                    if (tab == "Chats") showChats()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) setStatus("Bluetooth is on. Press Scan Devices.")
                    if (state == BluetoothAdapter.STATE_OFF) setStatus("Bluetooth is off. Turn it on to scan or connect.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(BluetoothManager::class.java)
        adapter = manager.adapter ?: run { Toast.makeText(this, "Bluetooth is not supported", Toast.LENGTH_LONG).show(); finish(); return }
        username = getPreferences(MODE_PRIVATE).getString("username", "") ?: ""
        peer = BluetoothPeer(adapter, this)
        val discoveryFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(discoveryReceiver, discoveryFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(discoveryReceiver, discoveryFilter)
        }
        buildShell()
        if (username.isBlank()) showSetup() else showChats()
    }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF4F7F6.toInt()) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 14); setBackgroundColor(0xFF075E54.toInt()) }
        header.addView(TextView(this).apply { text = "Nearby Connect"; textSize = 23f; setTextColor(-1); contentDescription = "Nearby Connect" })
        status = TextView(this).apply { text = "Private Bluetooth messaging"; textSize = 13f; setTextColor(0xFFE0F2EF.toInt()); contentDescription = "Connection status" }
        header.addView(status)
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(-1); gravity = Gravity.CENTER }
        listOf("Chats", "Calls", "Settings").forEach { name ->
            nav.addView(Button(this).apply { text = name; contentDescription = "$name tab"; setOnClickListener { tab = name; when (name) { "Chats" -> showChats(); "Calls" -> showCalls(); else -> showSettings() } } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun heading(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 20f; setTextColor(0xFF173C36.toInt()); setPadding(0, 4, 0, 12) }) }
    private fun label(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(0xFF263B37.toInt()); setPadding(0, 8, 0, 8) }) }
    private fun button(text: String, action: () -> Unit) { content.addView(Button(this).apply { this.text = text; contentDescription = text; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 }) }
    private fun edit(hint: String): EditText = EditText(this).apply { this.hint = hint; contentDescription = hint; textSize = 16f; singleLine = true }
    private fun showSetup() {
        content.removeAllViews(); heading("Welcome")
        label("Create a username so nearby contacts can recognize you.")
        val name = edit("Username")
        content.addView(name)
        button("Save Username") { val value = name.text.toString().trim(); if (value.length < 3) toast("Username must be at least 3 characters") else { username = value; getPreferences(MODE_PRIVATE).edit().putString("username", value).apply(); showChats() } }
        button("Skip Permissions for Now") { showChats() }
        label("You can grant permissions later in Settings. Bluetooth, microphone and SMS features will remain unavailable until their permissions are granted.")
        button("Grant Permissions") { requestAppPermissions() }
    }

    private fun showChats() {
        content.removeAllViews(); heading("Chats")
        label("Your username: ${if (username.isBlank()) "Not set" else username}")
        button("Scan Devices") { startDiscovery() }
        button("Stop Scan") { stopDiscovery() }
        button("Show Paired Devices") { loadPairedDevices(); showChats() }
        button("Make This Phone Discoverable") { makeDiscoverable() }
        button("Wait for Incoming Connection") { waitForIncomingConnection() }
        label("Nearby and paired devices")
        if (found.isEmpty()) label("No devices listed. Press Scan Devices. On the other phone, open Nearby Connect and press Make This Phone Discoverable.")
        found.values.forEach { device ->
            @SuppressLint("MissingPermission")
            val title = try { "${device.name ?: "Unknown device"} (${device.address})" } catch (_: Exception) { "Bluetooth device" }
            button("Connect: $title") { if (hasBluetoothPermission()) connectToDevice(device) else { pendingConnectDevice = device; requestBluetoothPermissions() } }
        }
        label("Messages")
        button("Disconnect") { peer?.disconnect() }
        val message = edit("Type a message")
        content.addView(message)
        button("Send Message") {
            val value = message.text.toString().trim()
            if (value.isNotEmpty()) {
                if (peer?.isConnected() == true) {
                    peer?.sendMessage("$username: $value")
                    addBubble("Me: $value")
                    message.setText("")
                } else toast("Connect to a nearby device first")
            }
        }
        button("Record Voice Note") { startVoiceNote() }
        button("Stop and Send Voice Note") { stopVoiceNote(true) }
        button("Send SMS") { showSmsDialog() }
    }

    private fun showCalls() {
        content.removeAllViews(); heading("Calls")
        label("Bluetooth audio calls work between Nearby Connect users while connected.")
        button("Start Audio Call") {
            if (peer?.isConnected() != true) toast("Connect to a nearby device first")
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
            else startAudioCall()
        }
        button("End Call") { stopAudioCall(); peer?.endCall() }
        button("Back to Chats") { tab = "Chats"; showChats() }
    }

    private fun showSettings() {
        content.removeAllViews(); heading("Settings")
        val name = edit("Username"); name.setText(username); content.addView(name)
        button("Save Username") { val value = name.text.toString().trim(); if (value.length >= 3) { username = value; getPreferences(MODE_PRIVATE).edit().putString("username", value).apply(); toast("Username saved") } else toast("Enter at least 3 characters") }
        button("Grant / Review Permissions") { requestAppPermissions() }
        button("Scan Devices") { tab = "Chats"; showChats(); startDiscovery() }
        button("Open Bluetooth Settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("Open Location Settings (Android 11 and below)") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        button("About Nearby Connect") { label("Version 1.2.0. Bluetooth Classic peer-to-peer messenger. Both phones need this app. One phone must wait for an incoming connection while the other connects.") }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!hasBluetoothPermission()) {
            pendingScan = true
            requestBluetoothPermissions()
            return
        }
        if (!adapter.isEnabled) {
            pendingScan = true
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 304)
            return
        }
        if (Build.VERSION.SDK_INT <= 30) {
            val location = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!location.isLocationEnabled) {
                setStatus("Turn on Location in Android Settings, then press Scan Devices again.")
                toast("Android 11 and below require Location to be on for Bluetooth scanning")
                return
            }
        }
        pendingScan = false
        found.clear()
        loadPairedDevices()
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            val started = adapter.startDiscovery()
            if (started) {
                setStatus("Scanning for nearby Bluetooth devices. Keep this screen open.")
            } else {
                setStatus("Android could not start scanning. Check Bluetooth and permissions, then retry.")
                toast("Scan did not start. Try again or open Bluetooth Settings.")
            }
            showChats()
        } catch (e: SecurityException) {
            pendingScan = true
            setStatus("Bluetooth permission denied. Allow Nearby devices permission and scan again.")
            requestBluetoothPermissions()
        } catch (e: Exception) {
            setStatus("Scan error: ${e.message ?: "Unknown error"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasBluetoothPermission()) return
        try {
            adapter.bondedDevices?.forEach { device -> found[device.address] = device }
        } catch (e: Exception) {
            setStatus("Could not read paired devices: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            setStatus("Scan stopped. ${found.size} device(s) available.")
        } catch (e: Exception) { setStatus("Could not stop scan: ${e.message}") }
    }

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestBluetoothPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isEmpty()) continuePendingBluetoothAction()
        else requestPermissions(needed.toTypedArray(), 304)
    }

    private fun requestAppPermissions() {
        requestBluetoothPermissions()
        toast("Microphone permission will be requested when you start a call or voice note. SMS permission will be requested when you send an SMS.")
    }

    @SuppressLint("MissingPermission")
    private fun makeDiscoverable() {
        if (!hasBluetoothPermission() || (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            pendingDiscoverable = true
            requestBluetoothPermissions()
            return
        }
        if (!adapter.isEnabled) {
            pendingDiscoverable = true
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 304)
            return
        }
        pendingDiscoverable = false
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 305)
        } catch (e: Exception) { setStatus("Could not enable visibility: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun waitForIncomingConnection() {
        if (!hasBluetoothPermission()) {
            pendingListen = true
            requestBluetoothPermissions()
            return
        }
        if (!adapter.isEnabled) {
            pendingListen = true
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 304)
            return
        }
        pendingListen = false
        peer?.listen()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) {
            pendingConnectDevice = device
            requestBluetoothPermissions()
            return
        }
        if (!adapter.isEnabled) {
            pendingConnectDevice = device
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 304)
            return
        }
        pendingConnectDevice = null
        peer?.connect(device)
    }

    @SuppressLint("MissingPermission")
    private fun continuePendingBluetoothAction() {
        when {
            pendingScan -> startDiscovery()
            pendingListen -> waitForIncomingConnection()
            pendingDiscoverable -> makeDiscoverable()
            pendingConnectDevice != null -> {
                val device = pendingConnectDevice
                if (device != null) connectToDevice(device)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            301 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startAudioCall() else toast("Microphone permission is required for calls")
            302 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceNote() else toast("Microphone permission is required for voice notes")
            303 -> if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("SMS permission was not granted")
            306 -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    try {
                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        startAudioCapture()
                    } catch (e: Exception) {
                        toast("Could not answer call: ${e.message}")
                        peer?.endCall()
                    }
                } else {
                    toast("Microphone permission denied. The incoming call will end.")
                    peer?.endCall()
                    stopAudioCall()
                }
            }
            304 -> {
                val granted = hasBluetoothPermission()
                if (granted) {
                    if (pendingDiscoverable && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                        pendingDiscoverable = false
                        toast("Allow Nearby devices permission to make this phone discoverable")
                    } else continuePendingBluetoothAction()
                } else {
                    pendingScan = false; pendingListen = false; pendingDiscoverable = false; pendingConnectDevice = null
                    setStatus("Nearby devices permission was denied. Allow it in App info > Permissions.")
                    toast("Bluetooth scan and connection need Nearby devices permission")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 304) {
            if (resultCode == RESULT_OK) continuePendingBluetoothAction()
            else if (resultCode != RESULT_OK) {
                pendingScan = false; pendingListen = false; pendingDiscoverable = false
                setStatus("Bluetooth must be turned on before scanning or connecting.")
            }
        }
    }

    private fun startVoiceNote() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 302); return }
        try {
            noteFile = File(cacheDir, "voice-note-${System.currentTimeMillis()}.m4a")
            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder?.apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(64000); setAudioSamplingRate(22050); setOutputFile(noteFile!!.absolutePath); prepare(); start() }
            toast("Recording voice note. Press Stop and Send Voice Note when finished.")
        } catch (e: Exception) { toast("Recording failed: ${e.message}"); stopVoiceNote(false) }
    }

    private fun stopVoiceNote(send: Boolean) {
        try { recorder?.stop() } catch (_: Exception) {}; try { recorder?.release() } catch (_: Exception) {}; recorder = null
        val file = noteFile; noteFile = null
        if (send && file != null && file.exists() && file.length() > 0) {
            if (peer?.isConnected() == true) {
                peer?.sendVoiceNote(file.readBytes())
                addBubble("Voice note sent")
                toast("Voice note sent")
            } else {
                toast("Not connected. Voice note was not sent.")
            }
        }
        file?.delete()
    }

    private fun startAudioCall() {
        if (peer?.isConnected() != true) { toast("Connect to a nearby device first"); return }
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            peer?.startCall()
            startAudioCapture()
            toast("Audio call started")
        } catch (e: Exception) { stopAudioCall(); peer?.endCall(); toast("Call could not start: ${e.message}") }
    }

    private fun startAudioCapture() {
        if (callRecording.get()) return
        try {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Audio recorder could not initialize")
            audioRecord?.startRecording()
            callRecording.set(true)
            Thread({
                val buffer = ByteArray(min)
                while (callRecording.get()) {
                    val count = try { audioRecord?.read(buffer, 0, buffer.size) ?: -1 } catch (_: Exception) { -1 }
                    if (count > 0) peer?.sendCallAudio(buffer.copyOf(count))
                    else if (count < 0) { callRecording.set(false); break }
                }
            }, "NearbyConnect-Microphone").apply { isDaemon = true; start() }
        } catch (e: Exception) { stopAudioCall(); peer?.endCall(); setStatus("Microphone could not start: ${e.message}") }
    }

    private fun stopAudioCall() {
        callRecording.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}; try { audioRecord?.release() } catch (_: Exception) {}; audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}; try { audioTrack?.release() } catch (_: Exception) {}; audioTrack = null
        try {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) { }
    }

    private fun playCallAudio(bytes: ByteArray) {
        try {
            if (audioTrack == null) {
                val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
                audioTrack = AudioTrack.Builder().setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(min * 2).build()
                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("Speaker audio output could not initialize")
                audioTrack?.play()
            }
            var offset = 0
            while (offset < bytes.size) {
                val written = audioTrack?.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING) ?: -1
                if (written <= 0) break
                offset += written
            }
        } catch (_: Exception) { }
    }

    private fun showSmsDialog() {
        val phone = edit("Phone number, e.g. +923001234567")
        val body = edit("SMS message")
        content.addView(phone); content.addView(body)
        button("Send SMS Now") {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 303); return@button }
            val number = phone.text.toString().trim()
            val message = body.text.toString().trim()
            if (number.isBlank() || message.isBlank()) {
                toast("Enter both a phone number and an SMS message")
                return@button
            }
            try { SmsManager.getDefault().sendTextMessage(number, null, message, null, null); toast("SMS submitted to mobile network") }
            catch (e: Exception) { toast("SMS failed: ${e.message}") }
        }
        label("SMS uses your SIM/mobile network and may incur charges. It is not sent over Bluetooth.")
    }

    private fun addBubble(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 16f; setTextColor(0xFF173C36.toInt()); setPadding(12, 12, 12, 12); setBackgroundColor(-1); contentDescription = text }) }
    private fun setStatus(message: String) { runOnUiThread { status.text = message; status.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT) } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStatus(message: String) = setStatus(message)
    override fun onMessage(message: String, mine: Boolean) { runOnUiThread { if (tab != "Chats") { tab = "Chats"; showChats() }; addBubble(message) } }
    override fun onVoiceNote(bytes: ByteArray) {
        Thread({
            try {
                val directory = getExternalFilesDir(null) ?: filesDir
                val file = File(directory, "received-${System.currentTimeMillis()}.m4a")
                file.writeBytes(bytes)
                runOnUiThread {
                    if (tab != "Chats") { tab = "Chats"; showChats() }
                    addBubble("Voice note received: ${file.name}")
                    toast("Voice note received and saved")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not save received voice note: ${e.message}") }
            }
        }, "NearbyConnect-SaveVoiceNote").apply { isDaemon = true; start() }
    }
    override fun onCallAudio(bytes: ByteArray) { playCallAudio(bytes) }
    override fun onCallState(active: Boolean) {
        if (active) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } catch (_: Exception) { }
                startAudioCapture()
            }
            else runOnUiThread {
                toast("Allow microphone permission to answer the incoming call")
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 306)
            }
        } else stopAudioCall()
    }

    override fun onDestroy() {
        try { if (::adapter.isInitialized && adapter.isDiscovering && hasBluetoothPermission()) adapter.cancelDiscovery() } catch (_: Exception) { }
        stopVoiceNote(false); stopAudioCall(); peer?.disconnect()
        try { unregisterReceiver(discoveryReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
