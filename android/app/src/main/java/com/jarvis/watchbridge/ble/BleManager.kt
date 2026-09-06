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
    data class Device(val name: String, val address: String, val jarvisTarget: Boolean = false)
    data class State(
        val scanning: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val connectedAddress: String? = null,
        val heartRateBpm: Int? = null,
        val services: List<String> = emptyList(),
        val characteristics: List<String> = emptyList(),
        val error: String? = null
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var gatt: BluetoothGatt? = null
    private var autoReconnect = false
    private var reconnectAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val TARGET_WATCH_NAME = "Watch"
        const val TARGET_WATCH_LE_ADDRESS = "41:42:69:41:49:D5"
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private fun hasScanPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    private fun hasConnectPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "BLE device"
            val address = result.device.address
            val target = address.equals(TARGET_WATCH_LE_ADDRESS, true) || name.equals(TARGET_WATCH_NAME, true)
            val device = Device(name, address, target)
            val list = (_state.value.devices + device)
                .distinctBy { it.address }
                .sortedWith(compareByDescending<Device> { it.jarvisTarget }.thenBy { it.name.lowercase() })
            _state.value = _state.value.copy(devices = list)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = _state.value.copy(scanning = false, error = "BLE scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) {
            _state.value = _state.value.copy(error = "Bluetooth scan permission required")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = _state.value.copy(error = "Bluetooth LE unavailable")
            return
        }
        _state.value = _state.value.copy(scanning = true, devices = emptyList(), error = null)
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (hasScanPermission()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _state.value = _state.value.copy(scanning = false)
    }

    fun connectTargetWatch() = connect(TARGET_WATCH_LE_ADDRESS)

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(error = "Bluetooth connect permission required")
            return
        }
        stopScan()
        val device = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        if (device == null) {
            _state.value = _state.value.copy(error = "Watch BLE address is unavailable")
            return
        }
        autoReconnect = true
        reconnectAddress = address
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoReconnect = false
        reconnectAddress = null
        handler.removeCallbacksAndMessages(null)
        if (hasConnectPermission()) gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = _state.value.copy(
            connectedName = null,
            connectedAddress = null,
            heartRateBpm = null,
            services = emptyList(),
            characteristics = emptyList()
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(
                    connectedName = g.device.name ?: TARGET_WATCH_NAME,
                    connectedAddress = g.device.address,
                    error = null
                )
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = _state.value.copy(connectedName = null, connectedAddress = null)
                if (autoReconnect) {
                    val address = reconnectAddress ?: return
                    handler.postDelayed({ if (autoReconnect) connect(address) }, 5_000)
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(error = "Watch connection error: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(error = "Watch service discovery failed: $status")
                return
            }
            val services = g.services.map { it.uuid.toString() }
            val characteristics = g.services.flatMap { service ->
                service.characteristics.map { characteristic ->
                    "${service.uuid}/${characteristic.uuid}/properties=${characteristic.properties}"
                }
            }
            _state.value = _state.value.copy(services = services, characteristics = characteristics)
            val hr = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT) ?: return
            g.setCharacteristicNotification(hr, true)
            hr.getDescriptor(CCCD)?.let { d ->
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION") g.writeDescriptor(d)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT && value.isNotEmpty()) {
                val flags = value[0].toInt()
                val bpm = if ((flags and 0x01) == 0) {
                    value.getOrNull(1)?.toInt()?.and(0xFF)
                } else if (value.size >= 3) {
                    ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
                } else null
                if (bpm != null) _state.value = _state.value.copy(heartRateBpm = bpm)
            }
        }
    }
}
