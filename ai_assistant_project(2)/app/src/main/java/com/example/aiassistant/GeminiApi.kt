package com.example.aiassistant

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object GeminiApi {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val MODEL = "gemini-2.0-flash"

    /** history: список пар (роль: "user"/"model", текст) */
    fun ask(apiKey: String, history: List<Pair<String, String>>, screenContext: String,
            callback: (String) -> Unit) {
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(JSONObject()
                .put("role", if (role == "model") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }

        val body = JSONObject()
            .put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text",
                    """Ты — ИИ-помощник в телефоне Android.
Контекст экрана пользователя (видимый текст):
${screenContext.ifBlank { "(недоступен)" }}

Правила:
- Отвечай кратко, на языке пользователя.
- Можешь управлять телефоном, добавив В КОНЦЕ ответа одну строку-команду:
  [CLICK: текст элемента] — нажать на элемент
  [SWIPE: up|down|left|right] — свайп по экрану
  [TYPE: текст] — ввести текст в поле ввода
После команды не пиши ничего лишнего."""
                )
            )))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                callback("Ошибка сети: ${e.message}")

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback("Ошибка API ${it.code}: ${it.body?.string()?.take(300)}")
                        return
                    }
                    try {
                        val text = JSONObject(it.body!!.string())
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        callback(text.trim())
                    } catch (e: Exception) {
                        callback("Ошибка разбора ответа: ${e.message}")
                    }
                }
            }
        })
    }
}