package com.jarvis.watchbridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthRepository(private val context: Context) {
    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )

    fun availabilityStatus(): Int = HealthConnectClient.getSdkStatus(context.applicationContext)

    fun isAvailable(): Boolean = availabilityStatus() == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean {
        if (!isAvailable()) return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun snapshot(): String {
        if (!isAvailable()) return "Health Connect is unavailable on this device"

        val end = Instant.now()
        val start = end.minus(24, ChronoUnit.HOURS)
        val range = TimeRangeFilter.between(start, end)

        val steps = client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records.sumOf { it.count }
        val hrs = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        val bpmSamples = hrs.flatMap { it.samples }.map { it.beatsPerMinute }
        val latestHr = bpmSamples.lastOrNull()
        val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
        val sleepMinutes = sleep.sumOf { ChronoUnit.MINUTES.between(it.startTime, it.endTime) }
        val spo2 = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records.lastOrNull()?.percentage?.value

        return buildString {
            append("Last 24h: steps=$steps")
            if (latestHr != null) append(", latest heart rate=$latestHr bpm")
            append(", sleep=$sleepMinutes minutes")
            if (spo2 != null) append(", latest SpO2=${"%.1f".format(spo2)}%")
        }
    }
}
