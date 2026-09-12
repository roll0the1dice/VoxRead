/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

/**
 * Public Microsoft Edge Read Aloud endpoints and browser fingerprints.
 *
 * Values follow the community `edge-tts` client so the handshake is accepted
 * by the same cloud frontend.
 */
internal object EdgeTtsConstants {

    const val TRUSTED_CLIENT_TOKEN: String = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    const val DEFAULT_VOICE: String = "en-US-EmmaMultilingualNeural"

    const val OUTPUT_FORMAT: String = "audio-24khz-48kbitrate-mono-mp3"

    const val SAMPLE_RATE_HZ: Int = 24_000

    const val CHANNEL_COUNT: Int = 1

    const val MAX_SSML_BYTES: Int = 4096

    private const val BASE_PATH: String =
        "speech.platform.bing.com/consumer/speech/synthesize/readaloud"

    const val WSS_URL: String =
        "wss://$BASE_PATH/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

    const val VOICE_LIST_URL: String =
        "https://$BASE_PATH/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"

    const val CHROMIUM_FULL_VERSION: String = "143.0.3650.75"

    val CHROMIUM_MAJOR_VERSION: String =
        CHROMIUM_FULL_VERSION.substringBefore('.')

    val SEC_MS_GEC_VERSION: String =
        "1-$CHROMIUM_FULL_VERSION"

    val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
            "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    const val ORIGIN: String =
        "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
}
