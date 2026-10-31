package com.example.network

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class GeminiClient {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val apiService: GeminiApiService = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiApiService::class.java)

    // Current models list in order of fallback
    private val candidateModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite"
    )

    suspend fun generateReply(
        conversationHistory: List<Pair<String, Boolean>>, // content to isUser
        currentPrompt: String,
        systemInstructionText: String
    ): Result<String> {
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return Result.failure(
                IllegalStateException("کلید ارتباط با هوش مصنوعی (API Key) یافت نشد یا معتبر نیست. لطفاً در پنل Secrets مقدار آن را تنظیم فرمایید.")
            )
        }

        // Prepare contents array
        val contents = mutableListOf<Content>()

        // Add history (limiting to last 14 messages to keep context focused)
        val trimmedHistory = conversationHistory.takeLast(14)
        for ((text, isUser) in trimmedHistory) {
            val role = if (isUser) "user" else "model"
            contents.add(
                Content(
                    role = role,
                    parts = listOf(Part(text = text))
                )
            )
        }

        // Add the current user prompt
        contents.add(
            Content(
                role = "user",
                parts = listOf(Part(text = currentPrompt))
            )
        )

        val systemInstruction = if (systemInstructionText.isNotBlank()) {
            SystemInstruction(parts = listOf(Part(text = systemInstructionText)))
        } else null

        val failedAttempts = mutableListOf<String>()
        var lastErrorMessage = "خطا در برقراری ارتباط با مدل هوش مصنوعی."

        // Attempt with available models in order of capability
        for (model in candidateModels) {
            try {
                // Ensure thinkingConfig is sent ONLY to models that support it (2.5+ / 3.x),
                // so models that do not support it do not receive an unsupported parameter
                val supportsThinking = model.startsWith("gemini-2.5") || model.startsWith("gemini-3")
                val thinkingConfig = if (supportsThinking) ThinkingConfig(thinkingBudget = 1024) else null

                val request = GeminiRequest(
                    contents = contents,
                    systemInstruction = systemInstruction,
                    generationConfig = GenerationConfig(
                        temperature = 0.7,
                        maxOutputTokens = 8192,
                        thinkingConfig = thinkingConfig
                    )
                )

                val response = apiService.generateContent(
                    model = model,
                    apiKey = apiKey,
                    request = request
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val candidate = body?.candidates?.firstOrNull()
                    val allParts = candidate?.content?.parts.orEmpty()

                    // Join the text of ALL parts of the reply, not only the first part
                    val nonThoughtParts = allParts.filter { it.thought != true }.mapNotNull { it.text }
                    val replyText = if (nonThoughtParts.isNotEmpty()) {
                        nonThoughtParts.joinToString("").trim()
                    } else {
                        allParts.mapNotNull { it.text }.joinToString("").trim()
                    }

                    // If the model stops because of MAX_TOKENS but some text exists, show that text and keep it
                    if (replyText.isNotBlank()) {
                        return Result.success(replyText)
                    } else {
                        // If reply is truly empty, show a specific Persian error message
                        val finishReason = candidate?.finishReason
                        val specificErrorMessage = when (finishReason) {
                            "MAX_TOKENS" -> "پاسخ بیش از حد طولانی شد و پیش از تولید متن، به سقف مجاز رسید. لطفاً پرسش را کوتاه‌تر بفرمایید."
                            "SAFETY" -> "پاسخ به دلیل خط‌مشی‌های ایمنی هوش مصنوعی مسدود گردید و متنی برای نمایش تولید نشد."
                            "RECITATION" -> "پاسخ به دلیل قوانین کپی‌رایت یا بازخوانی دقیق مسدود گردید."
                            "BLOCKLIST" -> "محتوای درخواست یا پاسخ به دلیل محدودیت‌های واژگانی مسدود شد."
                            "PROHIBITED_CONTENT" -> "محتوای پاسخ غیرمجاز تشخیص داده شد."
                            "SPII" -> "پاسخ به دلیل حفظ حریم خصوصی و اطلاعات حساس مسدود گردید."
                            else -> "مدل هوش مصنوعی هیچ پاسخی تولید نکرد (پاسخ دریافتی خالی بود)."
                        }
                        return Result.failure(IllegalStateException(specificErrorMessage))
                    }
                } else {
                    val code = response.code()
                    val errorBody = response.errorBody()?.string().orEmpty()
                    Log.w("GeminiClient", "Model $model returned HTTP $code: $errorBody")

                    val desc = when (code) {
                        404 -> "مدل پیدا نشد"
                        429 -> "محدودیت تعداد درخواست"
                        503 -> "شلوغی سرور"
                        504 -> "پایان زمان سرور"
                        else -> "پاسخ ناموفق"
                    }
                    failedAttempts.add("• $model — کد $code ($desc)")

                    when (code) {
                        429 -> {
                            val previousInfo = if (failedAttempts.size > 1) {
                                "\n\nتلاش‌های قبلی:\n" + failedAttempts.dropLast(1).joinToString("\n")
                            } else ""
                            return Result.failure(
                                IllegalStateException("محدودیت تعداد درخواست‌های هوش مصنوعی برای این لحظه تکمیل شده است (مدل $model — کد 429: $desc). لطفاً کمی صبر فرمایید.$previousInfo")
                            )
                        }
                        400, 403 -> {
                            val previousInfo = if (failedAttempts.size > 1) {
                                "\n\nتلاش‌های قبلی:\n" + failedAttempts.dropLast(1).joinToString("\n")
                            } else ""
                            return Result.failure(
                                IllegalStateException("دسترسی به سرویس هوش مصنوعی امکان‌پذیر نیست یا کلید ارائه‌شده معتبر نمی‌باشد (مدل $model — کد $code: $desc).$previousInfo")
                            )
                        }
                        404 -> {
                            // Model not found in this region/key, continue to next fallback model
                            continue
                        }
                        else -> {
                            continue
                        }
                    }
                }
            } catch (e: UnknownHostException) {
                Log.e("GeminiClient", "Unknown host", e)
                return Result.failure(
                    IOException("عدم دسترسی به اینترنت. لطفاً اتصال شبکه خود را بررسی کرده و مجدداً تلاش کنید.")
                )
            } catch (e: SocketTimeoutException) {
                Log.e("GeminiClient", "Timeout", e)
                return Result.failure(
                    IOException("زمان پاسخگویی سرور به پایان رسید. لطفاً مجدداً امتحان نمایید.")
                )
            } catch (e: IOException) {
                Log.e("GeminiClient", "IO error", e)
                return Result.failure(
                    IOException("خطا در انتقال داده‌های شبکه. لطفاً اتصال اینترنت را بررسی فرمایید.")
                )
            } catch (e: Exception) {
                Log.e("GeminiClient", "Unexpected error", e)
                lastErrorMessage = e.localizedMessage ?: "خطای ناشناخته رخ داد."
            }
        }

        if (failedAttempts.isNotEmpty()) {
            val failureSummary = "هیچ‌کدام از مدل‌ها پاسخ ندادند:\n" + failedAttempts.joinToString("\n")
            return Result.failure(IllegalStateException(failureSummary))
        }

        return Result.failure(IllegalStateException(lastErrorMessage))
    }
}
