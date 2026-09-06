package com.jarvis.watchbridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
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
        val error: String? = null
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var shouldReconnect = true

    companion object {
        const val TARGET_WATCH_LE_ADDRESS = "41:42:69:41:49:D5"
        private val TARGET_WATCH_NAMES = listOf("WATCH", "V19", "LAXASFIT")
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

    private fun bluetoothReady(): Boolean {
        val a = adapter ?: run {
            _state.value = _state.value.copy(error = "Bluetooth is unavailable on this device")
            return false
        }
        if (!a.isEnabled) {
            _state.value = _state.value.copy(error = "Turn Bluetooth on")
            return false
        }
        return true
    }

    private fun isJarvisTarget(name: String?, address: String): Boolean {
        if (address.equals(TARGET_WATCH_LE_ADDRESS, ignoreCase = true)) return true
        val normalized = name.orEmpty().uppercase()
        return TARGET_WATCH_NAMES.any { normalized == it || normalized.contains(it) }
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

            if (target && _state.value.connectedAddress == null && _state.value.connectingAddress == null) {
                connect(address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = _state.value.copy(
                scanning = false,
                error = "BLE scan failed: $errorCode"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!bluetoothReady()) return
        if (!hasScanPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices permission required")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(error = "Bluetooth LE scanner unavailable")
            return
        }
        shouldReconnect = true
        _state.value = _state.value.copy(
            scanning = true,
            devices = emptyList(),
            error = null
        )
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
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
        shouldReconnect = true
        if (!bluetoothReady()) return
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices permission required")
            return
        }

        // Try the known V19/Laxasfit address immediately. If the watch is using a
        // randomized/private address, the scan fallback below will still find it by name.
        runCatching {
            connect(TARGET_WATCH_LE_ADDRESS)
        }.onFailure {
            _state.value = _state.value.copy(error = "Direct watch connection failed; scanning nearby")
            startScan()
        }

        handler.postDelayed({
            if (_state.value.connectedAddress == null && _state.value.connectingAddress == null) {
                startScan()
            }
        }, 4_000L)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!bluetoothReady()) return
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(error = "Nearby devices permission required")
            return
        }

        shouldReconnect = true
        stopScan()
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(error = "Invalid Bluetooth address: $address")
            null
        } ?: return

        gatt?.close()
        gatt = null
        _state.value = _state.value.copy(
            connectingAddress = address,
            error = null
        )
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        shouldReconnect = false
        stopScan()
        if (hasConnectPermission()) runCatching { gatt?.disconnect() }
        gatt?.close()
        gatt = null
        _state.value = _state.value.copy(
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
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (_state.value.connectedAddress == null) connectTargetWatch()
        }, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    _state.value = _state.value.copy(
                        connectedName = g.device.name ?: "JARVIS Watch",
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
                        error = if (status == BluetoothGatt.GATT_SUCCESS) null else "Watch disconnected (GATT $status); reconnecting"
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
                _state.value = _state.value.copy(error = "Connected, but service discovery failed: $status")
                return
            }

            val names = g.services.map { it.uuid.toString() }
            _state.value = _state.value.copy(services = names, error = null)

            val hr = g.getService(HEART_RATE_SERVICE)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT)
                ?: return

            g.setCharacteristicNotification(hr, true)
            hr.getDescriptor(CCCD)?.let { d ->
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT && value.isNotEmpty()) {
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
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }
    }
}
