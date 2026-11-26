package com.murad.nexakeyboard

/**
 * One key on a layout.
 *
 * label/output = what the key shows and types normally.
 * shiftLabel/shiftOutput = what Shift gives.
 * isConsonant = the normal output is a Bengali consonant.
 * isPreBase = the output is ি / ে / ৈ, which Bijoy types BEFORE its
 * consonant. The service holds it and commits it after the consonant,
 * because Unicode stores the consonant first.
 */
data class KeyDef(
    val label: String,
    val output: String,
    val shiftLabel: String? = null,
    val shiftOutput: String? = null,
    val isConsonant: Boolean = false,
    val shiftIsConsonant: Boolean = false,
    val isPreBase: Boolean = false,
    val shiftIsPreBase: Boolean = false,
    /** Small secondary symbol shown in the key's top corner, like
     *  Gboard's long-press alternates (e.g. q -> 1, ক -> া). Tapping
     *  the key types the main letter; long-press types the alternate. */
    val alt: String? = null
)

private fun c(label: String, out: String, shiftOut: String) =
    KeyDef(label, out, shiftOut, shiftOut, isConsonant = true, shiftIsConsonant = true)

private fun s(
    label: String,
    out: String,
    shiftOut: String,
    preBase: Boolean = false,
    shiftPreBase: Boolean = false
) = KeyDef(label, out, shiftOut, shiftOut, isPreBase = preBase, shiftIsPreBase = shiftPreBase)

/**
 * Unicode Bijoy layout (the fixed layout used in Bangladeshi offices
 * and typing tests), verified against the key-by-key chart at
 * https://learntype.app/zh/blog/bijoy-keyboard-layout-complete-guide-chart
 *
 * Examples: আ = g then f, ই = g then d, কি = d then j,
 * কে = c then j, কো = c then j then f, ক্ত = j g k.
 */
object BijoyLayout {
    val numberRow = listOf(
        s("১", "১", "!"),
        s("২", "২", "@"),
        s("৩", "৩", "#"),
        s("৪", "৪", "$"),
        s("৫", "৫", "%"),
        s("৬", "৬", "^"),
        s("৭", "৭", "ঁ"),
        s("৮", "৮", "*"),
        s("৯", "৯", "("),
        s("০", "০", ")")
    )

    val row1 = listOf(
        // q: ঙ is a consonant, but Shift+q (ং) is a sign, not a consonant.
        KeyDef("q", "ঙ", "ং", "ং", isConsonant = true, shiftIsConsonant = false),
        c("w", "য", "য়"),
        c("e", "ড", "ঢ"),
        c("r", "প", "ফ"),
        c("t", "ট", "ঠ"),
        c("y", "চ", "ছ"),
        c("u", "জ", "ঝ"),
        c("i", "হ", "ঞ"),
        c("o", "গ", "ঘ"),
        c("p", "ড়", "ঢ়")
    )

    val row2 = listOf(
        s("a", "ৃ", "র্"),
        s("s", "ু", "ূ"),
        s("d", "ি", "ী", preBase = true),
        s("f", "া", "অ"),
        s("g", "্", "।"),
        c("h", "ব", "ভ"),
        c("j", "ক", "খ"),
        c("k", "ত", "থ"),
        c("l", "দ", "ধ")
    )

    val row3 = listOf(
        s("z", "্র", "্য"),
        s("x", "ও", "ৗ"),
        s("c", "ে", "ৈ", preBase = true, shiftPreBase = true),
        c("v", "র", "ল"),
        c("b", "ন", "ণ"),
        c("n", "স", "ষ"),
        c("m", "ম", "শ"),
        s("\\", "ৎ", "ঃ")
    )
}

object EnglishLayout {
    val numberRow = listOf(
        s("1", "1", "!"),
        s("2", "2", "@"),
        s("3", "3", "#"),
        s("4", "4", "$"),
        s("5", "5", "%"),
        s("6", "6", "^"),
        s("7", "7", "&"),
        s("8", "8", "*"),
        s("9", "9", "("),
        s("0", "0", ")")
    )

    // Gboard-like long-press alternates, shown small in the key corner.
    private val alts = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "@", 's' to "#", 'd' to "$", 'f' to "%", 'g' to "&",
        'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
        'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":",
        'b' to ";", 'n' to "!", 'm' to "?"
    )

    private fun letter(ch: String) =
        KeyDef(ch, ch, ch.uppercase(), ch.uppercase(), alt = alts[ch[0]])

    val row1 = "qwertyuiop".map { letter(it.toString()) }
    val row2 = "asdfghjkl".map { letter(it.toString()) }
    val row3 = "zxcvbnm".map { letter(it.toString()) }
}

