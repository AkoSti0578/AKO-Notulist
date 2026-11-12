package nl.arnoldkolkman.akonotulist.core

/**
 * Cheap, offline check whether a transcript came back in Dutch or (unwanted) in English.
 * The gpt-4o transcription models sometimes return an English translation of Dutch speech even
 * with language=nl; we detect that per chunk and correct it.
 *
 * Counts very frequent function words that only exist in one of the two languages. Words that
 * are spelled the same in both (in, is, we, …) are deliberately left out.
 */
object LanguageGuess {
    private val dutch = setOf(
        "de", "het", "een", "en", "van", "ik", "je", "jij", "dat", "die", "niet", "op", "te", "met", "voor",
        "zijn", "er", "maar", "ook", "als", "wat", "wij", "naar", "dan", "nog", "wel", "kan", "moet", "hebben",
        "heb", "hij", "zij", "ze", "dit", "om", "bij", "uit", "aan", "of", "al", "nu", "gaan", "worden", "deze",
        "mijn", "ons", "jullie", "wordt", "geen", "hoe", "waar", "zo", "want", "omdat", "heeft", "hebt", "ja",
        "nee", "even", "toch", "gewoon", "eigenlijk", "dus", "zou", "kunnen", "willen", "wil", "zeg", "weet",
    )
    private val english = setOf(
        "the", "and", "of", "to", "a", "it", "you", "that", "he", "was", "for", "on", "are", "with", "they",
        "be", "at", "have", "this", "from", "or", "had", "by", "but", "not", "what", "can", "will", "would",
        "there", "so", "if", "about", "which", "do", "does", "my", "your", "our", "just", "get", "know", "think",
        "going", "yes", "no", "i", "is", "were", "been", "should", "could", "really", "because", "also", "then",
    ) - dutch
    private const val MIN_HITS = 12

    enum class Lang { DUTCH, ENGLISH, UNKNOWN }

    fun guess(text: String): Lang {
        var nl = 0
        var en = 0
        Regex("[\\p{L}']+").findAll(text.lowercase()).forEach { m ->
            val w = m.value.trim('\'')
            if (w in dutch) nl++
            if (w in english) en++
        }
        if (nl + en < MIN_HITS) return Lang.UNKNOWN
        return when {
            en > nl * 2 -> Lang.ENGLISH
            nl > en * 2 -> Lang.DUTCH
            else -> Lang.UNKNOWN // mixed: leave it alone
        }
    }

    /** True when [text] is clearly English while [targetLanguage] is Dutch. */
    fun isWrongLanguage(text: String, targetLanguage: String?): Boolean =
        targetLanguage?.lowercase()?.startsWith("nl") == true && guess(text) == Lang.ENGLISH
}
