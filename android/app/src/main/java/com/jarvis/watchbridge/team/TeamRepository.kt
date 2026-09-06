package com.jarvis.watchbridge.team

import com.jarvis.watchbridge.auth.AuthStore

import com.jarvis.watchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TeamRepository {
    data class Specialist(
        val id: String,
        val name: String,
        val title: String,
        val skills: List<String>
    )

    data class AssignmentResult(
        val id: String,
        val specialistId: String,
        val status: String,
        val result: String
    )

    val specialists = listOf(
        Specialist("athena_cross", "Athena Cross", "Chief of Staff", listOf("Orchestration", "Delegation", "Project Management", "Executive Synthesis")),
        Specialist("marcus_vale", "Marcus Vale", "Principal Software Engineer", listOf("Android", "Kotlin", "APIs", "Databases", "CI/CD")),
        Specialist("nova_reed", "Nova Reed", "Cybersecurity Director", listOf("App Security", "Threat Modeling", "Privacy", "Hardening", "Incident Response")),
        Specialist("orion_blake", "Orion Blake", "Research Intelligence Director", listOf("Web Research", "Fact Verification", "Source Analysis", "Competitive Intelligence")),
        Specialist("victoria_kane", "Victoria Kane", "Business Strategy Director", listOf("Business Models", "Growth", "Operations", "Monetization", "Go-to-Market")),
        Specialist("elias_grant", "Elias Grant", "Financial Intelligence Director", listOf("Budgeting", "Forecasting", "Pricing", "Profitability", "Financial Modeling")),
        Specialist("sophia_mercer", "Sophia Mercer", "Legal Research Director", listOf("Legal Research", "Contracts", "Compliance", "Case Analysis", "Document Review")),
        Specialist("maya_sterling", "Maya Sterling", "Creative & Brand Director", listOf("Branding", "Advertising", "Copywriting", "Campaigns", "Visual Direction"))
    )

    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun chat(specialistId: String, message: String): String = post(
        "/team/chat",
        JSONObject().put("specialistId", specialistId).put("message", message)
    ).optString("reply").ifBlank { "The specialist returned no response." }

    suspend fun assign(specialistId: String, title: String, instructions: String): AssignmentResult {
        val root = post(
            "/team/assign",
            JSONObject()
                .put("specialistId", specialistId)
                .put("title", title.take(300))
                .put("instructions", instructions.take(12_000))
                .put("priority", 100)
        )
        val assignment = root.optJSONObject("assignment") ?: error("Assignment response missing")
        return AssignmentResult(
            id = assignment.optString("id"),
            specialistId = assignment.optString("assigned_agent", specialistId),
            status = assignment.optString("status", "unknown"),
            result = assignment.optString("result")
        )
    }

    suspend fun orchestrate(task: String, specialistIds: List<String>): String {
        val ids = JSONArray()
        specialistIds.distinct().take(4).forEach(ids::put)
        val root = post(
            "/team/orchestrate",
            JSONObject().put("task", task.take(12_000)).put("specialistIds", ids)
        )
        return root.optString("chiefOfStaff").ifBlank { "The team returned no coordinated result." }
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        val token = AuthStore.requireToken()
        require(base.startsWith("https://")) { "Secure JARVIS API URL required" }
        require(token.isNotBlank()) { "Chairman access token is not configured" }

        val request = Request.Builder()
            .url("$base$path")
            .addHeader("x-jarvis-admin-token", token)
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("JARVIS Team API ${response.code}: ${text.take(800)}")
            JSONObject(text)
        }
    }
}
