package com.roll0the1dice.voxread.desktop.tts

data class EdgeVoiceOption(
    val shortName: String,
    val displayName: String,
    val description: String,
    val gender: String
)

object EdgeTtsConstants {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
    const val DEFAULT_VOLUME = "+100%"
    const val DEFAULT_GAIN_FACTOR = 2.0
    const val DEFAULT_DRC_ENABLED = true
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    // 1:1 对齐 Android 端 EdgeTtsVoices.kt 精选发音人列表
    val BUILT_IN_VOICES = listOf(
        EdgeVoiceOption("zh-CN-XiaoxiaoNeural", "晓晓", "温柔自然女声 · 默认推荐", "女声"),
        EdgeVoiceOption("zh-CN-YunxiNeural", "云希", "阳光温暖男声 · 听书首选", "男声"),
        EdgeVoiceOption("zh-CN-XiaoyiNeural", "晓伊", "年轻活泼女声 · 轻快灵动", "女声"),
        EdgeVoiceOption("zh-CN-YunjianNeural", "云健", "沉稳磁性男声 · 纪实解说", "男声"),
        EdgeVoiceOption("zh-CN-YunyangNeural", "云扬", "专业播音男声 · 新闻资讯", "男声"),
        EdgeVoiceOption("zh-CN-YunxiaNeural", "云夏", "少年清朗男声 · 故事旁白", "男声"),
        EdgeVoiceOption("zh-CN-liaoning-XiaobeiNeural", "晓北", "东北辽宁口音 · 特色方言", "女声"),
        EdgeVoiceOption("zh-CN-shaanxi-XiaoniNeural", "晓妮", "陕西关中口音 · 特色方言", "女声"),
        EdgeVoiceOption("zh-TW-HsiaoChenNeural", "晓臻", "台湾甜美女声 · 国语发音", "女声"),
        EdgeVoiceOption("zh-TW-YunJheNeural", "云哲", "台湾温润男声 · 国语发音", "男声"),
        EdgeVoiceOption("zh-HK-HiuGaaiNeural", "晓佳", "香港粤语女声 · 地道粤语", "女声"),
        EdgeVoiceOption("zh-HK-WanLungNeural", "云龙", "香港粤语男声 · 地道粤语", "男声"),
        EdgeVoiceOption("en-US-AvaNeural", "Ava", "美式英语自然女声", "女声"),
        EdgeVoiceOption("en-US-AndrewNeural", "Andrew", "美式英语自然男声", "男声")
    )

    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private val CHROMIUM_MAJOR_VERSION = CHROMIUM_FULL_VERSION.substring(0, CHROMIUM_FULL_VERSION.indexOf('.'))
    val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    val WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
    val VOICE_LIST_URL = "https://$BASE_URL/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"

    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
}
