package org.qosp.notes.transcribe

/**
 * Key recognition ported from TTT's MaKeys. Classifies each whole token, so a
 * `gsk_...` key is Groq and never also a stray `sk_...` ElevenLabs match. Real key
 * shapes only; prose, arrows and short fragments return nothing. A long, key-like
 * token of an unknown provider is kept as "unknown" rather than discarded, so a
 * rebranded key (e.g. Google's new AQ. form) is never thrown away.
 */
object KeyParser {

    private val HEX32 = Regex("[0-9a-fA-F]{32}")
    private val GEMINI = Regex("(AQ\\.[0-9A-Za-z._-]{20,}|AIza[0-9A-Za-z_-]{20,})")
    private val ANTHROPIC = Regex("sk-ant-[0-9A-Za-z_-]{20,}")
    private val OPENAI = Regex("sk-(?!ant-)[0-9A-Za-z_-]{20,}")
    private val GROQ = Regex("gsk_[0-9A-Za-z_-]{20,}")
    private val ELEVEN = Regex("sk_[0-9A-Za-z_-]{16,}")
    private val LOOSE = Regex("[A-Za-z0-9._-]{24,120}")

    // Split on everything that cannot be inside a key. Dots are kept (AQ. keys need them).
    private val SEP = Regex("[\\s,;:\"'=|\\[\\](){}<>]+")

    data class Found(val key: String, val providerId: String)

    private fun classify(token: String): String? = when {
        ANTHROPIC.matches(token) -> "anthropic"
        GEMINI.matches(token) -> "gemini"
        GROQ.matches(token) -> "groq"
        ELEVEN.matches(token) -> "elevenlabs"
        OPENAI.matches(token) -> "openai"
        HEX32.matches(token) -> "assemblyai"
        LOOSE.matches(token) && token.any { it.isDigit() } && token.any { it.isLetter() } -> "unknown"
        else -> null
    }

    fun extract(text: String): List<Found> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.split(SEP)) {
            val t = raw.trim().trim('.', '-', '_')
            if (t.isEmpty() || t == "DELETED" || out.containsKey(t)) continue
            val id = classify(t) ?: continue
            out[t] = id
        }
        return out.entries.map { Found(it.key, it.value) }
    }
}
