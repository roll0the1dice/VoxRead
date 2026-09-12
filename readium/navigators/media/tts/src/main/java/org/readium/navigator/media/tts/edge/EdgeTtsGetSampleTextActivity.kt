/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Supplies a short sample sentence for the system TTS settings screen.
 */
@ExperimentalReadiumApi
public class EdgeTtsGetSampleTextActivity : Activity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent.getStringExtra("language")
            ?: intent.getStringExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT)
            ?: ""
        val sample = when {
            language.startsWith("zh", ignoreCase = true) ->
                "这是微软 Edge 在线语音合成引擎。"
            language.startsWith("ja", ignoreCase = true) ->
                "これは Microsoft Edge のオンライン音声合成エンジンです。"
            language.startsWith("ko", ignoreCase = true) ->
                "Microsoft Edge 온라인 음성 합성 엔진입니다."
            language.startsWith("fr", ignoreCase = true) ->
                "Voici le moteur de synthèse vocale en ligne Microsoft Edge."
            language.startsWith("de", ignoreCase = true) ->
                "Dies ist die Microsoft Edge Online-Sprachsynthese."
            language.startsWith("es", ignoreCase = true) ->
                "Este es el motor de síntesis de voz en línea de Microsoft Edge."
            else ->
                "This is the Microsoft Edge online text to speech engine."
        }
        setResult(
            RESULT_OK,
            Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        )
        finish()
    }
}
