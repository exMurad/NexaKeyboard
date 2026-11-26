package com.murad.nexakeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import java.util.Locale

sealed class UiKey {
    data class Char(val def: KeyDef) : UiKey()
    data class Special(val type: SpecialKey, val label: String, val weight: Float = 1f) : UiKey()
}

enum class SpecialKey {
    SHIFT, DELETE, LANG, SYMBOLS, ABC, EMOJI, SPACE, ENTER, DOT,
    MIC, GRAMMAR, TONE, PASTE, UNDO, REDO, CLIPBOARD, PHRASES, SETTINGS
}

enum class Mode {
    BANGLA, ENGLISH, SYMBOLS, EMOJI, CLIPBOARD, PHRASES
}

/**
 * NexaKeyboard — version 2.2 (Gboard-like look + Bengali keys).
 *
 * Keeps v1: Bangla (Bijoy) + English, numbers/symbols, emoji, a small
 * suggestion strip, and a dark/light Gboard-like theme. No glide typing.
 *
 * New in v2.2:
 *  - Bangla mode shows BENGALI letters on the keys (like Gboard):
 *    the label of each key is the Bengali character it types
 *    (derived from the existing Bijoy mapping in Layouts.kt —
 *    label = output, Shift label = shiftOutput). English mode
 *    still shows English letters. Typing/output values unchanged.
 *  - Pre-base signs (ি / ে / ৈ) work BOTH ways: tapped BEFORE a
 *    consonant (Bijoy style) they are held as pending composing
 *    text and committed after the consonant; tapped AFTER a Bengali
 *    consonant (Gboard style, e.g. ক then ি) they are committed
 *    straight away — Unicode stores consonant + sign, which renders
 *    as কি / কে / কৈ.
 *  - Clipboard preview chip in the suggestion bar: when any text is
 *    copied (in any app), the strip shows a small preview (first
 *    ~20 characters) of it; tapping the chip pastes the full text.
 *    The 3 word suggestions stay beside it.
 *
 * New in v2:
 *  - Voice typing (mic key, SpeechRecognizer, bn-BD / en-US), refined
 *    by Gemini when an API key is saved, otherwise raw transcript.
 *  - Gemini API key in Settings (save / update / clear, never shown
 *    in full, test only checks it is saved).
 *  - Grammar Fix + Tone Change (Professional / Casual / Formal /
 *    Friendly, chosen in Settings) via Gemini.
 *  - Suggestion bar: Paste, Undo, Redo — order reorderable in Settings.
 *  - Clipboard history (last 10), Quick Phrases, per-language
 *    autocorrect, theme editor (colour / shape / height).
 *
 * Bijoy detail: ি / ে / ৈ are typed BEFORE their consonant in
 * Bijoy, but Unicode stores the consonant first. So the service
 * holds a pending pre-base sign as composing text and commits it
 * right after the next consonant (কি = d then j, কে = c then j).
 * Since v2.2 they also work the Gboard way: tapped AFTER a Bengali
 * consonant they are committed immediately (ক then ি = কি), because
 * Unicode order consonant + sign renders the same way.
 * ো forms naturally: ে + consonant + া renders as ো.
 * Reph (Shift+a = র্) is typed after the consonant it sits on,
 * which is also the Unicode order (ক + র্ + ম = কর্ম).
 *
 * Undo/Redo limit: a stack of the last 20 edits, each a snapshot of
 * the text before the cursor (up to 500 characters). Text after the
 * cursor is not touched by Undo/Redo.
 */
class NexaKeyboardService : InputMethodService() {

    private var mode = Mode.BANGLA
    private var lastLangMode = Mode.BANGLA
    private var shift = false
    private var pendingPreBase: String? = null

    private var darkTheme = true
    private var keyColour = "grey"
    private var keyShape = "rounded"
    private var keyHeightDp = 54
    private var autocorrectBangla = false
    private var autocorrectEnglish = true
    private var toolbarOrder = SettingsStore.DEFAULT_TOOLBAR_ORDER
    private var showNumberRow = true

