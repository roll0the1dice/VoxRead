package com.roll0the1dice.voxread.desktop.tts

case class EdgeVoiceOption(
  shortName: String,
  displayName: String,
  description: String,
  gender: String
)

object EdgeTtsConstants:
  val TrustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
  val DefaultVoice = "zh-CN-XiaoxiaoNeural"
  val OutputFormat = "audio-24khz-48kbitrate-mono-mp3"

  // 1:1 对齐 Android 端 EdgeTtsVoices.kt 精选发音人列表
  val BuiltInVoices: Seq[EdgeVoiceOption] = Seq(
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

  val ChromiumFullVersion = "143.0.3650.75"
  val ChromiumMajorVersion = ChromiumFullVersion.substring(0, ChromiumFullVersion.indexOf('.'))
  val SecMsGecVersion = s"2-$ChromiumFullVersion"

  val BaseUrl = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
  val WssUrl = s"wss://$BaseUrl/edge/v1?TrustedClientToken=$TrustedClientToken"
  val VoiceListUrl = s"https://$BaseUrl/voices/list?trustedclienttoken=$TrustedClientToken"

  val Origin = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
  val UserAgent =
    s"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ChromiumMajorVersion.0.0.0 Safari/537.36 Edg/$ChromiumMajorVersion.0.0.0"
