package com.murad.nexakeyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * NexaKeyboard Settings (v2).
 *
 * Sections: enable / pick keyboard, Gemini API key (save / update / clear /
 * test — the test only checks the key is non-empty, it makes no network
 * call), Tone chooser, per-language autocorrect, theme editor, toolbar
 * order (Paste / Undo / Redo, Up/Down), Quick Phrases (add/delete),
 * clipboard history (clear).
 *
 * The full API key is never displayed — only "saved" / "not set".
 */
class MainActivity : AppCompatActivity() {

    private val toolbarLabels = mapOf(
        "paste" to "Paste",
        "undo" to "Undo",
        "redo" to "Redo"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---------- 1. Enable / pick ----------
        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_pick).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // ---------- 2. Gemini API key ----------
        val editKey = findViewById<EditText>(R.id.edit_api_key)
        val status = findViewById<TextView>(R.id.text_key_status)
        fun refreshKeyStatus() {
            status.text = if (SettingsStore.hasGeminiKey(this)) {
                "API key: saved"
            } else {
                "API key: not set"
            }
        }
        refreshKeyStatus()

        findViewById<Button>(R.id.btn_save_key).setOnClickListener {
            val key = editKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "API key খালি — আগে কী লিখুন", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SettingsStore.saveGeminiKey(this, key)
            editKey.text.clear()
            refreshKeyStatus()
            Toast.makeText(this, "Gemini API key saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_clear_key).setOnClickListener {
            SettingsStore.clearGeminiKey(this)
            editKey.text.clear()
            refreshKeyStatus()
            Toast.makeText(this, "Gemini API key cleared", Toast.LENGTH_SHORT).show()
        }
        // Test only checks a key is saved locally. No network call.
        findViewById<Button>(R.id.btn_test_key).setOnClickListener {
            if (SettingsStore.hasGeminiKey(this)) {
                Toast.makeText(
                    this,
                    "Key saved — will be used for AI refine",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "No API key saved yet", Toast.LENGTH_SHORT).show()
            }
        }

        // ---------- 3. Tone ----------
        val toneGroup = findViewById<RadioGroup>(R.id.radio_tone)
        val toneToId = mapOf(
            "Professional" to R.id.radio_tone_professional,
            "Casual" to R.id.radio_tone_casual,
            "Formal" to R.id.radio_tone_formal,
            "Friendly" to R.id.radio_tone_friendly
        )
        toneGroup.check(toneToId[SettingsStore.tone(this)] ?: R.id.radio_tone_professional)
        toneGroup.setOnCheckedChangeListener { _, checkedId ->
            val tone = toneToId.entries.firstOrNull { it.value == checkedId }?.key ?: "Professional"
            SettingsStore.prefs(this).edit().putString("tone", tone).apply()
        }

        // ---------- 4. Autocorrect per language ----------
        val swBn = findViewById<Switch>(R.id.switch_autocorrect_bangla)
        val swEn = findViewById<Switch>(R.id.switch_autocorrect_english)
        swBn.isChecked = SettingsStore.autocorrectBangla(this)
        swEn.isChecked = SettingsStore.autocorrectEnglish(this)
        swBn.setOnCheckedChangeListener { _, checked ->
            SettingsStore.prefs(this).edit().putBoolean("autocorrect_bangla", checked).apply()
        }
        swEn.setOnCheckedChangeListener { _, checked ->
            SettingsStore.prefs(this).edit().putBoolean("autocorrect_english", checked).apply()
        }

        // ---------- 5. Theme editor ----------
        val swTheme = findViewById<Switch>(R.id.switch_theme)
        swTheme.isChecked = SettingsStore.isDarkTheme(this)
        swTheme.setOnCheckedChangeListener { _, checked ->
            SettingsStore.prefs(this).edit().putBoolean("dark_theme", checked).apply()
        }

        val colourGroup = findViewById<RadioGroup>(R.id.radio_key_colour)
        val colourToId = mapOf(
            "grey" to R.id.radio_colour_grey,
            "dark" to R.id.radio_colour_dark,
            "blue" to R.id.radio_colour_blue
        )
        colourGroup.check(colourToId[SettingsStore.keyColour(this)] ?: R.id.radio_colour_grey)
        colourGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = colourToId.entries.firstOrNull { it.value == checkedId }?.key ?: "grey"
            SettingsStore.prefs(this).edit().putString("key_colour", v).apply()
        }

