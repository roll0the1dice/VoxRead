/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.readium.r2.shared.util.Language
import timber.log.Timber

public object EdgeTtsVoiceStore {

    public const val PARAM_SYSTEM_VOICE: String = "voiceName"
    public const val PARAM_EDGE_VOICE: String = "readium.edge.voice"

    private const val PREFS_NAME: String = "readium_edge_tts"
    private const val KEY_LAST: String = "voice.last"
    private const val KEY_PREFIX: String = "voice.lang."

    @Volatile
    private var requestedVoice: String? = null

    // 内存极速缓存，同进程内 0 延迟且绝不触发 Android 系统的 ContextImpl 存储报错
    private val memoryCache = ConcurrentHashMap<String, String>()

    public fun request(voiceName: String) {
        requestedVoice = EdgeTtsVoices.normalize(voiceName)?.shortName
        Timber.i("Edge TTS request voice=%s", requestedVoice)
    }

    public fun requested(): String? = requestedVoice

    public fun save(
        context: Context,
        voiceName: String,
        language: Language? = null,
        rememberAsLast: Boolean = true,
    ) {
        val official = EdgeTtsVoices.normalize(voiceName)?.shortName ?: return
        if (rememberAsLast) {
            request(official)
            memoryCache[KEY_LAST] = official
        }

        language?.let { memoryCache[keyFor(it)] = official }
        language?.removeRegion()?.let { memoryCache[keyFor(it)] = official }
        EdgeTtsVoices.find(official)?.let { entry ->
            memoryCache[keyFor(Language(entry.locale))] = official
            memoryCache[keyFor(Language(entry.locale).removeRegion())] = official
        }

        try {
            val editor = prefs(context).edit()
            if (rememberAsLast) {
                editor.putString(KEY_LAST, official)
            }
            language?.let { editor.putString(keyFor(it), official) }
            language?.removeRegion()?.let { editor.putString(keyFor(it), official) }
            EdgeTtsVoices.find(official)?.let { entry ->
                editor.putString(keyFor(Language(entry.locale)), official)
                editor.putString(keyFor(Language(entry.locale).removeRegion()), official)
            }
            editor.commit()
        } catch (e: Throwable) {
            Timber.w(e, "Edge TTS: 写入 SharedPreferences 失败")
        }

        Timber.i("Edge TTS persisted voice=%s last=%s", official, rememberAsLast)
    }

    public fun get(context: Context, locale: Locale): String? {
        return try {
            val language = Language(locale)
            officialOrNull(
                property(context, keyFor(language))
                    ?: property(context, keyFor(language.removeRegion()))
                    ?: last(context)
            )
        } catch (_: Throwable) {
            null
        }
    }

    public fun last(context: Context): String? =
        try {
            officialOrNull(property(context, KEY_LAST))
        } catch (_: Throwable) {
            null
        }

    private fun officialOrNull(raw: String?): String? =
        EdgeTtsVoices.normalize(raw)?.shortName

    private fun property(context: Context, key: String): String? {
        // 1. 优先走内存缓存读取（0 风险）
        memoryCache[key]?.let { return it }

        // 2. 内存没有则读 SharedPreferences（之前用 adb 验证过已经有 云扬 配置）
        return try {
            val value = prefs(context).getString(key, null)
            if (!value.isNullOrBlank()) {
                memoryCache[key] = value
                value
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun keyFor(language: Language): String =
        KEY_PREFIX + language.code.lowercase(Locale.ROOT)

    private fun prefs(context: Context): SharedPreferences {
        val target = context.applicationContext ?: context
        return target.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}