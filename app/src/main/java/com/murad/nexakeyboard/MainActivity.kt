package com.murad.nexakeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * NexaKeyboard Settings (v2.4).
 *
 * Sections (top to bottom): microphone permission, Gemini API key
 * (save / update / clear / REAL test that calls Gemini), enable /
 * pick keyboard, Tone chooser, per-language autocorrect, Bangla
 * layout, theme editor, toolbar order (Paste / Undo / Redo, Up/Down),
 * Quick Phrases (add/delete), clipboard history (clear).
 *
 * The full API key is never displayed — only "saved" / "not set",
 * plus the last 4 characters so the user can see WHICH key is saved.
 * A key that does not start with "AIza" is NOT a Gemini API key, so
 * saving one shows a clear warning (but still saves it).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MIC = 1001
    }

    // Toolbar editor: every item, icon + name, in Settings.
    // The keyboard itself shows icons only, in the saved visible order.
    private val toolbarLabels = mapOf(
        "grammar" to "✎ Grammar Fix",
        "tone" to "🎭 Tone",
        "clipboard" to "📋 Clipboard",
        "phrases" to "💬 Phrases",
        "paste" to "📥 Paste",
        "undo" to "↩ Undo",
        "redo" to "↪ Redo",
        "settings" to "⚙️ Settings"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---------- 0. Microphone permission (v2.4) ----------
        // Voice typing needs RECORD_AUDIO at RUNTIME — declaring it in
        // the manifest alone is not enough, and no code ever asked for
        // it, so the mic failed the instant it was tapped. Ask on
        // launch (with a Bengali explanation first) and show the
        // state in a visible row the user can come back to.
        refreshMicStatus()
        findViewById<Button>(R.id.btn_mic_permission).setOnClickListener {
            requestMicPermission()
        }
        if (!hasMicPermission()) {
            Toast.makeText(
                this, "ভয়েস টাইপিংয়ের জন্য মাইকের অনুমতি লাগবে",
                Toast.LENGTH_LONG
            ).show()
            requestMicPermission()
        }

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
            // v2.4 — say exactly WHAT was saved, so there is no
            // doubt: the last 4 characters prove the key landed.
            Toast.makeText(
                this, "Gemini API key saved ✓ — শেষ ৪ অক্ষর: ${key.takeLast(4)}",
                Toast.LENGTH_LONG
            ).show()
            // Real Gemini keys start with "AIza" — anything else
            // (e.g. a key starting "AQ.Ab8R") is not a Gemini API
            // key and the Test will fail. Warn, but still save it.
            if (!key.startsWith("AIza")) {
                Toast.makeText(
                    this,
                    "সতর্কতা: এটা Gemini API কী মনে হচ্ছে না — 'AIza' দিয়ে শুরু হওয়া কী লাগবে",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<Button>(R.id.btn_get_key).setOnClickListener {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com"))
            )
        }
        findViewById<Button>(R.id.btn_clear_key).setOnClickListener {
            SettingsStore.clearGeminiKey(this)
            editKey.text.clear()
            refreshKeyStatus()
            Toast.makeText(this, "API key removed", Toast.LENGTH_SHORT).show()
        }
        // v2.3 — Test now REALLY calls Gemini ("Say OK") and shows the
        // result. The button is disabled and the status shows
        // "Testing…" while the call runs, so the user is never left
        // with no result.
        val btnTest = findViewById<Button>(R.id.btn_test_key)
        val statusView = findViewById<TextView>(R.id.text_key_status)
        btnTest.setOnClickListener {
            val key = SettingsStore.geminiKey(this)
            if (key.isBlank()) {
                Toast.makeText(this, "No API key saved yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            statusView.text = "Testing… — Gemini-কে ডাকা হচ্ছে…"
            statusView.setTextColor(Color.parseColor("#5F6368"))
            GeminiClient.testKey(key) { result ->
                btnTest.isEnabled = true
                when (result) {
                    is GeminiClient.GeminiResult.Ok -> {
                        val reply = result.text.take(40)
                        statusView.text = "Test passed ✓ — Gemini is working (উত্তর: $reply)"
                        statusView.setTextColor(Color.parseColor("#1E8E3E"))
                        Toast.makeText(
                            this, "Test passed ✓ — Gemini is working",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is GeminiClient.GeminiResult.Fail -> {
                        statusView.text = "Test failed: ${result.reason}"
                        statusView.setTextColor(Color.parseColor("#D93025"))
                        Toast.makeText(
                            this, "Test failed: ${result.reason}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
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

        // ---------- Bangla layout: Gboard direct (default) or Bijoy ----------
        val layoutGroup = findViewById<RadioGroup>(R.id.radio_bangla_layout)
        val layoutToId = mapOf(
            "gboard" to R.id.radio_layout_gboard,
            "bijoy" to R.id.radio_layout_bijoy
        )
        layoutGroup.check(layoutToId[SettingsStore.banglaLayout(this)] ?: R.id.radio_layout_gboard)
        layoutGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = layoutToId.entries.firstOrNull { it.value == checkedId }?.key ?: "gboard"
            SettingsStore.prefs(this).edit().putString("bangla_layout", v).apply()
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
        heightGroup.check(heightToId[SettingsStore.keyboardHeight(this)] ?: R.id.radio_height_short)
        heightGroup.setOnCheckedChangeListener { _, checkedId ->
            val v = heightToId.entries.firstOrNull { it.value == checkedId }?.key ?: "short"
            SettingsStore.prefs(this).edit().putString("keyboard_height", v).apply()
        }

        // Number row on/off (default: on).
        val swNumberRow = findViewById<Switch>(R.id.switch_number_row)
        swNumberRow.isChecked = SettingsStore.showNumberRow(this)
        swNumberRow.setOnCheckedChangeListener { _, checked ->
            SettingsStore.prefs(this).edit().putBoolean("number_row", checked).apply()
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
        refreshMicStatus()
        refreshKeyStatus()
        refreshToolbarOrder()
        refreshPhrases()
        refreshClipboardInfo()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            refreshMicStatus()
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this, "মাইকের অনুমতি দেওয়া হয়েছে ✓ — এখন ভয়েস টাইপিং চলবে",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "মাইকের অনুমতি দেওয়া হয়নি — ভয়েস টাইপিং চলবে না",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
        )
    }

    /**
     * v2.4 — the mic-permission row at the top of Settings: green
     * "অনুমতি দেওয়া হয়েছে ✓" when granted, red "অনুমতি দেওয়া
     * হয়নি" + a working [অনুমতি দিন] button when not.
     */
    private fun refreshMicStatus() {
        val status = findViewById<TextView>(R.id.text_mic_status)
        val button = findViewById<Button>(R.id.btn_mic_permission)
        if (hasMicPermission()) {
            status.text = "মাইক্রোফোন: অনুমতি দেওয়া হয়েছে ✓"
            status.setTextColor(Color.parseColor("#1E8E3E"))
            button.text = "অনুমতি দেওয়া আছে ✓"
            button.isEnabled = false
        } else {
            status.text = "মাইক্রোফোন: অনুমতি দেওয়া হয়নি"
            status.setTextColor(Color.parseColor("#D93025"))
            button.text = "অনুমতি দিন"
            button.isEnabled = true
        }
    }

    /**
     * v2.4 — the status line is BIG (18sp, bold) and coloured, at
     * the TOP of the Settings screen, so it cannot be missed:
     * green "✓ সেভ হয়েছে — শেষ ৪ অক্ষর: XXXX" when a key is saved,
     * red "এখনো কোনো কী দেওয়া হয়নি" when not set. A saved key
     * that does not start with "AIza" also shows a red warning line
     * underneath, because it is not a Gemini API key.
     */
    private fun refreshKeyStatus() {
        val status = findViewById<TextView>(R.id.text_key_status)
        val warning = findViewById<TextView>(R.id.text_key_warning)
        val key = SettingsStore.geminiKey(this)
        if (key.isNotBlank()) {
            status.text = "✓ সেভ হয়েছে — শেষ ৪ অক্ষর: ${key.takeLast(4)}"
            status.setTextColor(Color.parseColor("#1E8E3E"))
            if (!key.startsWith("AIza")) {
                warning.text =
                    "⚠ এটা Gemini API কী মনে হচ্ছে না — Google AI Studio থেকে 'AIza' দিয়ে শুরু হওয়া কী নিন"
                warning.visibility = View.VISIBLE
            } else {
                warning.visibility = View.GONE
            }
        } else {
            status.text = "এখনো কোনো কী দেওয়া হয়নি"
            status.setTextColor(Color.parseColor("#D93025"))
            warning.visibility = View.GONE
        }
    }

    private fun refreshClipboardInfo() {
        val count = SettingsStore.clipboardHistory(this).size
        findViewById<TextView>(R.id.text_clipboard_info).text =
            "শেষ ১০টা কপি করা লেখা কীবোর্ডে জমা থাকে। এখন জমা আছে: $count টা।"
    }

    /**
     * Toolbar editor: all 8 items, visible ones first in saved order,
     * then hidden ones. Each row has a Show checkbox and Up / Down
     * buttons (Up/Down only move visible items). Unchecking hides the
     * item from the keyboard; checking it again brings it back at the
     * end, and it can then be moved up.
     */
    private fun refreshToolbarOrder() {
        val container = findViewById<LinearLayout>(R.id.layout_toolbar_order)
        container.removeAllViews()
        val visible = SettingsStore.toolbarOrder(this).toMutableList()
        val displayOrder = visible + SettingsStore.TOOLBAR_ITEMS.filter { it !in visible }
        for (item in displayOrder) {
            val isVisible = item in visible
            val index = visible.indexOf(item)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val check = CheckBox(this).apply {
                isChecked = isVisible
                setOnCheckedChangeListener { _, checked ->
                    val current = SettingsStore.toolbarOrder(this@MainActivity).toMutableList()
                    if (checked && item !in current) {
                        current.add(item)
                    } else if (!checked) {
                        current.remove(item)
                    }
                    SettingsStore.saveToolbarOrder(this@MainActivity, current)
                    refreshToolbarOrder()
                }
            }
            val label = TextView(this).apply {
                text = toolbarLabels[item] ?: item
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnUp = Button(this).apply {
                text = "↑ Up"
                isEnabled = isVisible && index > 0
                setOnClickListener {
                    if (isVisible && index > 0) {
                        val mutable = SettingsStore.toolbarOrder(this@MainActivity).toMutableList()
                        val pos = mutable.indexOf(item)
                        if (pos > 0) {
                            val tmp = mutable[pos - 1]
                            mutable[pos - 1] = mutable[pos]
                            mutable[pos] = tmp
                            SettingsStore.saveToolbarOrder(this@MainActivity, mutable)
                            refreshToolbarOrder()
                        }
                    }
                }
            }
            val btnDown = Button(this).apply {
                text = "↓ Down"
                isEnabled = isVisible && index >= 0 && index < visible.size - 1
                setOnClickListener {
                    if (isVisible) {
                        val mutable = SettingsStore.toolbarOrder(this@MainActivity).toMutableList()
                        val pos = mutable.indexOf(item)
                        if (pos >= 0 && pos < mutable.size - 1) {
                            val tmp = mutable[pos + 1]
                            mutable[pos + 1] = mutable[pos]
                            mutable[pos] = tmp
                            SettingsStore.saveToolbarOrder(this@MainActivity, mutable)
                            refreshToolbarOrder()
                        }
                    }
                }
            }
            row.addView(check)
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
