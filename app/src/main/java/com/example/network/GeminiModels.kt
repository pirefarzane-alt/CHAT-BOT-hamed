package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @field:Json(name = "contents") val contents: List<Content>,
    @field:Json(name = "system_instruction") val systemInstruction: SystemInstruction? = null,
    @field:Json(name = "generationConfig") val generationConfig: GenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class SystemInstruction(
    @field:Json(name = "parts") val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Content(
    @field:Json(name = "role") val role: String,
    @field:Json(name = "parts") val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    @field:Json(name = "text") val text: String? = null,
    @field:Json(name = "thought") val thought: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    @field:Json(name = "thinkingBudget") val thinkingBudget: Int = 1024
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @field:Json(name = "temperature") val temperature: Double = 0.7,
    @field:Json(name = "maxOutputTokens") val maxOutputTokens: Int = 8192,
    @field:Json(name = "thinkingConfig") val thinkingConfig: ThinkingConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @field:Json(name = "candidates") val candidates: List<Candidate>? = null,
    @field:Json(name = "error") val error: GeminiApiError? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @field:Json(name = "content") val content: CandidateContent? = null,
    @field:Json(name = "finishReason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class CandidateContent(
    @field:Json(name = "parts") val parts: List<Part>? = null,
    @field:Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiApiError(
    @field:Json(name = "code") val code: Int? = null,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "status") val status: String? = null
)
