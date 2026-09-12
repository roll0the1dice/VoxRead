/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Built-in catalog of Microsoft Edge neural voices.
 *
 * The service also tries to refresh this list from the public voices endpoint
 * so newly published voices show up without a toolkit update.
 */
public object EdgeTtsVoices {

    public data class Entry(
        val shortName: String,
        val locale: Locale,
        val female: Boolean,
    ) {
        public val language: String
            get() = locale.language

        public val country: String
            get() = locale.country
    }

    public val all: List<Entry>
        get() = remoteOverride ?: builtIn

    public val locales: List<Locale>
        get() = all.map { it.locale }.distinct()

    @Volatile
    internal var remoteOverride: List<Entry>? = null

    public fun defaultFor(locale: Locale): Entry {
        val exact = all.firstOrNull {
            it.locale.language == locale.language &&
                it.locale.country.equals(locale.country, ignoreCase = true)
        }
        val language = all.firstOrNull { it.locale.language == locale.language }
        return exact ?: language ?: all.first { it.shortName == EdgeTtsConstants.DEFAULT_VOICE }
    }

    public fun find(shortName: String?): Entry? =
        shortName?.takeIf { it.isNotBlank() }?.let { name ->
            all.firstOrNull { it.shortName == name }
        }

    /**
     * Maps a UI label, locale-like token, or messy identifier onto the official
     * Microsoft ShortName (`zh-CN-YunxiNeural`). Anything else is rejected so
     * the cloud does not silently fall back to Xiaoxiao.
     */
    public fun normalize(raw: String?): Entry? {
        val cleaned = sanitizeIdentifier(raw) ?: return null
        find(cleaned)?.let { return it }

        all.firstOrNull { it.shortName.equals(cleaned, ignoreCase = true) }?.let { return it }

        aliases[cleaned.lowercase(Locale.ROOT)]?.let { official ->
            find(official)?.let { return it }
        }

        val contained = all
            .filter { cleaned.contains(it.shortName, ignoreCase = true) }
            .maxByOrNull { it.shortName.length }
        if (contained != null) {
            return contained
        }

        val withoutSuffix = stripNeuralSuffix(cleaned)
        val restored = listOf(
            "${withoutSuffix}Neural",
            "${withoutSuffix}MultilingualNeural"
        )
        for (candidate in restored) {
            all.firstOrNull { it.shortName.equals(candidate, ignoreCase = true) }?.let { return it }
        }

        val person = personToken(cleaned)
        if (person.length >= 2) {
            val matches = all.filter { personToken(it.shortName) == person }
            pickPreferred(matches)?.let { return it }
        }
        return null
    }

    public fun xmlLang(entry: Entry): String {
        val language = entry.locale.language
        val country = entry.locale.country
        return if (country.isBlank()) language else "$language-$country"
    }

    public fun xmlLangFromName(voiceName: String): String {
        val parts = sanitizeIdentifier(voiceName)?.split('-').orEmpty()
        val language = parts.getOrNull(0).orEmpty()
        val country = parts.getOrNull(1)
        return if (
            language.isNotBlank() &&
            country != null &&
            country.length == 2 &&
            country.all { it.isLetter() }
        ) {
            "$language-$country"
        } else {
            "en-US"
        }
    }

    internal fun sanitizeIdentifier(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val compact = buildString(raw.length) {
            for (char in raw.trim()) {
                when {
                    char == '_' -> append('-')
                    char.isWhitespace() -> Unit
                    else -> append(char)
                }
            }
        }
        return compact.takeIf { it.isNotEmpty() }
    }

    private fun stripNeuralSuffix(name: String): String {
        var value = name
        if (value.endsWith("Neural", ignoreCase = true)) {
            value = value.dropLast("Neural".length)
        }
        if (value.endsWith("Multilingual", ignoreCase = true)) {
            value = value.dropLast("Multilingual".length)
        }
        return value.trimEnd('-')
    }

    private fun personToken(name: String): String =
        stripNeuralSuffix(name)
            .substringAfterLast('-')
            .lowercase(Locale.ROOT)

    private fun pickPreferred(matches: List<Entry>): Entry? =
        matches.firstOrNull { !it.shortName.contains("Multilingual") }
            ?: matches.firstOrNull()

    private val aliases: Map<String, String> = mapOf(
        "晓晓" to "zh-CN-XiaoxiaoNeural",
        "xiaoxiao" to "zh-CN-XiaoxiaoNeural",
        "晓伊" to "zh-CN-XiaoyiNeural",
        "云希" to "zh-CN-YunxiNeural",
        "yunxi" to "zh-CN-YunxiNeural",
        "云健" to "zh-CN-YunjianNeural",
        "yunjian" to "zh-CN-YunjianNeural",
        "云扬" to "zh-CN-YunyangNeural",
        "yunyang" to "zh-CN-YunyangNeural"
    )

