/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import timber.log.Timber

/**
 * Optionally refreshes [EdgeTtsVoices] from Microsoft's public voice list.
 */
internal object EdgeTtsVoiceCatalog {

    private val started = AtomicBoolean(false)

    fun refreshAsync() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        val url = EdgeTtsConstants.VOICE_LIST_URL +
            "&Sec-MS-GEC=${EdgeTtsDrm.generateSecMsGec()}" +
            "&Sec-MS-GEC-Version=${EdgeTtsConstants.SEC_MS_GEC_VERSION}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", EdgeTtsConstants.USER_AGENT)
            .header("Accept", "*/*")
            .header("Authority", "speech.platform.bing.com")
            .header("Cookie", "muid=${EdgeTtsClient.connectId().uppercase()};")
            .build()
        EdgeTtsSynthesizer.sharedHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.w(e, "Failed to refresh Edge TTS voices")
                started.set(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { bodyResponse ->
                    if (bodyResponse.code == 403) {
                        EdgeTtsDrm.adjustClockFromHttpDate(bodyResponse.header("Date"))
                        started.set(false)
                        return
                    }
                    if (!bodyResponse.isSuccessful) {
                        Timber.w("Edge TTS voice list HTTP ${bodyResponse.code}")
                        return
                    }
                    val payload = bodyResponse.body?.string().orEmpty()
                    val parsed = parse(payload)
                    if (parsed.isNotEmpty()) {
                        EdgeTtsVoices.remoteOverride = parsed
                    }
                }
            }
        })
    }

    internal fun parse(json: String): List<EdgeTtsVoices.Entry> {
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val shortName = item.optString("ShortName")
                    if (shortName.isBlank()) {
                        continue
                    }
                    val localeTag = item.optString("Locale")
                    val locale = if (localeTag.isNotBlank()) {
                        val parts = localeTag.split('-', '_')
                        if (parts.size >= 2) {
                            java.util.Locale(parts[0], parts[1])
                        } else {
                            java.util.Locale(parts[0])
                        }
                    } else {
                        EdgeTtsVoices.parseShortName(shortName)
                    }
                    val female = !item.optString("Gender").equals("Male", ignoreCase = true)
                    add(EdgeTtsVoices.Entry(shortName, locale, female))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Edge TTS voice list")
            emptyList()
        }
    }
}
