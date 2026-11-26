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
 *
 * v2.3 — errors are no longer swallowed: every call returns a
 * GeminiResult (Ok with the text, or Fail with a plain-language
 * reason: "Invalid API key", "No internet", "Quota exceeded", ...).
 * The old (String?) callbacks are kept for existing callers and
 * simply receive null on failure.
 */
object GeminiClient {

    private const val URL_STRING =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Result of a Gemini call: the text, or a plain-language reason. */
    sealed class GeminiResult {
        data class Ok(val text: String) : GeminiResult()
        data class Fail(val reason: String) : GeminiResult()
    }

    /** Real connectivity test: asks Gemini to "Say OK". */
    fun testKey(apiKey: String, callback: (GeminiResult) -> Unit) {
        generateDetailed("Say OK", apiKey, callback)
    }

    fun refineVoiceText(raw: String, apiKey: String, callback: (String?) -> Unit) {
        val prompt = "You are cleaning up a voice-typing transcript. " +
            "Fix spelling and punctuation, drop filler words like 'um', 'uh' and " +
            "repeated self-corrections, keep the meaning exactly, and return ONLY " +
            "the refined text, with no explanation and no quotes around it.\n\n" +
            "Transcript:\n$raw"
        generate(prompt, apiKey, callback)
    }

    /** Same as refineVoiceText, but the callback also gets the failure reason. */
    fun refineVoiceTextDetailed(
        raw: String,
        apiKey: String,
        callback: (GeminiResult) -> Unit
    ) {
        val prompt = "You are cleaning up a voice-typing transcript. " +
            "Fix spelling and punctuation, drop filler words like 'um', 'uh' and " +
            "repeated self-corrections, keep the meaning exactly, and return ONLY " +
            "the refined text, with no explanation and no quotes around it.\n\n" +
            "Transcript:\n$raw"
        generateDetailed(prompt, apiKey, callback)
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
        generateDetailed(prompt, apiKey) { result ->
            callback(if (result is GeminiResult.Ok) result.text else null)
        }
    }

    /** Full-result version: the callback always gets Ok or Fail. */
    fun generateDetailed(prompt: String, apiKey: String, callback: (GeminiResult) -> Unit) {
        Thread {
            val result = try {
                post(prompt, apiKey)
            } catch (e: java.net.UnknownHostException) {
                GeminiResult.Fail("No internet — check connection")
            } catch (e: java.net.SocketTimeoutException) {
                GeminiResult.Fail("No internet — connection timed out")
            } catch (e: java.io.IOException) {
                GeminiResult.Fail("No internet — ${e.message ?: "network error"}")
            } catch (e: Exception) {
                GeminiResult.Fail("Gemini error: ${e.message ?: "unknown error"}")
            }
            mainHandler.post { callback(result) }
        }.start()
    }

    /** Turn an HTTP error into a plain-language reason. */
    private fun httpError(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }
        val detail = if (msg.isNotEmpty()) " — ${msg.take(100)}" else ""
        return when (code) {
            400, 401, 403 -> "Invalid API key$detail"
            429 -> "Quota exceeded — try again later"
            else -> "Gemini error ($code)$detail"
        }
    }

    private fun post(prompt: String, apiKey: String): GeminiResult {
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
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return GeminiResult.Fail("Gemini error ($code): empty response")
            if (code !in 200..299) return GeminiResult.Fail(httpError(code, response))

            val root = JSONObject(response)
            val text = root
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
            val clean = text?.trim()?.takeIf { it.isNotEmpty() }
            return if (clean != null) GeminiResult.Ok(clean)
            else GeminiResult.Fail("Gemini error: empty reply")
        } finally {
            conn.disconnect()
        }
    }
}