    public fun availability(lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrBlank()) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        val languageMatches = all.filter { it.locale.language.equals(lang, ignoreCase = true) }
        if (languageMatches.isEmpty()) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (country.isNullOrBlank()) {
            return TextToSpeech.LANG_AVAILABLE
        }
        val countryMatches = languageMatches.filter {
            it.locale.country.equals(country, ignoreCase = true)
        }
        if (countryMatches.isEmpty()) {
            return TextToSpeech.LANG_AVAILABLE
        }
        if (variant.isNullOrBlank()) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
        return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    public fun toAndroidVoice(entry: Entry): Voice =
        Voice(
            entry.shortName,
            entry.locale,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            true,
            emptySet()
        )

internal val builtIn: List<Entry> = listOf(
        // ================= Chinese (全部亲测有效，绝对有独特声音) =================
        // 核心普通话 - 女声
        entry("zh-CN-XiaoxiaoNeural", female = true),     // 晓晓 (温柔女声)
        entry("zh-CN-XiaoyiNeural", female = true),       // 晓伊 (年轻活泼女声)
        
        // 核心普通话 - 男声 (切这些声音变化极其巨大)
        entry("zh-CN-YunjianNeural", female = false),     // 云健 (影视解说沉稳男声)
        entry("zh-CN-YunxiNeural", female = false),       // 云希 (阳光男声，听书首选)
        entry("zh-CN-YunyangNeural", female = false),     // 云扬 (专业新闻播音男声)
        entry("zh-CN-YunxiaNeural", female = false),      // 云夏 (活泼少年/男孩声)
        
        // 地方特色方言 (听感极具特色)
        entry("zh-CN-liaoning-XiaobeiNeural", female = true), // 晓北 (东北辽宁老铁口音)
        entry("zh-CN-shaanxi-XiaoniNeural", female = true),   // 晓妮 (陕西关中方言口音)

        // 港台声音
        entry("zh-TW-HsiaoChenNeural", female = true),    // 晓臻 (台湾女声)
        entry("zh-TW-HsiaoYuNeural", female = true),      // 晓雨 (台湾女声)
        entry("zh-TW-YunJheNeural", female = false),      // 云哲 (台湾男声)
        entry("zh-HK-HiuGaaiNeural", female = true),      // 晓佳 (粤语女声)
        entry("zh-HK-HiuMaanNeural", female = true),      // 晓曼 (粤语女声)
        entry("zh-HK-WanLungNeural", female = false),     // 云龙 (粤语男声)

        // ================= English =================
        entry("en-US-AvaNeural", female = true),
        entry("en-US-AndrewNeural", female = false),
        entry("en-US-EmmaNeural", female = true),
        entry("en-US-BrianNeural", female = false),
        entry("en-US-AriaNeural", female = true),
        entry("en-US-JennyNeural", female = true),
        entry("en-US-GuyNeural", female = false),
        entry("en-US-ChristopherNeural", female = false),
        entry("en-US-EricNeural", female = false),
        entry("en-US-MichelleNeural", female = true),
        entry("en-US-RogerNeural", female = false),
        entry("en-US-SteffanNeural", female = false),
        entry("en-GB-SoniaNeural", female = true),
        entry("en-GB-RyanNeural", female = false),
        entry("en-GB-LibbyNeural", female = true),
        entry("en-AU-NatashaNeural", female = true),
        entry("en-AU-WilliamNeural", female = false),
        entry("en-CA-ClaraNeural", female = true),
        entry("en-CA-LiamNeural", female = false),
        entry("en-IN-NeerjaNeural", female = true),
        entry("en-IN-PrabhatNeural", female = false),

        // ================= Japanese / Korean =================
        entry("ja-JP-NanamiNeural", female = true),
        entry("ja-JP-KeitaNeural", female = false),
        entry("ko-KR-SunHiNeural", female = true),
        entry("ko-KR-InJoonNeural", female = false),

        // ================= European =================
        entry("fr-FR-DeniseNeural", female = true),
        entry("fr-FR-HenriNeural", female = false),
        entry("de-DE-KatjaNeural", female = true),
        entry("de-DE-ConradNeural", female = false),
        entry("es-ES-ElviraNeural", female = true),
        entry("es-ES-AlvaroNeural", female = false),
        entry("es-MX-DaliaNeural", female = true),
        entry("es-MX-JorgeNeural", female = false),
        entry("it-IT-ElsaNeural", female = true),
        entry("it-IT-DiegoNeural", female = false),
        entry("pt-BR-FranciscaNeural", female = true),
        entry("pt-BR-AntonioNeural", female = false),
        entry("pt-PT-RaquelNeural", female = true),
        entry("ru-RU-SvetlanaNeural", female = true),
        entry("ru-RU-DmitryNeural", female = false),
        entry("nl-NL-ColetteNeural", female = true),
        entry("pl-PL-AgnieszkaNeural", female = true),
        entry("sv-SE-SofieNeural", female = true),
        entry("da-DK-ChristelNeural", female = true),
        entry("fi-FI-SelmaNeural", female = true),
        entry("nb-NO-PernilleNeural", female = true),
        entry("tr-TR-AhmetNeural", female = false),
        entry("tr-TR-EmelNeural", female = true),

        // ================= Other =================
        entry("ar-SA-ZariyahNeural", female = true),
        entry("ar-EG-SalmaNeural", female = true),
        entry("hi-IN-SwaraNeural", female = true),
        entry("hi-IN-MadhurNeural", female = false),
        entry("th-TH-PremwadeeNeural", female = true),
        entry("vi-VN-HoaiMyNeural", female = true),
        entry("vi-VN-NamMinhNeural", female = false),
        entry("id-ID-GadisNeural", female = true),
        entry("ms-MY-YasminNeural", female = true)
    )

    internal fun parseShortName(shortName: String): Locale {
        val parts = shortName.split('-')
        val language = parts.getOrNull(0).orEmpty()
        val country = parts.getOrNull(1)
            ?.takeIf { part -> part.length == 2 && part.all { it.isLetter() } }
            .orEmpty()
        return if (country.isEmpty()) Locale(language) else Locale(language, country)
    }

    private fun entry(shortName: String, female: Boolean): Entry =
        Entry(shortName, parseShortName(shortName), female)
}
