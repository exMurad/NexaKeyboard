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
    val shiftIsPreBase: Boolean = false
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

    private fun letter(ch: String) =
        KeyDef(ch, ch, ch.uppercase(), ch.uppercase())

    val row1 = "qwertyuiop".map { letter(it.toString()) }
    val row2 = "asdfghjkl".map { letter(it.toString()) }
    val row3 = "zxcvbnm".map { letter(it.toString()) }
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
