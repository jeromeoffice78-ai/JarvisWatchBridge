package com.jarvis.watchbridge.ble

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        val characteristics: List<String> = emptyList(),
        val error: String? = null
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var autoReconnect = false
    private var reconnectAddress: String? = null

    companion object {
        const val TARGET_WATCH_NAME = "Watch"
        const val TARGET_WATCH_LE_ADDRESS = "41:42:69:41:49:D5"
        private val TARGET_WATCH_NAMES = listOf("WATCH", "V19", "LAXASFIT")
        private const val DIRECT_CONNECT_FALLBACK_MS = 4_000L
        private const val RECONNECT_DELAY_MS = 3_000L

        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private fun hasScanPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bluetoothReady(): Boolean {
        val bluetoothAdapter = adapter ?: run {
            _state.value = _state.value.copy(error = "Bluetooth is unavailable on this device")
            return false
        }
        val enabled = runCatching { bluetoothAdapter.isEnabled }.getOrElse {
            _state.value = _state.value.copy(error = "Nearby devices permission is required")
            return false
        }
        if (!enabled) {
            _state.value = _state.value.copy(error = "Turn Bluetooth on")
            return false
        }
        return true
    }

    private fun isJarvisTarget(name: String?, address: String): Boolean {
        if (address.equals(TARGET_WATCH_LE_ADDRESS, ignoreCase = true)) return true
        val normalizedName = name.orEmpty().trim().uppercase()
        return TARGET_WATCH_NAMES.any { target ->
            normalizedName == target || normalizedName.contains(target)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "BLE device"
            val address = result.device.address
            val target = isJarvisTarget(name, address)
            val device = Device(name, address, target)
            val list = (_state.value.devices + device)
                .distinctBy { it.address }
                .sortedWith(compareByDescending<Device> { it.jarvisTarget }.thenBy { it.name.lowercase() })
            _state.value = _state.value.copy(devices = list)

            if (
                target &&
                _state.value.connectedAddress == null &&
                _state.value.connectingAddress == null
            ) {
                connect(address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = _state.value.copy(
                scanning = false,
                error = "BLE scan failed: $errorCode"
            )
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!bluetoothReady()) return
        if (!hasScanPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices scan permission required")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(error = "Bluetooth LE scanner unavailable")
            return
        }

        autoReconnect = true
        runCatching { scanner.stopScan(scanCallback) }
        _state.value = _state.value.copy(
            scanning = true,
            devices = emptyList(),
            error = null
        )
        scanner.startScan(
            null,
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build(),
            scanCallback
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (hasScanPermission()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        _state.value = _state.value.copy(scanning = false)
    }

    @SuppressLint("MissingPermission")
    fun connectTargetWatch() {
        autoReconnect = true
        reconnectAddress = TARGET_WATCH_LE_ADDRESS
        if (!bluetoothReady()) return
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices connect permission required")
            return
        }

        connect(TARGET_WATCH_LE_ADDRESS)

        // Some watches rotate/private-randomize their BLE address. If the known V19 address does
        // not connect promptly, abandon that attempt and scan by the known watch names as fallback.
        handler.postDelayed({
            if (autoReconnect && _state.value.connectedAddress == null) {
                runCatching { gatt?.close() }
                gatt = null
                _state.value = _state.value.copy(connectingAddress = null)
                startScan()
            }
        }, DIRECT_CONNECT_FALLBACK_MS)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!bluetoothReady()) return
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices connect permission required")
            return
        }

        stopScan()
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            _state.value = _state.value.copy(error = "Watch BLE address is unavailable")
            scheduleReconnect()
            return
        }

        autoReconnect = true
        reconnectAddress = address
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        _state.value = _state.value.copy(
            connectingAddress = address,
            error = null
        )
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoReconnect = false
        reconnectAddress = null
        handler.removeCallbacksAndMessages(null)
        stopScan()
        if (hasConnectPermission()) runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _state.value = _state.value.copy(
            connectedName = null,
            connectedAddress = null,
            connectingAddress = null,
            heartRateBpm = null,
            services = emptyList(),
            characteristics = emptyList(),
            error = null
        )
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!autoReconnect || _state.value.connectedAddress != null) return@postDelayed
            val address = reconnectAddress
            if (address.isNullOrBlank()) {
                startScan()
            } else {
                connect(address)
                handler.postDelayed({
                    if (autoReconnect && _state.value.connectedAddress == null) {
                        runCatching { gatt?.close() }
                        gatt = null
                        _state.value = _state.value.copy(connectingAddress = null)
                        startScan()
                    }
                }, DIRECT_CONNECT_FALLBACK_MS)
            }
        }, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    reconnectAddress = g.device.address
                    handler.removeCallbacksAndMessages(null)
                    _state.value = _state.value.copy(
                        connectedName = g.device.name ?: TARGET_WATCH_NAME,
                        connectedAddress = g.device.address,
                        connectingAddress = null,
                        error = null
                    )
                    g.discoverServices()
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    _state.value = _state.value.copy(
                        connectedName = null,
                        connectedAddress = null,
                        connectingAddress = null,
                        heartRateBpm = null,
                        services = emptyList(),
                        characteristics = emptyList(),
                        error = if (status == BluetoothGatt.GATT_SUCCESS) {
                            null
                        } else {
                            "Watch disconnected (GATT $status); reconnecting"
                        }
                    )
                    scheduleReconnect()
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    _state.value = _state.value.copy(
                        connectedName = null,
                        connectedAddress = null,
                        connectingAddress = null,
                        error = "Watch connection failed (GATT $status); retrying"
                    )
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(error = "Watch service discovery failed: $status")
                return
            }

            val services = g.services.map { service -> service.uuid.toString() }
            val characteristics = g.services.flatMap { service ->
                service.characteristics.map { characteristic ->
                    "${service.uuid}/${characteristic.uuid}/properties=${characteristic.properties}"
                }
            }
            _state.value = _state.value.copy(
                services = services,
                characteristics = characteristics,
                error = null
            )

            val heartRate = g.getService(HEART_RATE_SERVICE)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
                ?: return
            g.setCharacteristicNotification(heartRate, true)
            heartRate.getDescriptor(CCCD)?.let { descriptor ->
                if (android.os.Build.VERSION.SDK_INT >= 33) {
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
            consumeCharacteristic(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            consumeCharacteristic(characteristic, characteristic.value ?: return)
        }
    }

    private fun consumeCharacteristic(
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
        if (bpm != null) {
            _state.value = _state.value.copy(heartRateBpm = bpm)
        }
    }
}
