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
        val address: String,
        val autoConnect: Boolean,
        val label: String
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var shouldReconnect = true
    private var scanGeneration = 0
    private var modernScanResults = 0
    private var legacyScanResults = 0
    private var legacyScanning = false
    private val attemptQueue = ArrayDeque<Candidate>()

    companion object {
        // Confirmed directly from the watch About screen.
        const val TARGET_WATCH_LE_ADDRESS = "41:42:69:41:49:D5"
        const val TARGET_WATCH_BT_ADDRESS = "41:42:69:41:49:80"
        private val TARGET_WATCH_NAMES = listOf("JARVIS WATCH", "WATCH", "V19", "LAXASFIT")

        private const val MODERN_SCAN_MS = 6_000L
        private const val LEGACY_SCAN_MS = 6_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 10_000L
        private const val AUTO_CONNECT_TIMEOUT_MS = 18_000L
        private const val RECONNECT_DELAY_MS = 4_000L

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
            _state.value = _state.value.copy(error = "Bluetooth is unavailable on this tablet")
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            _state.value = _state.value.copy(error = "Turn Bluetooth on")
            return false
        }
        return true
    }

    private fun isJarvisTarget(name: String?, address: String): Boolean {
        if (address.equals(TARGET_WATCH_LE_ADDRESS, ignoreCase = true)) return true
        if (address.equals(TARGET_WATCH_BT_ADDRESS, ignoreCase = true)) return true
        val normalized = name.orEmpty().uppercase()
        return TARGET_WATCH_NAMES.any { normalized == it || normalized.contains(it) }
    }

    @SuppressLint("MissingPermission")
    private fun bondedWatchDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        return runCatching {
            adapter?.bondedDevices.orEmpty().filter { device ->
                isJarvisTarget(device.name, device.address)
            }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    private fun watchAlreadyHeldByAnotherGattClient(): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        if (_state.value.connectedAddress != null) return null
        return runCatching {
            bluetoothManager
                ?.getConnectedDevices(BluetoothProfile.GATT)
                ?.firstOrNull { device -> isJarvisTarget(device.name, device.address) }
        }.getOrNull()
    }

    private fun mergeDevice(device: Device) {
        val updated = (_state.value.devices + device)
            .distinctBy { it.address.uppercase() }
            .sortedWith(compareByDescending<Device> { it.jarvisTarget }.thenBy { it.name.lowercase() })
        _state.value = _state.value.copy(devices = updated)
    }

    private fun handleDiscoveredDevice(device: BluetoothDevice, advertisedName: String?) {
        if (!hasConnectPermission()) return
        @SuppressLint("MissingPermission")
        val name = runCatching { device.name }.getOrNull() ?: advertisedName ?: "BLE device"
        val address = device.address
        val target = isJarvisTarget(name, address)
        mergeDevice(Device(name, address, target))

        if (target && _state.value.connectedAddress == null && _state.value.connectingAddress == null) {
            beginConnectionPlan(address, "scan result")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            modernScanResults += 1
            handleDiscoveredDevice(result.device, result.scanRecord?.deviceName)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                modernScanResults += 1
                handleDiscoveredDevice(result.device, result.scanRecord?.deviceName)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!_state.value.scanning) return
            _state.value = _state.value.copy(error = "Modern BLE scan failed ($errorCode); trying legacy scan")
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

        val bonded = bondedWatchDevices().map { device ->
            Device(device.name ?: "Paired watch", device.address, true)
        }

        _state.value = _state.value.copy(
            scanning = true,
            connectingAddress = null,
            devices = if (resetDevices) bonded else (_state.value.devices + bonded).distinctBy { it.address.uppercase() },
            error = "Scanning for watch on tablet…"
        )

        scanner.startScan(
            null,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build(),
            scanCallback
        )

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
            error = "No watch yet. Modern scan saw $modernScanResults result(s); trying legacy BLE scan…"
        )

        handler.postDelayed({
            if (generation != scanGeneration || _state.value.connectedAddress != null || _state.value.connectingAddress != null) {
                return@postDelayed
            }
            stopActiveScans()
            _state.value = _state.value.copy(scanning = false)
            beginFallbackPlan("Scanner saw ${modernScanResults + legacyScanResults} BLE result(s), but not the watch")
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

        watchAlreadyHeldByAnotherGattClient()?.let { held ->
            _state.value = _state.value.copy(
                scanning = false,
                connectingAddress = null,
                error = "Watch BLE is already connected through another app (${held.address}). Force-stop Laxasfit, then tap Reconnect watch."
            )
            scheduleReconnect()
            return
        }

        clearPendingConnection()
        startModernScan(resetDevices = true)
    }

    fun connect(address: String) {
        shouldReconnect = true
        handler.removeCallbacks(reconnectRunnable)
        beginConnectionPlan(address, "selected device")
    }

    @SuppressLint("MissingPermission")
    private fun beginConnectionPlan(firstAddress: String, reason: String) {
        scanGeneration += 1
        stopActiveScans()
        clearPendingConnection()
        attemptQueue.clear()

        addCandidate(firstAddress, false, reason)
        addCandidate(TARGET_WATCH_LE_ADDRESS, false, "confirmed LE address")
        addCandidate(TARGET_WATCH_BT_ADDRESS, false, "watch BT identity address")
        bondedWatchDevices().forEach { device ->
            addCandidate(device.address, false, "paired Watch on tablet")
        }
        addCandidate(TARGET_WATCH_LE_ADDRESS, true, "background LE reconnect")

        tryNextCandidate(null)
    }

    @SuppressLint("MissingPermission")
    private fun beginFallbackPlan(reason: String) {
        attemptQueue.clear()
        addCandidate(TARGET_WATCH_LE_ADDRESS, false, "confirmed LE address")
        addCandidate(TARGET_WATCH_BT_ADDRESS, false, "watch BT identity address")
        bondedWatchDevices().forEach { device ->
            addCandidate(device.address, false, "paired Watch on tablet")
        }
        addCandidate(TARGET_WATCH_LE_ADDRESS, true, "background LE reconnect")
        tryNextCandidate(reason)
    }

    private fun addCandidate(address: String, autoConnect: Boolean, label: String) {
        if (address.isBlank()) return
        if (attemptQueue.any { it.address.equals(address, ignoreCase = true) && it.autoConnect == autoConnect }) return
        attemptQueue.addLast(Candidate(address, autoConnect, label))
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
                    append("Watch BLE not connected")
                    if (!lastFailure.isNullOrBlank()) append(": $lastFailure")
                    append(". Retrying scan automatically.")
                }
            )
            scheduleReconnect()
            return
        }

        val device = try {
            adapter?.getRemoteDevice(candidate.address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            tryNextCandidate("invalid address ${candidate.address}")
            return
        }

        clearPendingConnection()
        _state.value = _state.value.copy(
            scanning = false,
            connectingAddress = candidate.address,
            error = "Connecting ${candidate.label}: ${candidate.address}${if (candidate.autoConnect) " (background)" else ""}"
        )

        val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                context,
                candidate.autoConnect,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK
            )
        } else {
            device.connectGatt(context, candidate.autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
        gatt = newGatt

        val timeout = if (candidate.autoConnect) AUTO_CONNECT_TIMEOUT_MS else DIRECT_CONNECT_TIMEOUT_MS
        handler.postDelayed({
            if (gatt === newGatt && _state.value.connectedAddress == null) {
                gatt = null
                runCatching { newGatt.disconnect() }
                runCatching { newGatt.close() }
                _state.value = _state.value.copy(connectingAddress = null)
                tryNextCandidate("timeout on ${candidate.address}")
            }
        }, timeout)
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
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) {
                runCatching { g.close() }
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                attemptQueue.clear()
                _state.value = _state.value.copy(
                    scanning = false,
                    connectedName = g.device.name ?: "JARVIS Watch",
                    connectedAddress = g.device.address,
                    connectingAddress = null,
                    error = null
                )
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                g.discoverServices()
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                gatt = null
                runCatching { g.close() }
                _state.value = _state.value.copy(
                    connectedName = null,
                    connectedAddress = null,
                    connectingAddress = null,
                    heartRateBpm = null,
                    services = emptyList(),
                    error = if (status == BluetoothGatt.GATT_SUCCESS) {
                        "Watch disconnected"
                    } else {
                        "Watch GATT status $status; trying next connection method"
                    }
                )

                if (attemptQueue.isNotEmpty()) {
                    handler.postDelayed({ tryNextCandidate("GATT $status") }, 500L)
                } else {
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(error = "Connected, but service discovery failed: $status")
                return
            }

            _state.value = _state.value.copy(services = g.services.map { it.uuid.toString() }, error = null)
            val heartRate = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return
            g.setCharacteristicNotification(heartRate, true)
            heartRate.getDescriptor(CCCD)?.let { descriptor ->
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
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
