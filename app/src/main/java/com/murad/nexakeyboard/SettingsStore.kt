package com.murad.nexakeyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * All NexaKeyboard v2 settings, stored in SharedPreferences "nexa_prefs".
 * No API key or user text is ever hardcoded here.
 *
 * Keys:
 *  gemini_api_key      String, empty = not set
 *  toolbar_order       String, comma list, e.g. "paste,undo,redo"
 *  autocorrect_bangla  Boolean, default false
 *  autocorrect_english Boolean, default true
 *  dark_theme          Boolean, default true
 *  key_colour          String: "grey" | "dark" | "blue", default "grey"
 *  key_shape           String: "rounded" | "square", default "rounded"
 *  keyboard_height     String: "short" | "medium" | "tall", default "medium"
 *  tone                String: "Professional" | "Casual" | "Formal" | "Friendly"
 *  quick_phrases       String, JSON array of strings
 *  clipboard_history   String, JSON array of strings (last 10)
 */
object SettingsStore {

    const val PREFS = "nexa_prefs"

    val DEFAULT_TOOLBAR_ORDER = listOf("paste", "undo", "redo")
    val DEFAULT_PHRASES = listOf("ছয়গাঁও ফার্মেসি", "ধন্যবাদ", "আসসালামু আলাইকুম")
    val TONES = listOf("Professional", "Casual", "Formal", "Friendly")
    val KEY_COLOURS = listOf("grey", "dark", "blue")
    val KEY_SHAPES = listOf("rounded", "square")
    val KEYBOARD_HEIGHTS = listOf("short", "medium", "tall")

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun geminiKey(context: Context): String =
        prefs(context).getString("gemini_api_key", "") ?: ""

    fun hasGeminiKey(context: Context): Boolean = geminiKey(context).isNotBlank()

    fun saveGeminiKey(context: Context, key: String) {
        prefs(context).edit().putString("gemini_api_key", key.trim()).apply()
    }

    fun clearGeminiKey(context: Context) {
        prefs(context).edit().remove("gemini_api_key").apply()
    }

    fun toolbarOrder(context: Context): List<String> {
        val raw = prefs(context).getString("toolbar_order", null) ?: return DEFAULT_TOOLBAR_ORDER
        val list = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return DEFAULT_TOOLBAR_ORDER
        // Keep the three known items, in saved order, then any missing one at the end.
        val known = DEFAULT_TOOLBAR_ORDER
        val ordered = list.filter { it in known } + known.filter { it !in list }
        return ordered
    }

    fun saveToolbarOrder(context: Context, order: List<String>) {
        prefs(context).edit().putString("toolbar_order", order.joinToString(",")).apply()
    }

    fun autocorrectBangla(context: Context): Boolean =
        prefs(context).getBoolean("autocorrect_bangla", false)

    fun autocorrectEnglish(context: Context): Boolean =
        prefs(context).getBoolean("autocorrect_english", true)

    fun isDarkTheme(context: Context): Boolean =
        prefs(context).getBoolean("dark_theme", true)

    fun keyColour(context: Context): String =
        prefs(context).getString("key_colour", "grey") ?: "grey"

    fun keyShape(context: Context): String =
        prefs(context).getString("key_shape", "rounded") ?: "rounded"

    fun keyboardHeight(context: Context): String =
        prefs(context).getString("keyboard_height", "medium") ?: "medium"

    fun tone(context: Context): String =
        prefs(context).getString("tone", "Professional") ?: "Professional"

    fun quickPhrases(context: Context): List<String> {
        val raw = prefs(context).getString("quick_phrases", null) ?: return DEFAULT_PHRASES
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_PHRASES }
        } catch (e: Exception) {
            DEFAULT_PHRASES
        }
    }

    fun saveQuickPhrases(context: Context, phrases: List<String>) {
        val arr = JSONArray()
        phrases.forEach { arr.put(it) }
        prefs(context).edit().putString("quick_phrases", arr.toString()).apply()
    }

    fun clipboardHistory(context: Context): List<String> {
        val raw = prefs(context).getString("clipboard_history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Add an item to the front of the clipboard history, keep last 10, de-duplicated. */
    fun addClipboardItem(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val list = (listOf(clean) + clipboardHistory(context).filter { it != clean }).take(10)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(context).edit().putString("clipboard_history", arr.toString()).apply()
    }
}
