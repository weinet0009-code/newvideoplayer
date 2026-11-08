package com.weinet.newvideoplayer.tools

import java.io.File

/** A generated subtitle file and the language it is in ("zh", "en"; "und" when unknown). */
data class AiResult(val file: File, val language: String)

/** Whisper / Groq language names ("chinese") to ISO codes ("zh"). */
object LangCodes {
    private val names = mapOf(
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "yue", "english" to "en", "japanese" to "ja",
        "korean" to "ko", "french" to "fr", "german" to "de", "spanish" to "es", "italian" to "it",
        "portuguese" to "pt", "russian" to "ru", "thai" to "th", "vietnamese" to "vi", "indonesian" to "id",
        "malay" to "ms", "arabic" to "ar", "hindi" to "hi", "turkish" to "tr", "dutch" to "nl",
    )

    fun normalize(raw: String?): String {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return "und"
        names[s]?.let { return it }
        return if (s.length in 2..3 && s.all { it.isLetter() }) s else "und"
    }

    /** Prompt nudging Whisper towards Traditional characters for Chinese. */
    fun prompt(lang: String?): String? = when (lang) {
        "zh" -> "以下是繁體中文的字幕。"
        "yue" -> "以下是粵語的繁體中文字幕。"
        else -> null
    }

    /** Whisper still writes some Simplified characters; convert Chinese output to Traditional (Android 10+). */
    fun toTraditional(lang: String?, segs: List<Segment>): List<Segment> {
        if ((lang != "zh" && lang != "yue") || android.os.Build.VERSION.SDK_INT < 29) return segs
        val tr = runCatching { android.icu.text.Transliterator.getInstance("Simplified-Traditional") }.getOrNull() ?: return segs
        return segs.map { it.copy(text = tr.transliterate(it.text)) }
    }
}
