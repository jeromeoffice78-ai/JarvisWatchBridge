package com.jarvis.watchbridge.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

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

    fun setJarvisVolume(percent: Int = 100): Int {
        val safe = percent.coerceIn(10, 100)
        val musicMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val voiceMax = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val music = ((musicMax * safe) / 100.0).toInt().coerceIn(1, musicMax)
        val voice = ((voiceMax * safe) / 100.0).toInt().coerceIn(1, voiceMax)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, music, 0)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, voice, 0)
        return safe
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
