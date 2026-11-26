package com.murad.nexakeyboard

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Gemini client. No new Gradle dependencies: HttpURLConnection
 * for the network call and org.json (built into Android) for parsing.
 *
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/
 *           gemini-2.0-flash:generateContent
 * Auth: header "x-goog-api-key: <key>" — the key is only ever read from
 * SharedPreferences, never hardcoded and never logged.
 *
 * All calls run on a background thread. Callbacks are posted to the main
 * thread, so callers can safely insert text / show a Toast in them.
 * On any failure the callback receives null and the caller should fall
 * back to the original text.
 */
object GeminiClient {

    private const val URL_STRING =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun refineVoiceText(raw: String, apiKey: String, callback: (String?) -> Unit) {
        val prompt = "You are cleaning up a voice-typing transcript. " +
            "Fix spelling and punctuation, drop filler words like 'um', 'uh' and " +
            "repeated self-corrections, keep the meaning exactly, and return ONLY " +
            "the refined text, with no explanation and no quotes around it.\n\n" +
            "Transcript:\n$raw"
        generate(prompt, apiKey, callback)
    }

    fun fixGrammar(text: String, apiKey: String, callback: (String?) -> Unit) {
        val prompt = "Fix grammar, spelling and punctuation only, do not rewrite " +
            "meaning, return only corrected text.\n\nText:\n$text"
        generate(prompt, apiKey, callback)
    }

    fun changeTone(text: String, tone: String, apiKey: String, callback: (String?) -> Unit) {
        val prompt = "Rewrite the following text in a $tone tone, keeping the " +
            "meaning the same. Return only the rewritten text.\n\nText:\n$text"
        generate(prompt, apiKey, callback)
    }

    fun generate(prompt: String, apiKey: String, callback: (String?) -> Unit) {
        Thread {
            val result = try {
                post(prompt, apiKey)
            } catch (e: Exception) {
                null
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    private fun post(prompt: String, apiKey: String): String? {
        val url = URL(URL_STRING)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000

            val body = JSONObject()
                .put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
                .toString()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
            if (code !in 200..299) return null

            val root = JSONObject(response)
            val text = root
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
            return text?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }
}