    private val suggestionViews = mutableListOf<TextView>()
    private var clipboardChipView: TextView? = null
    private var clipboardFullText: String? = null
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreateInputView(): View {
        loadSettings()
        return buildView()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
        shift = false
        pendingPreBase = null
        updateSuggestions()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    private fun loadSettings() {
        darkTheme = SettingsStore.isDarkTheme(this)
        keyColour = SettingsStore.keyColour(this)
        keyShape = SettingsStore.keyShape(this)
        keyHeightDp = when (SettingsStore.keyboardHeight(this)) {
            "short" -> 46
            "tall" -> 62
            else -> 54
        }
        autocorrectBangla = SettingsStore.autocorrectBangla(this)
        autocorrectEnglish = SettingsStore.autocorrectEnglish(this)
        toolbarOrder = SettingsStore.toolbarOrder(this)
        showNumberRow = SettingsStore.showNumberRow(this)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- colours / theme (Gboard-like, v2.1) ----------

    private fun bgColor() =
        if (darkTheme) Color.parseColor("#202124") else Color.parseColor("#F1F3F4")

    private fun keyBg(): Int {
        return when (keyColour) {
            "dark" -> if (darkTheme) Color.parseColor("#3C4043") else Color.parseColor("#D0D0D0")
            "blue" -> if (darkTheme) Color.parseColor("#2B4A6F") else Color.parseColor("#D6E4F0")
            // Gboard default grey.
            else -> if (darkTheme) Color.parseColor("#303134") else Color.parseColor("#FFFFFF")
        }
    }

    private fun specialBg() =
        if (darkTheme) Color.parseColor("#5F6368") else Color.parseColor("#DADCE0")

    private fun textColor() =
        if (darkTheme) Color.parseColor("#E8EAED") else Color.parseColor("#202124")

    /** Gboard blue for suggestion text. */
    private fun suggestionColor() =
        if (darkTheme) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")

    /** Gboard blue Enter key. */
    private fun enterBg() =
        if (darkTheme) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8")

    private fun enterTextColor() =
        if (darkTheme) Color.parseColor("#202124") else Color.WHITE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- view building ----------

    private fun buildView(): View {
        suggestionViews.clear()
        clipboardChipView = null
        clipboardFullText = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        // One slim Gboard-like toolbar row (36dp, flat, no grey key boxes),
        // icon-only so 5-6 items fit in one row. Only the visible items,
        // in the saved order from Settings, are rendered here.
        if (mode == Mode.BANGLA || mode == Mode.ENGLISH) {
            val toolbar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(bgColor())
            }
            for (item in toolbarOrder) {
                val (label, type) = when (item) {
                    "grammar" -> "✎" to SpecialKey.GRAMMAR
                    "tone" -> "🎭" to SpecialKey.TONE
                    "clipboard" -> "📋" to SpecialKey.CLIPBOARD
                    "phrases" -> "💬" to SpecialKey.PHRASES
                    "paste" -> "📥" to SpecialKey.PASTE
                    "undo" -> "↩" to SpecialKey.UNDO
                    "redo" -> "↪" to SpecialKey.REDO
                    "settings" -> "⚙️" to SpecialKey.SETTINGS
                    else -> continue
                }
                toolbar.addView(
                    makeToolbarButton(label) { onSpecialKey(type) },
                    LinearLayout.LayoutParams(0, dp(36), 1f)
                )
            }
            root.addView(toolbar)

            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            // Clipboard preview chip (v2.2): first item of the strip.
            // Shows a short preview of whatever text is on the system
            // clipboard; tapping it pastes the full text. Hidden when
            // the clipboard is empty — the word suggestions stay.
            val chip = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(suggestionColor())
                setPadding(dp(8), dp(6), dp(8), dp(6))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(specialBg())
                }
                visibility = View.GONE
                setOnClickListener { onClipboardChipClicked() }
            }
            clipboardChipView = chip
            strip.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
            )
            repeat(3) {
                val tv = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 16f
                    setTextColor(suggestionColor())
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setOnClickListener { onSuggestionClicked(text.toString()) }
                }
                suggestionViews.add(tv)
                strip.addView(
                    tv,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            root.addView(strip)
        }

