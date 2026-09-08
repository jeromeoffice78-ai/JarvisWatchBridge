package com.jarvis.watchbridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID

class BleManager(private val context: Context) {
    data class Device(
        val name: String,
        val address: String,
        val jarvisTarget: Boolean = false
    )

    data class State(
        val scanning: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val connectedAddress: String? = null,
        val connectingAddress: String? = null,
        val heartRateBpm: Int? = null,
        val services: List<String> = emptyList(),
        val error: String? = null
    )

    private data class Candidate(
        val device: BluetoothDevice,
        val autoConnect: Boolean,
        val label: String
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val prefs = context.getSharedPreferences("jarvis_ble_identity", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var shouldReconnect = true
    private var scanGeneration = 0
    private var modernScanResults = 0
    private var legacyScanResults = 0
    private var legacyScanning = false
    private var lastLiveTarget: BluetoothDevice? = null
    private val seenDevices = LinkedHashMap<String, BluetoothDevice>()
    private val attemptQueue = ArrayDeque<Candidate>()

    companion object {
        // Historical addresses from the watch. The live ScanResult BluetoothDevice is always preferred,
        // because a BLE address can be private/rotating and Android also retains address-type metadata
        // on the BluetoothDevice obtained from scanning.
        const val TARGET_WATCH_LE_ADDRESS = "41:42:69:41:49:D5"
        const val TARGET_WATCH_BT_ADDRESS = "41:42:69:41:49:80"
        private val TARGET_WATCH_NAMES = listOf(
            "JARVIS WATCH",
            "LAXASFIT",
            "LAXAS FIT",
            "V19",
            "SMART WATCH",
            "SMARTWATCH",
            "WATCH"
        )

        private const val MODERN_SCAN_MS = 10_000L
        private const val LEGACY_SCAN_MS = 8_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 18_000L
        private const val AUTO_CONNECT_TIMEOUT_MS = 35_000L
        private const val RECONNECT_DELAY_MS = 5_000L

        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val reconnectRunnable = Runnable {
        if (shouldReconnect && _state.value.connectedAddress == null && _state.value.connectingAddress == null) {
            connectTargetWatch()
        }
    }

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    private fun bluetoothReady(): Boolean {
        val bluetoothAdapter = adapter ?: run {
            _state.value = _state.value.copy(error = "Bluetooth is unavailable on this device")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            _state.value = _state.value.copy(error = "Turn Bluetooth on")
            return false
        }
        return true
    }

    private fun rememberedAddress(): String? = prefs.getString("target_address", null)

    private fun isJarvisTarget(
        name: String?,
        address: String,
        advertisedServices: Collection<UUID> = emptyList()
    ): Boolean {
        if (address.equals(TARGET_WATCH_LE_ADDRESS, ignoreCase = true)) return true
        if (address.equals(TARGET_WATCH_BT_ADDRESS, ignoreCase = true)) return true
        if (rememberedAddress()?.equals(address, ignoreCase = true) == true) return true
        if (HEART_RATE_SERVICE in advertisedServices) return true

        val normalized = name.orEmpty().trim().uppercase()
        return normalized.isNotBlank() && TARGET_WATCH_NAMES.any { token -> normalized.contains(token) }
    }

    @SuppressLint("MissingPermission")
    private fun rememberTarget(device: BluetoothDevice, name: String?) {
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: runCatching { device.name }.getOrNull()
            ?: "JARVIS Watch"
        prefs.edit()
            .putString("target_address", device.address)
            .putString("target_name", resolvedName)
            .apply()
        lastLiveTarget = device
    }

    @SuppressLint("MissingPermission")
    private fun bondedWatchDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        return runCatching {
            adapter?.bondedDevices.orEmpty().filter { device ->
                val name = runCatching { device.name }.getOrNull()
                isJarvisTarget(name, device.address)
            }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    private fun systemConnectedWatch(): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        return runCatching {
            bluetoothManager
                ?.getConnectedDevices(BluetoothProfile.GATT)
                ?.firstOrNull { device ->
                    val name = runCatching { device.name }.getOrNull()
                    isJarvisTarget(name, device.address)
                }
        }.getOrNull()
    }

    private fun mergeDevice(device: Device) {
        val updated = (_state.value.devices + device)
            .distinctBy { it.address.uppercase() }
            .sortedWith(compareByDescending<Device> { it.jarvisTarget }.thenBy { it.name.lowercase() })
        _state.value = _state.value.copy(devices = updated)
    }

    @SuppressLint("MissingPermission")
    private fun handleDiscoveredDevice(
        device: BluetoothDevice,
        advertisedName: String?,
        advertisedServices: Collection<UUID> = emptyList()
    ) {
        if (!hasConnectPermission()) return

        val address = device.address
        val name = runCatching { device.name }.getOrNull()
            ?: advertisedName
            ?: "BLE device"
        val target = isJarvisTarget(name, address, advertisedServices)

        seenDevices[address.uppercase()] = device
        mergeDevice(Device(name, address, target))

        if (target) {
            rememberTarget(device, name)
            if (_state.value.connectedAddress == null && _state.value.connectingAddress == null) {
                beginConnectionPlan(device, "live BLE scan")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            modernScanResults += 1
            val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            handleDiscoveredDevice(result.device, result.scanRecord?.deviceName, services)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                modernScanResults += 1
                val services = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
                handleDiscoveredDevice(result.device, result.scanRecord?.deviceName, services)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!_state.value.scanning) return
            _state.value = _state.value.copy(error = "Modern BLE scan failed ($errorCode); trying compatibility scan")
            startLegacyScan(scanGeneration)
        }
    }

    private val legacyScanCallback = BluetoothAdapter.LeScanCallback { device, _, _ ->
        legacyScanResults += 1
        handleDiscoveredDevice(device, null)
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScans() {
        if (hasScanPermission()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            if (legacyScanning) runCatching { adapter?.stopLeScan(legacyScanCallback) }
        }
        legacyScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun startModernScan(resetDevices: Boolean) {
        if (!bluetoothReady()) return
        if (!hasScanPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices permission required")
            return
        }

        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(error = "Bluetooth LE scanner unavailable")
            return
        }

        scanGeneration += 1
        val generation = scanGeneration
        modernScanResults = 0
        legacyScanResults = 0
        stopActiveScans()
        if (resetDevices) seenDevices.clear()

        val bonded = bondedWatchDevices().map { device ->
            Device(runCatching { device.name }.getOrNull() ?: "Paired watch", device.address, true)
        }

        _state.value = _state.value.copy(
            scanning = true,
            connectingAddress = null,
            devices = if (resetDevices) bonded else (_state.value.devices + bonded).distinctBy { it.address.uppercase() },
            error = "Scanning for the watch's live BLE identity…"
        )

        runCatching {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build(),
                scanCallback
            )
        }.onFailure {
            _state.value = _state.value.copy(error = "BLE scan could not start: ${it.message ?: "unknown error"}")
            startLegacyScan(generation)
            return
        }

        handler.postDelayed({
            if (generation != scanGeneration || _state.value.connectedAddress != null || _state.value.connectingAddress != null) {
                return@postDelayed
            }
            startLegacyScan(generation)
        }, MODERN_SCAN_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyScan(generation: Int) {
        if (generation != scanGeneration || _state.value.connectedAddress != null || _state.value.connectingAddress != null) return
        if (!hasScanPermission()) return

        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        legacyScanning = runCatching { adapter?.startLeScan(legacyScanCallback) == true }.getOrDefault(false)
        _state.value = _state.value.copy(
            scanning = true,
            error = "Still looking. Modern scan saw $modernScanResults BLE device(s); running compatibility scan…"
        )

        handler.postDelayed({
            if (generation != scanGeneration || _state.value.connectedAddress != null || _state.value.connectingAddress != null) {
                return@postDelayed
            }
            stopActiveScans()
            _state.value = _state.value.copy(scanning = false)
            beginFallbackPlan("Scan saw ${modernScanResults + legacyScanResults} BLE result(s), but did not identify the watch")
        }, LEGACY_SCAN_MS)
    }

    fun startScan() {
        shouldReconnect = true
        handler.removeCallbacks(reconnectRunnable)
        if (_state.value.connectedAddress != null) return
        clearPendingConnection()
        startModernScan(resetDevices = true)
    }

    fun stopScan() {
        scanGeneration += 1
        stopActiveScans()
        _state.value = _state.value.copy(scanning = false)
    }

    @SuppressLint("MissingPermission")
    fun connectTargetWatch() {
        shouldReconnect = true
        handler.removeCallbacks(reconnectRunnable)
        if (!bluetoothReady()) return
        if (!hasConnectPermission() || !hasScanPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices permission required")
            return
        }
        if (_state.value.connectedAddress != null) return

        clearPendingConnection()

        systemConnectedWatch()?.let { device ->
            rememberTarget(device, runCatching { device.name }.getOrNull())
            beginConnectionPlan(device, "Android already-connected watch")
            return
        }

        lastLiveTarget?.let { device ->
            beginConnectionPlan(device, "last live watch")
            return
        }

        startModernScan(resetDevices = true)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        shouldReconnect = true
        handler.removeCallbacks(reconnectRunnable)

        val device = seenDevices[address.uppercase()] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            _state.value = _state.value.copy(error = "Could not open Bluetooth device $address")
            return
        }

        val name = runCatching { device.name }.getOrNull()
        rememberTarget(device, name)
        val existing = _state.value.devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
        if (existing != null && !existing.jarvisTarget) {
            mergeDevice(existing.copy(jarvisTarget = true))
        }
        beginConnectionPlan(device, "selected device")
    }

    @SuppressLint("MissingPermission")
    private fun beginConnectionPlan(firstDevice: BluetoothDevice, reason: String) {
        scanGeneration += 1
        stopActiveScans()
        clearPendingConnection()
        attemptQueue.clear()

        addCandidate(firstDevice, false, reason)
        addCandidate(firstDevice, true, "$reason background retry")

        systemConnectedWatch()?.let { addCandidate(it, false, "Android connected-device record") }
        lastLiveTarget?.let { addCandidate(it, false, "last live scan device") }
        bondedWatchDevices().forEach { device ->
            addCandidate(device, false, "paired watch")
        }

        // Historical addresses are deliberately last. A live ScanResult BluetoothDevice is safer for
        // private/random BLE addressing and carries the address-type information Android discovered.
        remoteDeviceOrNull(rememberedAddress())?.let { addCandidate(it, false, "remembered address fallback") }
        remoteDeviceOrNull(TARGET_WATCH_LE_ADDRESS)?.let { addCandidate(it, false, "confirmed LE address") }
        remoteDeviceOrNull(TARGET_WATCH_BT_ADDRESS)?.let { addCandidate(it, false, "confirmed Bluetooth address") }

        tryNextCandidate(null)
    }

    @SuppressLint("MissingPermission")
    private fun beginFallbackPlan(reason: String) {
        attemptQueue.clear()

        systemConnectedWatch()?.let { addCandidate(it, false, "Android connected-device record") }
        lastLiveTarget?.let { addCandidate(it, false, "last live scan device") }
        bondedWatchDevices().forEach { device -> addCandidate(device, false, "paired watch") }
        remoteDeviceOrNull(rememberedAddress())?.let { addCandidate(it, false, "remembered address fallback") }
        remoteDeviceOrNull(TARGET_WATCH_LE_ADDRESS)?.let { addCandidate(it, false, "confirmed LE address") }
        remoteDeviceOrNull(TARGET_WATCH_BT_ADDRESS)?.let { addCandidate(it, false, "confirmed Bluetooth address") }

        if (attemptQueue.isEmpty()) {
            _state.value = _state.value.copy(
                scanning = false,
                connectingAddress = null,
                error = "$reason. Tap Scan watches; if JARVIS cannot identify the name automatically, open System controls and tap your watch from the detected list."
            )
            scheduleReconnect()
            return
        }

        tryNextCandidate(reason)
    }

    @SuppressLint("MissingPermission")
    private fun remoteDeviceOrNull(address: String?): BluetoothDevice? {
        if (address.isNullOrBlank()) return null
        return runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
    }

    private fun addCandidate(device: BluetoothDevice, autoConnect: Boolean, label: String) {
        val address = device.address
        if (address.isBlank()) return
        if (attemptQueue.any { candidate ->
                candidate.device.address.equals(address, ignoreCase = true) && candidate.autoConnect == autoConnect
            }) return
        attemptQueue.addLast(Candidate(device, autoConnect, label))
    }

    @SuppressLint("MissingPermission")
    private fun tryNextCandidate(lastFailure: String?) {
        if (!shouldReconnect || _state.value.connectedAddress != null) return

        val candidate = attemptQueue.pollFirst()
        if (candidate == null) {
            _state.value = _state.value.copy(
                scanning = false,
                connectingAddress = null,
                error = buildString {
                    append("Watch is not connected")
                    if (!lastFailure.isNullOrBlank()) append(": $lastFailure")
                    append(". JARVIS will rescan using the watch's live BLE identity.")
                }
            )
            scheduleReconnect()
            return
        }

        clearPendingConnection()

        val device = candidate.device
        val address = device.address
        _state.value = _state.value.copy(
            scanning = false,
            connectingAddress = address,
            error = "Connecting ${candidate.label}: $address${if (candidate.autoConnect) " (background)" else ""}"
        )

        val newGatt = runCatching {
            // Do not force a PHY here. Some low-cost wearable chipsets fail to complete GATT setup
            // when Android's PHY-specific overload is used. TRANSPORT_LE is sufficient and lets the
            // platform use the address type learned from the live ScanResult BluetoothDevice.
            device.connectGatt(
                context,
                candidate.autoConnect,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(connectingAddress = null)
            handler.post { tryNextCandidate("connectGatt failed for $address: ${error.message ?: "unknown error"}") }
            return
        }

        gatt = newGatt

        val timeout = if (candidate.autoConnect) AUTO_CONNECT_TIMEOUT_MS else DIRECT_CONNECT_TIMEOUT_MS
        handler.postDelayed({
            if (gatt === newGatt && _state.value.connectedAddress == null) {
                gatt = null
                runCatching { newGatt.disconnect() }
                runCatching { newGatt.close() }
                _state.value = _state.value.copy(connectingAddress = null)
                tryNextCandidate("timeout on $address")
            }
        }, timeout)
    }

    @SuppressLint("MissingPermission")
    private fun adoptConnectedGatt(connection: BluetoothGatt) {
        gatt = connection
        attemptQueue.clear()
        scanGeneration += 1
        stopActiveScans()
        handler.removeCallbacks(reconnectRunnable)

        val name = runCatching { connection.device.name }.getOrNull() ?: "JARVIS Watch"
        val address = connection.device.address
        rememberTarget(connection.device, name)

        _state.value = _state.value.copy(
            scanning = false,
            connectedName = name,
            connectedAddress = address,
            connectingAddress = null,
            error = null
        )

        runCatching { connection.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        val discoveryStarted = runCatching { connection.discoverServices() }.getOrDefault(false)
        if (!discoveryStarted) {
            _state.value = _state.value.copy(error = "Watch connected; Android delayed GATT service discovery. Retrying…")
            handler.postDelayed({
                if (gatt === connection && _state.value.connectedAddress != null) {
                    runCatching { connection.discoverServices() }
                }
            }, 1_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearPendingConnection() {
        val oldGatt = gatt
        gatt = null
        if (oldGatt != null && hasConnectPermission()) runCatching { oldGatt.disconnect() }
        runCatching { oldGatt?.close() }
        _state.value = _state.value.copy(connectingAddress = null)
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        scanGeneration += 1
        stopActiveScans()
        attemptQueue.clear()
        clearPendingConnection()
        _state.value = _state.value.copy(
            scanning = false,
            connectedName = null,
            connectedAddress = null,
            connectingAddress = null,
            heartRateBpm = null,
            services = emptyList(),
            error = null
        )
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
            val address = connection.device.address
            val expectedAddress = _state.value.connectingAddress
            val isExpected = expectedAddress?.equals(address, ignoreCase = true) == true
            val name = runCatching { connection.device.name }.getOrNull()
            val isTarget = isJarvisTarget(name, address)

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                // A connectGatt callback can arrive before connectGatt() returns on some Android
                // Bluetooth stacks. Accept the callback if it is the current address or a known target.
                if (gatt === connection || isExpected || isTarget) {
                    adoptConnectedGatt(connection)
                    return
                }
            }

            if (gatt !== connection) {
                runCatching { connection.close() }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                gatt = null
                runCatching { connection.close() }
                _state.value = _state.value.copy(
                    connectedName = null,
                    connectedAddress = null,
                    connectingAddress = null,
                    heartRateBpm = null,
                    services = emptyList(),
                    error = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Watch disconnected"
                    } else {
                        "Watch GATT status $status; trying the next live connection method"
                    }
                )

                if (attemptQueue.isNotEmpty()) {
                    handler.postDelayed({ tryNextCandidate("GATT $status on $address") }, 700L)
                } else {
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
            if (gatt !== connection) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(error = "Watch connected, but service discovery failed ($status). Retrying…")
                handler.postDelayed({
                    if (gatt === connection) runCatching { connection.discoverServices() }
                }, 1_500L)
                return
            }

            _state.value = _state.value.copy(
                services = connection.services.map { it.uuid.toString() },
                error = null
            )

            val heartRate = connection.getService(HEART_RATE_SERVICE)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
                ?: return

            val notifyEnabled = runCatching {
                connection.setCharacteristicNotification(heartRate, true)
            }.getOrDefault(false)
            if (!notifyEnabled) return

            heartRate.getDescriptor(CCCD)?.let { descriptor ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    connection.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT || value.isEmpty()) return
            val flags = value[0].toInt()
            val bpm = if ((flags and 0x01) == 0) {
                value.getOrNull(1)?.toInt()?.and(0xFF)
            } else if (value.size >= 3) {
                ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
            } else {
                null
            }
            if (bpm != null) _state.value = _state.value.copy(heartRateBpm = bpm)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }
    }
}
