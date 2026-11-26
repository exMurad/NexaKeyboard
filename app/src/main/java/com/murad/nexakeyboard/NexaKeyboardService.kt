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
    MIC, GRAMMAR, TONE, PASTE, UNDO, REDO, CLIPBOARD, PHRASES
}

enum class Mode {
    BANGLA, ENGLISH, SYMBOLS, EMOJI, CLIPBOARD, PHRASES
}

/**
 * NexaKeyboard — second version (v2).
 *
 * Keeps v1: Bangla (Bijoy) + English, numbers/symbols, emoji, a small
 * suggestion strip, and a dark/light Gboard-like theme. No glide typing.
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

    private val suggestionViews = mutableListOf<TextView>()
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
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- colours / theme ----------

    private fun bgColor() =
        if (darkTheme) Color.parseColor("#1C1C1E") else Color.parseColor("#D1D5DB")

    private fun keyBg(): Int {
        return when (keyColour) {
            "dark" -> if (darkTheme) Color.parseColor("#2E2E2E") else Color.parseColor("#D0D0D0")
            "blue" -> if (darkTheme) Color.parseColor("#2B4A6F") else Color.parseColor("#D6E4F0")
            else -> if (darkTheme) Color.parseColor("#3A3A3C") else Color.parseColor("#ECECEC")
        }
    }

    private fun specialBg() =
        if (darkTheme) Color.parseColor("#48484A") else Color.parseColor("#B9BEC6")

    private fun textColor() = if (darkTheme) Color.WHITE else Color.BLACK

    private fun accentColor() = Color.parseColor("#2FA84F")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- view building ----------

    private fun buildView(): View {
        suggestionViews.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

        // AI toolbar row: Grammar Fix, Tone, Clipboard, Phrases.
        if (mode == Mode.BANGLA || mode == Mode.ENGLISH) {
            val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val aiButtons = listOf(
                UiKey.Special(SpecialKey.GRAMMAR, "AI ✎", 1f),
                UiKey.Special(SpecialKey.TONE, "Tone", 1f),
                UiKey.Special(SpecialKey.CLIPBOARD, "📋", 1f),
                UiKey.Special(SpecialKey.PHRASES, "Phrases", 1f)
            )
            for (k in aiButtons) {
                aiRow.addView(
                    makeButton(k.label, k.weight, true, 15f) { onSpecialKey(k.type) },
                    LinearLayout.LayoutParams(0, dp(40), k.weight)
                )
            }
            root.addView(aiRow)
        }

        // Suggestion strip + Paste / Undo / Redo in the saved order.
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (item in toolbarOrder) {
            val (label, type) = when (item) {
                "paste" -> "Paste" to SpecialKey.PASTE
                "undo" -> "↩ Undo" to SpecialKey.UNDO
                "redo" -> "↪ Redo" to SpecialKey.REDO
                else -> continue
            }
            val btn = makeButton(label, 0.9f, true, 14f) { onSpecialKey(type) }
            strip.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
        }
        repeat(3) {
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(accentColor())
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

        when (mode) {
            Mode.BANGLA, Mode.ENGLISH -> addLetterRows(root)
            Mode.SYMBOLS -> addSymbolRows(root)
            Mode.EMOJI -> addEmojiRows(root)
            Mode.CLIPBOARD -> addClipboardRows(root)
            Mode.PHRASES -> addPhraseRows(root)
        }
        return root
    }

    private fun keyBackground(isSpecial: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (keyShape == "square") 0f else dp(6).toFloat()
            setColor(if (isSpecial) specialBg() else keyBg())
        }

    private fun makeButton(
        label: String,
        weight: Float,
        isSpecial: Boolean,
        textSizeSp: Float,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = label
        textSize = textSizeSp
        isAllCaps = false
        minimumHeight = 0
        setTextColor(textColor())
        background = keyBackground(isSpecial)
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(keyHeightDp), weight).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
        setOnClickListener { onClick() }
    }

    private fun addRow(root: LinearLayout, keys: List<UiKey>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (k in keys) {
            when (k) {
                is UiKey.Char -> {
                    val def = k.def
                    val label = if (shift && def.shiftLabel != null) def.shiftLabel else def.label
                    row.addView(makeButton(label, 1f, false, 20f) { onCharKey(def) })
                }
                is UiKey.Special -> {
                    val size = if (k.type == SpecialKey.SPACE) 16f else 17f
                    row.addView(makeButton(k.label, k.weight, true, size) { onSpecialKey(k.type) })
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

        addRow(root, numbers.map { UiKey.Char(it) })
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
                UiKey.Special(SpecialKey.SPACE, "স্পেস", 3f),
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
                UiKey.Special(SpecialKey.SPACE, "স্পেস", 4f),
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
                UiKey.Special(SpecialKey.SPACE, "স্পেস", 4f),
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
            root.addView(makeButton(label, 1f, false, 15f) {
                pushUndoSnapshot()
                currentInputConnection?.commitText(item, 1)
                SettingsStore.addClipboardItem(this, item)
                mode = lastLangMode
                refreshKeyboard()
            })
        }
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.ABC, "ABC", 1.5f),
                UiKey.Special(SpecialKey.SPACE, "স্পেস", 4f),
                UiKey.Special(SpecialKey.ENTER, "⏎", 1.2f)
            )
        )
    }

    private fun addPhraseRows(root: LinearLayout) {
        val phrases = SettingsStore.quickPhrases(this)
        for (phrase in phrases) {
            root.addView(makeButton(phrase, 1f, false, 16f) {
                pushUndoSnapshot()
                currentInputConnection?.commitText("$phrase ", 1)
                mode = lastLangMode
                refreshKeyboard()
            })
        }
        addRow(
            root,
            listOf(
                UiKey.Special(SpecialKey.ABC, "ABC", 1.5f),
                UiKey.Special(SpecialKey.SPACE, "স্পেস", 4f),
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
                if (pendingPreBase != null) ic.commitText(pendingPreBase!!, 1)
                pendingPreBase = out
                ic.setComposingText(out, 1)
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

    private fun refreshKeyboard() {
        setInputView(buildView())
        updateSuggestions()
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