        when (mode) {
            Mode.BANGLA, Mode.ENGLISH -> addLetterRows(root)
            Mode.SYMBOLS -> addSymbolRows(root)
            Mode.EMOJI -> addEmojiRows(root)
            Mode.CLIPBOARD -> addClipboardRows(root)
            Mode.PHRASES -> addPhraseRows(root)
        }
        return root
    }

    private fun keyBackground(isSpecial: Boolean, isEnter: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (keyShape == "square") 0f else dp(10).toFloat()
            setColor(
                when {
                    isEnter -> enterBg()
                    isSpecial -> specialBg()
                    else -> keyBg()
                }
            )
        }

    /** Flat toolbar button: same background as the keyboard, no key box. */
    private fun makeToolbarButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            minimumHeight = 0
            setTextColor(textColor())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    /** Current-language label for the spacebar, Gboard-style. */
    private fun spaceLabel(): String {
        val langMode = if (mode == Mode.BANGLA || mode == Mode.ENGLISH) mode else lastLangMode
        return if (langMode == Mode.BANGLA) "বাংলা" else "English"
    }

    private fun makeButton(
        label: String,
        weight: Float,
        isSpecial: Boolean,
        textSizeSp: Float,
        onClick: () -> Unit,
        isEnter: Boolean = false
    ): Button = Button(this).apply {
        text = label
        textSize = textSizeSp
        isAllCaps = false
        minimumHeight = 0
        setTextColor(if (isEnter) enterTextColor() else textColor())
        background = keyBackground(isSpecial, isEnter)
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(keyHeightDp), weight).apply {
            setMargins(dp(3), dp(5), dp(3), dp(5))
        }
        setOnClickListener { onClick() }
    }

    private fun addRow(root: LinearLayout, keys: List<UiKey>, textSizeSp: Float = 22f) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (k in keys) {
            when (k) {
                is UiKey.Char -> {
                    val def = k.def
                    // v2.2 — Bangla mode shows BENGALI letters on the
                    // keys, like Gboard: the label is the Bengali
                    // character the key types (output / shiftOutput
                    // from the Bijoy mapping). English mode keeps the
                    // English letters as labels. Outputs unchanged.
                    val label = if (mode == Mode.BANGLA) {
                        if (shift) def.shiftOutput ?: def.output else def.output
                    } else if (shift && def.shiftLabel != null) {
                        def.shiftLabel
                    } else {
                        def.label
                    }
                    row.addView(makeButton(label, 1f, false, textSizeSp, { onCharKey(def) }))
                }
                is UiKey.Special -> {
                    val size = when (k.type) {
                        SpecialKey.SPACE -> 13f
                        else -> 17f
                    }
                    val isEnter = k.type == SpecialKey.ENTER
                    row.addView(
                        makeButton(k.label, k.weight, true, size, { onSpecialKey(k.type) }, isEnter)
                    )
                }
            }
        }
        root.addView(row)
    }

    private fun addLetterRows(root: LinearLayout) {
        val isBangla = mode == Mode.BANGLA
        val numbers = if (isBangla) BijoyLayout.numberRow else EnglishLayout.numberRow
        val r1 = if (isBangla) BijoyLayout.row1 else EnglishLayout.row1
        val r2 = if (isBangla) BijoyLayout.row2 else EnglishLayout.row2
        val r3 = if (isBangla) BijoyLayout.row3 else EnglishLayout.row3

        if (showNumberRow) addRow(root, numbers.map { UiKey.Char(it) }, 18f)
        addRow(root, r1.map { UiKey.Char(it) })
        addRow(root, r2.map { UiKey.Char(it) })
        addRow(
            root,
            listOf(UiKey.Special(SpecialKey.SHIFT, if (shift) "⇪" else "⇧", 1.5f)) +
                r3.map { UiKey.Char(it) } +
                listOf(UiKey.Special(SpecialKey.DELETE, "⌫", 1.5f))
        )
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.SYMBOLS, "?123", 1.5f),
                UiKey.Special(SpecialKey.LANG, if (isBangla) "EN" else "বাং", 1.2f),
                UiKey.Special(SpecialKey.EMOJI, "😀", 1.1f),
                UiKey.Special(SpecialKey.MIC, "🎤", 1f),
                UiKey.Special(SpecialKey.SPACE, spaceLabel(), 3f),
                UiKey.Special(SpecialKey.DOT, if (isBangla) "।" else ".", 1f),
                UiKey.Special(SpecialKey.ENTER, "⏎", 1.2f)
            )
        )
    }

    private fun addSymbolRows(root: LinearLayout) {
        addRow(root, SymbolsLayout.row1.map { UiKey.Char(it) })
        addRow(root, SymbolsLayout.row2.map { UiKey.Char(it) })
        addRow(root, SymbolsLayout.row3.map { UiKey.Char(it) })
        addRow(root, SymbolsLayout.row4.map { UiKey.Char(it) })
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.ABC, "ABC", 1.5f),
                UiKey.Special(SpecialKey.LANG, if (lastLangMode == Mode.BANGLA) "EN" else "বাং", 1.2f),
                UiKey.Special(SpecialKey.SPACE, spaceLabel(), 4f),
                UiKey.Special(SpecialKey.ENTER, "⏎", 1.2f)
            )
        )
    }

    private fun addEmojiRows(root: LinearLayout) {
        for (chunk in EmojiList.emojis.chunked(8)) {
            addRow(root, chunk.map { UiKey.Char(KeyDef(it, it)) })
        }
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.ABC, "ABC", 1.5f),
                UiKey.Special(SpecialKey.LANG, if (lastLangMode == Mode.BANGLA) "EN" else "বাং", 1.2f),
                UiKey.Special(SpecialKey.SPACE, spaceLabel(), 4f),
                UiKey.Special(SpecialKey.ENTER, "⏎", 1.2f)
            )
        )
    }

    private fun addClipboardRows(root: LinearLayout) {
        // Pick up whatever is on the system clipboard right now.
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val current = cm?.primaryClip?.getItemAt(0)?.text?.toString()
        if (!current.isNullOrBlank()) SettingsStore.addClipboardItem(this, current)

        val items = SettingsStore.clipboardHistory(this)
        if (items.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "ক্লিপবোর্ড খালি — আগে কিছু কপি করুন"
                textSize = 16f
                setTextColor(textColor())
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        }
        for (item in items) {
            val label = if (item.length > 32) item.take(30) + "…" else item
            root.addView(makeButton(label, 1f, false, 15f, {
                pushUndoSnapshot()
                currentInputConnection?.commitText(item, 1)
                SettingsStore.addClipboardItem(this, item)
                mode = lastLangMode
                refreshKeyboard()
            }))
        }
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.ABC, "ABC", 1.5f),
                UiKey.Special(SpecialKey.SPACE, spaceLabel(), 4f),
                UiKey.Special(SpecialKey.ENTER, "⏎", 1.2f)
            )
        )
    }

    private fun addPhraseRows(root: LinearLayout) {
        val phrases = SettingsStore.quickPhrases(this)
        for (phrase in phrases) {
            root.addView(makeButton(phrase, 1f, false, 16f, {
                pushUndoSnapshot()
                currentInputConnection?.commitText("$phrase ", 1)
                mode = lastLangMode
                refreshKeyboard()
            }))
        }
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.ABC, "ABC", 1.5f),
                UiKey.Special(SpecialKey.SPACE, spaceLabel(), 4f),
                UiKey.Special(SpecialKey.ENTER, "⏎", 1.2f)
            )
        )
    }

    // ---------- Undo / Redo ----------

    private fun textBeforeCursor(): String =
        currentInputConnection?.getTextBeforeCursor(500, 0)?.toString() ?: ""

    private fun pushUndoSnapshot() {
        val text = textBeforeCursor()
        if (undoStack.lastOrNull() == text) return
        undoStack.addLast(text)
        while (undoStack.size > 20) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun replaceBeforeCursor(text: String) {
        val ic = currentInputConnection ?: return
        val current = textBeforeCursor()
        if (current.isNotEmpty()) ic.deleteSurroundingText(current.length, 0)
        if (text.isNotEmpty()) ic.commitText(text, 1)
    }

    private fun doUndo() {
        if (undoStack.isEmpty()) {
            toast("আর Undo করা যাবে না")
            return
        }
        val current = textBeforeCursor()
        redoStack.addLast(current)
        val target = undoStack.removeLast()
        replaceBeforeCursor(target)
        updateSuggestions()
    }

    private fun doRedo() {
        if (redoStack.isEmpty()) {
            toast("আর Redo করা যাবে না")
            return
        }
        val current = textBeforeCursor()
        undoStack.addLast(current)
        val target = redoStack.removeLast()
        replaceBeforeCursor(target)
        updateSuggestions()
    }

    // ---------- key handling ----------

    private fun onCharKey(def: KeyDef) {
        val ic = currentInputConnection ?: return
        pushUndoSnapshot()
        val out = if (shift) def.shiftOutput ?: def.output else def.output
        val outIsConsonant = if (shift) def.shiftIsConsonant else def.isConsonant
        val outIsPreBase = if (shift) def.shiftIsPreBase else def.isPreBase

        if (mode == Mode.BANGLA) {
            if (outIsPreBase) {
                // v2.2 — pre-base signs (ি / ে / ৈ) work BOTH ways:
                // (a) Gboard style: tapped AFTER a Bengali consonant,
                //     commit immediately. Unicode stores consonant +
                //     sign, which renders the sign before the consonant
                //     (ক then ি -> কি, ক then ে -> কে).
                // (b) Bijoy style: no consonant before the cursor,
                //     hold as pending composing text and commit it
                //     right after the next consonant (ি then ক -> কি).
                if (pendingPreBase != null) ic.commitText(pendingPreBase!!, 1)
                pendingPreBase = null
                val before = currentInputConnection
                    ?.getTextBeforeCursor(1, 0)?.toString()?.lastOrNull()
                if (before != null && isBengaliConsonant(before)) {
                    ic.commitText(out, 1)
                } else {
                    pendingPreBase = out
                    ic.setComposingText(out, 1)
                }
            } else if (pendingPreBase != null) {
                val pending = pendingPreBase!!
                pendingPreBase = null
                if (outIsConsonant) {
                    ic.commitText(out + pending, 1)
                } else {
                    ic.commitText(pending, 1)
                    ic.commitText(out, 1)
                }
            } else {
                ic.commitText(out, 1)
            }
        } else {
            ic.commitText(out, 1)
        }

        if (shift) {
            shift = false
            refreshKeyboard()
        } else {
            updateSuggestions()
        }
    }

    private fun onSpecialKey(type: SpecialKey) {
        val ic = currentInputConnection
        when (type) {
            SpecialKey.SHIFT -> {
                shift = !shift
                refreshKeyboard()
            }
            SpecialKey.DELETE -> {
                pushUndoSnapshot()
                if (pendingPreBase != null) {
                    pendingPreBase = null
                    ic?.finishComposingText()
                } else {
                    ic?.deleteSurroundingText(1, 0)
                }
                updateSuggestions()
            }
            SpecialKey.LANG -> {
                flushPending()
                mode = if (mode == Mode.BANGLA) Mode.ENGLISH else Mode.BANGLA
                lastLangMode = mode
                shift = false
                refreshKeyboard()
            }
            SpecialKey.SYMBOLS -> {
                flushPending()
                mode = Mode.SYMBOLS
                shift = false
                refreshKeyboard()
            }
            SpecialKey.ABC -> {
                flushPending()
                mode = lastLangMode
                shift = false
                refreshKeyboard()
            }
            SpecialKey.EMOJI -> {
                flushPending()
                mode = Mode.EMOJI
                refreshKeyboard()
            }
            SpecialKey.CLIPBOARD -> {
                flushPending()
                mode = Mode.CLIPBOARD
                refreshKeyboard()
            }
            SpecialKey.PHRASES -> {
                flushPending()
                mode = Mode.PHRASES
                refreshKeyboard()
            }
            SpecialKey.SPACE -> {
                flushPending()
                pushUndoSnapshot()
                ic?.commitText(" ", 1)
                updateSuggestions()
            }
            SpecialKey.DOT -> {
                flushPending()
                pushUndoSnapshot()
                ic?.commitText(if (mode == Mode.BANGLA) "।" else ".", 1)
                updateSuggestions()
            }
            SpecialKey.ENTER -> {
                flushPending()
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            SpecialKey.MIC -> startVoiceTyping()
            SpecialKey.GRAMMAR -> runGrammarFix()
            SpecialKey.TONE -> runToneChange()
            SpecialKey.SETTINGS -> openSettings()
            SpecialKey.PASTE -> doPaste()
            SpecialKey.UNDO -> doUndo()
            SpecialKey.REDO -> doRedo()
        }
    }

    private fun flushPending() {
        val p = pendingPreBase
        if (p != null) {
            pendingPreBase = null
            currentInputConnection?.commitText(p, 1)
        }
    }

    /** Bengali consonants: ক–হ (U+0995–U+09B9) and ড়, ঢ়, য় (U+09DC–U+09DF). */
    private fun isBengaliConsonant(ch: Char): Boolean =
        (ch in '\u0995'..'\u09B9') || (ch in '\u09DC'..'\u09DF')

    private fun refreshKeyboard() {
        setInputView(buildView())
        updateSuggestions()
    }

    /**
     * v2.2 — ⚙️ settings icon in the toolbar. Opens the NexaKeyboard
     * Settings screen (MainActivity) straight from the keyboard, so
     * Murad never has to leave the app he is typing in and open
     * NexaKeyboard from the launcher. A service is not an activity,
     * so the Intent needs FLAG_ACTIVITY_NEW_TASK. Any pending
     * pre-base sign is committed first, so nothing typed is lost.
     */
    private fun openSettings() {
        flushPending()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ---------- Paste / clipboard ----------

    private fun doPaste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) {
            toast("ক্লিপবোর্ড খালি")
            return
        }
        pushUndoSnapshot()
        currentInputConnection?.commitText(text, 1)
        SettingsStore.addClipboardItem(this, text)
        updateSuggestions()
    }

    // ---------- Voice typing ----------

    private fun startVoiceTyping() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("এই ফোনে ভয়েস টাইপিং পাওয়া যাচ্ছে না")
            return
        }
        speechRecognizer?.destroy()
        val locale = if (mode == Mode.BANGLA || lastLangMode == Mode.BANGLA) {
            Locale.forLanguageTag("bn-BD")
        } else {
            Locale.US
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কিছু শোনা যায়নি — আবার বলুন"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ইন্টারনেট লাগবে — ভয়েস টাইপিং ব্যর্থ হয়েছে"
                    else -> "ভয়েস টাইপিং ব্যর্থ হয়েছে — আবার চেষ্টা করুন"
                }
                toast(msg)
                recognizer.destroy()
                if (speechRecognizer == recognizer) speechRecognizer = null
            }

            override fun onResults(results: Bundle?) {
                val raw = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                recognizer.destroy()
                if (speechRecognizer == recognizer) speechRecognizer = null
                if (raw.isNullOrEmpty()) {
                    toast("কিছু শোনা যায়নি — আবার বলুন")
                    return
                }
                val key = SettingsStore.geminiKey(this@NexaKeyboardService)
                if (key.isBlank()) {
                    pushUndoSnapshot()
                    currentInputConnection?.commitText("$raw ", 1)
                    updateSuggestions()
                    return
                }
                GeminiClient.refineVoiceText(raw, key) { refined ->
                    pushUndoSnapshot()
                    val text = refined ?: raw
                    if (refined == null) toast("AI রিফাইন ব্যর্থ হয়েছে — আসল লেখাটাই বসানো হলো")
                    currentInputConnection?.commitText("$text ", 1)
                    updateSuggestions()
                }
            }
        })
        try {
            recognizer.startListening(intent)
            toast("বলুন…")
        } catch (e: Exception) {
            toast("ভয়েস টাইপিং শুরু করা যায়নি")
        }
    }

    // ---------- AI: Grammar Fix / Tone Change ----------

    private fun currentSentence(): Pair<String, Int>? {
        val text = textBeforeCursor()
        if (text.isBlank()) return null
        val trimmed = text.trimEnd()
        val idx = trimmed.indexOfLast { it == '.' || it == '।' || it == '!' || it == '?' || it == '\n' }
        val sentence = if (idx >= 0) trimmed.substring(idx + 1).trim() else trimmed
        if (sentence.isEmpty()) return null
        return sentence to sentence.length
    }

    private fun requireGeminiKey(): String? {
        val key = SettingsStore.geminiKey(this)
        if (key.isBlank()) {
            toast("Gemini API key needed — set it in NexaKeyboard settings")
            return null
        }
        return key
    }

    private fun runGrammarFix() {
        val key = requireGeminiKey() ?: return
        val (sentence, length) = currentSentence() ?: run {
            toast("আগে কিছু লিখুন")
            return
        }
        GeminiClient.fixGrammar(sentence, key) { corrected ->
            if (corrected == null) {
                toast("AI ব্যর্থ হয়েছে — আবার চেষ্টা করুন")
                return@fixGrammar
            }
            pushUndoSnapshot()
            currentInputConnection?.deleteSurroundingText(length, 0)
            currentInputConnection?.commitText(corrected, 1)
            updateSuggestions()
        }
    }

    private fun runToneChange() {
        val key = requireGeminiKey() ?: return
        val tone = SettingsStore.tone(this)
        val (sentence, length) = currentSentence() ?: run {
            toast("আগে কিছু লিখুন")
            return
        }
        GeminiClient.changeTone(sentence, tone, key) { rewritten ->
            if (rewritten == null) {
                toast("AI ব্যর্থ হয়েছে — আবার চেষ্টা করুন")
                return@changeTone
            }
            pushUndoSnapshot()
            currentInputConnection?.deleteSurroundingText(length, 0)
            currentInputConnection?.commitText(rewritten, 1)
            updateSuggestions()
        }
    }

    // ---------- suggestions ----------

    private fun currentWord(): String {
        val text = currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()
            ?: return ""
        val idx = text.indexOfLast {
            it == ' ' || it == '\n' || it == '\t' || it == ',' ||
                it == '.' || it == '।' || it == '!' || it == '?'
        }
        return if (idx >= 0) text.substring(idx + 1) else text
    }

    private fun updateSuggestions() {
        val isBangla = mode == Mode.BANGLA
        val enabled = if (isBangla) autocorrectBangla else autocorrectEnglish
        val word = currentWord()
        val list = if (enabled && word.length >= 1) {
            SuggestionEngine.suggest(word, isBangla)
        } else {
            emptyList()
        }
        for (i in suggestionViews.indices) {
            suggestionViews[i].text = list.getOrNull(i) ?: ""
        }
        updateClipboardChip()
    }

    /**
     * v2.2 — clipboard preview chip. Reads the system clipboard every
     * time the suggestions refresh (keyboard shown, key pressed, mode
     * changed), so text copied in any app appears at once. Shows a
     * short preview (first ~20 characters, "…" if longer); the full
     * text is kept for the tap. Empty clipboard -> chip hidden, the
     * normal 3 word suggestions stay as they are.
     */
    private fun updateClipboardChip() {
        val chip = clipboardChipView ?: return
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrEmpty()) {
            clipboardFullText = null
            chip.visibility = View.GONE
            return
        }
        clipboardFullText = text
        val flat = text.replace(Regex("\\s+"), " ")
        val preview = if (flat.length > 20) flat.take(20) + "…" else flat
        chip.text = "📋 $preview"
        chip.visibility = View.VISIBLE
    }

    /** Tapping the clipboard chip pastes the FULL copied text. */
    private fun onClipboardChipClicked() {
        val text = clipboardFullText ?: return
        pushUndoSnapshot()
        currentInputConnection?.commitText(text, 1)
        SettingsStore.addClipboardItem(this, text)
        updateSuggestions()
    }

    private fun onSuggestionClicked(suggestion: String) {
        if (suggestion.isEmpty()) return
        val ic = currentInputConnection ?: return
        pushUndoSnapshot()
        val word = currentWord()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        pendingPreBase = null
        ic.commitText("$suggestion ", 1)
        updateSuggestions()
    }
}
