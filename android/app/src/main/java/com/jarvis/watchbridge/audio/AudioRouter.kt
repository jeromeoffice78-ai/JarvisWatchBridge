package com.jarvis.watchbridge.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Keeps the phone/tablet as the default audio path and allows a compatible
 * Bluetooth headset/watch audio endpoint to be selected when Android exposes it.
 */
class AudioRouter(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    data class Route(val id: Int, val name: String, val type: Int)

    fun availableRoutes(): List<Route> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return listOf(Route(-1, "Device audio", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        }
        return audio.availableCommunicationDevices.map { device ->
            Route(device.id, device.productName?.toString() ?: typeName(device.type), device.type)
        }
    }

    fun useDeviceAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            audio.mode = AudioManager.MODE_NORMAL
            audio.isSpeakerphoneOn = true
            return true
        }
        val speaker = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return audio.setCommunicationDevice(speaker)
    }

    fun useRoute(id: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val route = audio.availableCommunicationDevices.firstOrNull { it.id == id } ?: return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        return audio.setCommunicationDevice(route)
    }

    fun clearRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        }
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Device speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Device earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        else -> "Audio device"
    }
}
