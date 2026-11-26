package com.murad.nexakeyboard

/**
 * Small built-in suggestion lists for the first version.
 * A later version can grow these into a real dictionary.
 */
object SuggestionEngine {

    private val banglaWords = listOf(
        "আমি", "আমরা", "তুমি", "তোমার", "আমার", "ভালো", "ভালোবাসা",
        "ধন্যবাদ", "আসসালামু আলাইকুম", "কেমন", "আছো", "আছি", "আজ",
        "কাল", "দোকান", "টাকা", "কাজ", "খবর", "বাড়ি", "মা", "বাবা",
        "সাকিন", "মুরাদ", "বাংলা", "বাংলাদেশ", "ঢাকা", "ওষুধ",
        "ফার্মেসি", "ছয়গাঁও", "আসবো", "যাবো", "করবো", "খাবো",
        "ঘুম", "সকাল", "রাত", "দুপুর", "বন্ধু", "পরিবার", "সুখী",
        "সুস্থ", "আল্লাহ", "ইনশাআল্লাহ", "ঠিক", "আছে", "নেই", "কী",
        "কেন", "কোথায়", "কখন", "কিভাবে", "অনেক", "একটু", "আরও",
        "নতুন", "পুরনো", "বড়", "ছোট"
    )

    private val englishWords = listOf(
        "hello", "thanks", "thank", "you", "good", "morning",
        "please", "ok", "okay", "yes", "no", "today", "tomorrow",
        "shop", "money", "work", "home", "family", "love", "happy",
        "night", "welcome", "sorry", "how", "are", "doing", "well",
        "fine", "great", "nice", "see", "later", "bye", "take",
        "care", "am", "is", "the", "and", "for", "with", "this",
        "that", "have", "will", "can", "from", "about", "what",
        "when", "where", "why", "much", "more", "new", "time",
        "day", "life"
    )

    fun suggest(word: String, bangla: Boolean): List<String> {
        if (word.isEmpty()) return emptyList()
        val pool = if (bangla) banglaWords else englishWords
        return pool.filter { it.startsWith(word) && it != word }.take(3)
    }
}
