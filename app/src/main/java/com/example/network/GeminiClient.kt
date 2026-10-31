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

    private val candidateModels = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash"
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

        val request = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            generationConfig = GenerationConfig(
                temperature = 0.7,
                maxOutputTokens = 2048
            )
        )

        var lastErrorMessage = "خطا در برقراری ارتباط با مدل هوش مصنوعی."

        // Attempt with available models in order of capability
        for (model in candidateModels) {
            try {
                val response = apiService.generateContent(
                    model = model,
                    apiKey = apiKey,
                    request = request
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val candidate = body?.candidates?.firstOrNull()
                    val replyText = candidate?.content?.parts?.firstOrNull()?.text

                    if (!replyText.isNullOrBlank()) {
                        return Result.success(replyText.trim())
                    } else {
                        val finishReason = candidate?.finishReason
                        if (finishReason == "SAFETY") {
                            return Result.failure(
                                IllegalStateException("متأسفانه این پرسش با خط‌مشی‌های ایمنی مطابقت نداشت و پاسخی برای آن تولید نشد.")
                            )
                        }
                    }
                } else {
                    val code = response.code()
                    val errorBody = response.errorBody()?.string().orEmpty()
                    Log.w("GeminiClient", "Model $model returned HTTP $code: $errorBody")

                    when (code) {
                        429 -> {
                            return Result.failure(
                                IllegalStateException("محدودیت تعداد درخواست‌های هوش مصنوعی برای این لحظه تکمیل شده است. لطفاً کمی صبر فرمایید.")
                            )
                        }
                        404 -> {
                            // Model not found in this region/key, continue to next fallback model
                            lastErrorMessage = "مدل $model در دسترس نبود."
                            continue
                        }
                        400, 403 -> {
                            return Result.failure(
                                IllegalStateException("دسترسی به سرویس هوش مصنوعی امکان‌پذیر نیست یا کلید ارائه‌شده معتبر نمی‌باشد (کد خطا: $code).")
                            )
                        }
                        else -> {
                            lastErrorMessage = "پاسخ ناموفق از سرور با کد $code دریافت شد."
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

        return Result.failure(IllegalStateException(lastErrorMessage))
    }
}