/**
 * Direct Bengali layout, exactly like Gboard's Bengali keyboard
 * (Murad's reference screenshot): the alphabet in order, not the
 * Bijoy QWERTY mapping. Tapping a key types that Bengali letter
 * directly. `alt` is the small secondary symbol in the key corner
 * (long-press types it).
 *
 * Shift layer: vowel signs + hasanta/conjuncts + numerals + punctuation.
 *   Shift row 1: া ি ী ু ূ ৃ ে ৈ ো ৌ  (vowel signs)
 *   Shift row 2: ্ র্ ্য ্র ্‌ল ঁ ং ঃ ৎ ।
 *   Shift row 3: ক্ষ জ্ঞ ত্র শ্র দ্র দ্ধ ন্ত স্ত ন্দ ঙ্ক (common conjuncts)
 *   Shift row 4: ১ ২ ৩ ৪ ৫ ৬ ৭ ৮ ৯ ০  (numerals)
 *   Shift row 5: ? ! , . - ৳ % ( )  (punctuation)
 */
object GboardBanglaLayout {
    // Direct key: label = output = the Bengali character itself.
    private fun b(ch: String, alt: String? = null, consonant: Boolean = false) =
        KeyDef(ch, ch, alt = alt, isConsonant = consonant)

    // Vowel sign key: ি / ে / ৈ are pre-base (see service logic).
    private fun sign(ch: String, preBase: Boolean = false) =
        KeyDef(ch, ch, isPreBase = preBase)

    val numberRow = BijoyLayout.numberRow

    val row1 = listOf(
        b("অ", "া"), b("আ", "া"), b("ই", "ি"), b("ঈ", "ী"), b("উ", "ু"),
        b("ঊ", "ূ"), b("এ", "ে"), b("ঐ", "ৈ"), b("ও", "ো"), b("ঔ", "ৌ")
    )

    val row2 = listOf(
        b("ক", "া", true), b("খ", "ি", true), b("গ", "ু", true),
        b("ঘ", "ূ", true), b("ঙ", "ং", true), b("চ", "ে", true),
        b("ছ", "ৈ", true), b("জ", "্", true), b("ঝ", "ঁ", true),
        b("ঞ", "্ঞ", true)
    )

    val row3 = listOf(
        b("ট", "া", true), b("ঠ", "ি", true), b("ড", "ু", true),
        b("ঢ", "ূ", true), b("ণ", "ণ", true), b("ত", "ে", true),
        b("থ", "ৈ", true), b("দ", "ো", true), b("ধ", "ৌ", true),
        b("ন", "ং", true)
    )

    val row4 = listOf(
        b("প", "া", true), b("ফ", "ি", true), b("ব", "ু", true),
        b("ভ", "ূ", true), b("ম", "ে", true), b("য", "ৈ", true),
        b("র", "্র", true), b("ল", "্ল", true), b("শ", "্", true),
        b("ষ", "ঃ", true)
    )

    val row5 = listOf(
        b("স", "া", true), b("হ", "ি", true), b("ড়", "ু", true),
        b("ঢ়", "ূ", true), b("য়", "ে", true), b("ৎ", "ৎ"),
        b("ং", "ং"), b("ঃ", "ঃ")
    )

    // Shift layer — each key's output is the character itself.
    val shiftRow1 = listOf(
        sign("া"), sign("ি", true), sign("ী"), sign("ু"), sign("ূ"),
        sign("ৃ"), sign("ে", true), sign("ৈ", true), sign("ো"), sign("ৌ")
    )

    val shiftRow2 = listOf(
        sign("্"), sign("র্"), sign("্য"), sign("্র"), sign("্ল"),
        sign("ঁ"), sign("ং"), sign("ঃ"), sign("ৎ"), sign("।")
    )

    val shiftRow3 = listOf(
        b("ক্ষ"), b("জ্ঞ"), b("ত্র"), b("শ্র"), b("দ্র"),
        b("দ্ধ"), b("ন্ত"), b("স্ত"), b("ন্দ"), b("ঙ্ক")
    )

    val shiftRow4 = listOf(
        b("১"), b("২"), b("৩"), b("৪"), b("৫"),
        b("৬"), b("৭"), b("৮"), b("৯"), b("০")
    )

    val shiftRow5 = listOf(
        b("?"), b("!"), b(","), b("."), b("-"),
        b("৳"), b("%"), b("(")
    )
}

object SymbolsLayout {
    private fun sym(ch: String) = KeyDef(ch, ch)

    val row1 = "1234567890".map { sym(it.toString()) }
    val row2 = "@#$%&*-+()".map { sym(it.toString()) }
    val row3 = "!\"':;/?,.".map { sym(it.toString()) }
    val row4 = listOf("=", "_", "<", ">", "[", "]", "{", "}", "৳", "€").map { sym(it) }
}

object EmojiList {
    val emojis = listOf(
        "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
        "😢", "😭", "😡", "😴", "🤔", "🙏", "👍", "👎",
        "👏", "🙌", "💪", "❤️", "💔", "🔥", "🎉", "🎊",
        "✨", "⭐", "🌙", "☀️", "🌧️", "🍎", "🍌", "🍚",
        "🍵", "☕", "🍗", "🐟", "🚗", "🏏", "⚽", "🏆",
        "📱", "💊", "🏪", "🕌", "🇧🇩", "🌸", "🎵", "📖"
    )
}
