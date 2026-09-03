package com.jarvis.watchbridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BleManager(private val context: Context) {
    data class Device(val name: String, val address: String)
    data class State(
        val scanning: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val heartRateBpm: Int? = null,
        val services: List<String> = emptyList(),
        val error: String? = null
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var gatt: BluetoothGatt? = null

    companion object {
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
            val device = Device(name, result.device.address)
            val list = (_state.value.devices + device).distinctBy { it.address }.sortedBy { it.name.lowercase() }
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

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasConnectPermission()) {
            _state.value = _state.value.copy(error = "Bluetooth connect permission required")
            return
        }
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (hasConnectPermission()) gatt?.disconnect()
        gatt?.close(); gatt = null
        _state.value = _state.value.copy(connectedName = null, heartRateBpm = null, services = emptyList())
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = _state.value.copy(connectedName = g.device.name ?: g.device.address, error = null)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = _state.value.copy(connectedName = null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val names = g.services.map { it.uuid.toString() }
            _state.value = _state.value.copy(services = names)
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
                val bpm = if ((flags and 0x01) == 0) value.getOrNull(1)?.toInt()?.and(0xFF)
                else if (value.size >= 3) ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF) else null
                if (bpm != null) _state.value = _state.value.copy(heartRateBpm = bpm)
            }
        }
    }
}