        val shapeGroup = findViewById<RadioGroup>(R.id.radio_key_shape)
        val shapeToId = mapOf(
            "rounded" to R.id.radio_shape_rounded,
            "square" to R.id.radio_shape_square
        )
        shapeGroup.check(shapeToId[SettingsStore.keyShape(this)] ?: R.id.radio_shape_rounded)
        shapeGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = shapeToId.entries.firstOrNull { it.value == checkedId }?.key ?: "rounded"
            SettingsStore.prefs(this).edit().putString("key_shape", v).apply()
        }

        val heightGroup = findViewById<RadioGroup>(R.id.radio_keyboard_height)
        val heightToId = mapOf(
            "short" to R.id.radio_height_short,
            "medium" to R.id.radio_height_medium,
            "tall" to R.id.radio_height_tall
        )
        heightGroup.check(heightToId[SettingsStore.keyboardHeight(this)] ?: R.id.radio_height_medium)
        heightGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = heightToId.entries.firstOrNull { it.value == checkedId }?.key ?: "medium"
            SettingsStore.prefs(this).edit().putString("keyboard_height", v).apply()
        }

        // ---------- 6. Toolbar order ----------
        refreshToolbarOrder()

        // ---------- 7. Quick phrases ----------
        refreshPhrases()
        val editPhrase = findViewById<EditText>(R.id.edit_new_phrase)
        findViewById<Button>(R.id.btn_add_phrase).setOnClickListener {
            val phrase = editPhrase.text.toString().trim()
            if (phrase.isEmpty()) return@setOnClickListener
            val list = SettingsStore.quickPhrases(this).toMutableList()
            if (phrase !in list) list.add(phrase)
            SettingsStore.saveQuickPhrases(this, list)
            editPhrase.text.clear()
            refreshPhrases()
        }

        // ---------- 8. Clipboard history ----------
        refreshClipboardInfo()
        findViewById<Button>(R.id.btn_clear_clipboard).setOnClickListener {
            SettingsStore.prefs(this).edit().remove("clipboard_history").apply()
            refreshClipboardInfo()
            Toast.makeText(this, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToolbarOrder()
        refreshPhrases()
        refreshClipboardInfo()
    }

    private fun refreshClipboardInfo() {
        val count = SettingsStore.clipboardHistory(this).size
        findViewById<TextView>(R.id.text_clipboard_info).text =
            "শেষ ১০টা কপি করা লেখা কীবোর্ডে জমা থাকে। এখন জমা আছে: $count টা।"
    }

    private fun refreshToolbarOrder() {
        val container = findViewById<LinearLayout>(R.id.layout_toolbar_order)
        container.removeAllViews()
        var order = SettingsStore.toolbarOrder(this)
        order.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(this).apply {
                text = "${index + 1}. ${toolbarLabels[item] ?: item}"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnUp = Button(this).apply {
                text = "↑ Up"
                setOnClickListener {
                    if (index > 0) {
                        val mutable = order.toMutableList()
                        val tmp = mutable[index - 1]
                        mutable[index - 1] = mutable[index]
                        mutable[index] = tmp
                        order = mutable
                        SettingsStore.saveToolbarOrder(this@MainActivity, mutable)
                        refreshToolbarOrder()
                    }
                }
            }
            val btnDown = Button(this).apply {
                text = "↓ Down"
                setOnClickListener {
                    if (index < order.size - 1) {
                        val mutable = order.toMutableList()
                        val tmp = mutable[index + 1]
                        mutable[index + 1] = mutable[index]
                        mutable[index] = tmp
                        order = mutable
                        SettingsStore.saveToolbarOrder(this@MainActivity, mutable)
                        refreshToolbarOrder()
                    }
                }
            }
            row.addView(label)
            row.addView(btnUp)
            row.addView(btnDown)
            container.addView(row)
        }
    }

    private fun refreshPhrases() {
        val container = findViewById<LinearLayout>(R.id.layout_phrases)
        container.removeAllViews()
        val phrases = SettingsStore.quickPhrases(this)
        phrases.forEach { phrase ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val label = TextView(this).apply {
                text = phrase
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnDelete = Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    val list = SettingsStore.quickPhrases(this@MainActivity).toMutableList()
                    list.remove(phrase)
                    SettingsStore.saveQuickPhrases(this@MainActivity, list)
                    refreshPhrases()
                }
            }
            row.addView(label)
            row.addView(btnDelete)
            container.addView(row)
        }
    }
}
