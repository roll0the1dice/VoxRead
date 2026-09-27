@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package org.readium.r2.shared.publication.services.content.iterators

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.readium.r2.shared.publication.services.content.MathSpeechBundle
import org.readium.r2.shared.publication.services.content.MathSpeechMarkup
import java.io.File
import java.io.FileOutputStream

public class MathSpeechEngine private constructor(private val context: Context) {

    private val rulesVersion: String by lazy {
        readRulesVersion(context)
    }

    private val rulesDir: String by lazy {
        setupRulesDir(context, rulesVersion)
    }

    private val bundles = LinkedHashMap<String, MathSpeechBundle>()
    private val byScope = HashMap<String, MathSpeechBundle>()

    /**
     * The formula and the speech from one MathCAT call. Later calls with the same
     * formula, language, and scope reuse that result, including its node ids.
     */
    public suspend fun toBundle(
        mathml: String,
        locale: String = "en",
        scopeId: String = "",
    ): MathSpeechBundle? = withContext(Dispatchers.Default) {
        if (mathml.isBlank() || !isLibraryLoaded) return@withContext null
        val normalizedXml = normalizeMathml(mathml)
        val key = locale + "\u0000" + rulesVersion + "\u0000" + normalizedXml.length + ":" + normalizedXml.hashCode()
        synchronized(bundles) { bundles[key] }?.let { return@withContext it }

        val built = try {
            speakFormula(normalizedXml, locale, depth = 0, idPrefix = "voxfb")
        } catch (e: Throwable) {
            Log.e(TAG, "MathCAT bundle failed: ${e.message}", e)
            structuredFormula(normalizedXml, locale, "voxfb")
        }

        synchronized(bundles) {
            val existing = bundles[key]
            if (existing != null) return@withContext existing
            bundles[key] = built
            if (scopeId.isNotBlank()) byScope[scopeId] = built
            while (bundles.size > 256) {
                val eldest = bundles.entries.firstOrNull()?.key ?: break
                bundles.remove(eldest)
            }
        }
        built
    }

    private fun speakFormula(mathml: String, locale: String, depth: Int, idPrefix: String): MathSpeechBundle {
        fromMathCat(mathml, locale)?.let { return it }
        val pieces = if (depth < 4) formulaPieces(mathml) else emptyList()
        if (pieces.size < 2) return structuredFormula(mathml, locale, idPrefix)
        val spoken = pieces.mapIndexed { index, piece ->
            speakFormula(piece, locale, depth + 1, "$idPrefix-$index")
        }
        return stitchFormula(mathml, spoken, locale)
    }

    private fun fromMathCat(mathml: String, locale: String): MathSpeechBundle? {
        val raw = mathCatBundle(mathml, rulesDir, toMathCatLanguage(locale))
        if (raw.isBlank()) return null
        val json = JSONObject(raw)
        val canonical = json.optString("canonical")
        val markup = json.optString("markup")
        if (json.optBoolean("ok", true).not() || canonical.isBlank() || markup.isBlank()) {
            Log.e(
                TAG,
                "MathCAT ${json.optString("stage", "speech")} ${json.optString("version")}: ${json.optString("error")}",
            )
            return null
        }
        var bundle = MathSpeechMarkup.align(markup, canonical)
            .copy(version = json.optString("version") + "|" + rulesVersion)
        if (prefersSimplifiedChinese(locale)) bundle = MathSpeechMarkup.simplify(bundle)
        return bundle.takeIf { it.spokenText.isNotBlank() && acceptableSpeech(it.spokenText, locale) }
    }

    private fun structuredFormula(mathml: String, locale: String, idPrefix: String): MathSpeechBundle {
        var bundle = MathSpeechMarkup.structure(mathml, idPrefix)
            .copy(version = "structured|$rulesVersion|$locale")
        if (prefersSimplifiedChinese(locale)) bundle = MathSpeechMarkup.simplify(bundle)
        return bundle
    }

    private fun formulaPieces(mathml: String): List<String> {
        val document = Jsoup.parse(mathml, "", org.jsoup.parser.Parser.xmlParser())
        val math = document.selectFirst("math") ?: return emptyList()
        val semantics = math.children().firstOrNull { it.normalName() == "semantics" }
        val inner = semantics?.children()?.firstOrNull { it.normalName() !in silentMathTags }
            ?: math.children().firstOrNull { it.normalName() !in silentMathTags }
            ?: return emptyList()
        val host = if (inner.normalName() == "mrow") inner else return emptyList()
        return host.children()
            .filter { it.normalName() !in silentMathTags }
            .map { child ->
                "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">${child.outerHtml()}</math>"
            }
    }

    private fun stitchFormula(
        mathml: String,
        parts: List<MathSpeechBundle>,
        locale: String,
    ): MathSpeechBundle {
        val document = Jsoup.parse(mathml, "", org.jsoup.parser.Parser.xmlParser())
        document.outputSettings()
            .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
            .prettyPrint(false)
        val math = document.selectFirst("math")
        val semantics = math?.children()?.firstOrNull { it.normalName() == "semantics" }
        val inner = semantics?.children()?.firstOrNull { it.normalName() !in silentMathTags }
        val host = if (inner?.normalName() == "mrow") inner else math
        val children = host?.children()?.filter { it.normalName() !in silentMathTags }.orEmpty()
        children.zip(parts).forEach { (child, part) ->
            val piece = Jsoup.parse(part.canonicalMathMl, "", org.jsoup.parser.Parser.xmlParser())
            val replacement = piece.selectFirst("math")?.children()?.firstOrNull() ?: return@forEach
            child.replaceWith(replacement.clone())
        }
        val text = StringBuilder()
        val anchors = mutableListOf<org.readium.r2.shared.publication.services.content.MathSpeechAnchor>()
        for (part in parts) {
            if (part.spokenText.isEmpty()) continue
            if (text.isNotEmpty()) text.append(' ')
            val base = text.length
            text.append(part.spokenText)
            anchors += part.anchors.map { anchor ->
                anchor.copy(start = base + anchor.start, end = base + anchor.end)
            }
        }
        val version = parts.map { it.version }.distinct().singleOrNull() ?: "mixed|$rulesVersion|$locale"
        return org.readium.r2.shared.publication.services.content.MathSpeechBundle(
            canonicalMathMl = math?.outerHtml().orEmpty(),
            spokenText = text.toString(),
            anchors = anchors,
            version = version,
        )
    }

    public fun cached(scopeId: String): MathSpeechBundle? {
        if (scopeId.isBlank()) return null
        return synchronized(byScope) { byScope[scopeId] }
    }

public suspend fun toSpeech(
        mathml: String,
        locale: String = "en",
        outputSsml: Boolean = false
    ): String = withContext(Dispatchers.Default) {
        if (mathml.isBlank()) return@withContext ""

        if (!isLibraryLoaded) {
            Log.w(TAG, "🟡 [MathSpeech] .so 库未加载成功，直接走本地 LaTeX 降级")
            return@withContext fallbackSpeech(mathml, locale)
        }

        try {
            // 🌟 修复点 1：确保拥有合法的 MathML 命名空间（MathCAT 严格要求）
            val normalizedXml = normalizeMathml(mathml)

            //Log.d(TAG, "🔍 [MathSpeech] 传入 MathML: $normalizedXml")
            //Log.d(TAG, "🔍 [MathSpeech] rulesDir 路径: $rulesDir")

            val mathCatLocale = toMathCatLanguage(locale)
            val speech = mathCatToSpeech(normalizedXml, rulesDir, mathCatLocale)

            if (speech.isNotBlank() && acceptableSpeech(speech, locale)) {
                val spoken = if (prefersSimplifiedChinese(locale)) simplifyChinese(speech) else speech
                return@withContext spoken
            } else if (speech.isNotBlank()) {
                Log.w(TAG, "MathCAT speech ignored because it does not match locale $locale")
            } else {
                Log.w(TAG, "🟡 [MathSpeech] MathCAT 返回空白文本，当前目录内容为: ${File(rulesDir).list()?.contentToString()}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [MathSpeech] JNI 调用异常: ${e.message}", e)
        }

        val fallback = fallbackSpeech(mathml, locale)
        return@withContext fallback
    }

    private fun fallbackSpeech(mathml: String, locale: String): String {
        return try {
            val doc = Jsoup.parseBodyFragment(mathml)
            val mathNode = doc.selectFirst("math") ?: return ""
            val annotation = mathNode.getElementsByTag("annotation").firstOrNull()
            val latex = annotation?.text()?.takeIf { it.isNotBlank() } ?: mathNode.toMathmlLatex()
            if (locale.startsWith("zh", ignoreCase = true)) {
                latexToChineseSpeech(latex)
            } else {
                latexToPlainSpeech(latex)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ fallbackSpeech 解析失败", e)
            ""
        }
    }

    public companion object {
        private const val TAG = "MathSpeechEngine"

        @Volatile
        public var isLibraryLoaded: Boolean = false
            private set

        init {
            try {
                // 🔍 打印运行环境诊断信息
                val is64 = Process.is64Bit()
                //Log.i(TAG, "🔍 [环境诊断] 当前进程是否为 64 位: $is64")
                //Log.i(TAG, "🔍 [环境诊断] 设备支持的 ABI 列表: ${Build.SUPPORTED_ABIS.contentToString()}")

                System.loadLibrary("mathcat_android")
                isLibraryLoaded = true
                //Log.i(TAG, "✅ [MathSpeech] libmathcat_android.so 加载成功！")
            } catch (e: UnsatisfiedLinkError) {
                isLibraryLoaded = false
                Log.e(TAG, "❌ [MathSpeech] 无法加载 libmathcat_android.so（架构不匹配或文件缺失）", e)
            } catch (t: Throwable) {
                isLibraryLoaded = false
                Log.e(TAG, "❌ [MathSpeech] 初始化时发生意外错误", t)
            }
        }

        // 外部 JNI 方法声明
        @JvmStatic
        private external fun mathCatToSpeech(
            mathml: String,
            rulesDirPath: String,
            locale: String,
        ): String

        @JvmStatic
        private external fun mathCatBundle(
            mathml: String,
            rulesDirPath: String,
            locale: String,
        ): String

        @Volatile
        private var INSTANCE: MathSpeechEngine? = null

        public fun getInstance(context: Context): MathSpeechEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MathSpeechEngine(context.applicationContext).also { INSTANCE = it }
            }

        // 🌟 检查并保证完整解压规则目录
private fun readRulesVersion(context: Context): String =
            try {
                context.assets.open("mathcat_rules/vox-rules-version.txt")
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()
                    .ifBlank { "unversioned" }
            } catch (_: Exception) {
                "unversioned"
            }

        private fun setupRulesDir(context: Context, version: String): String {
            val root = File(context.filesDir, "mathcat_rules")
            val targetDir = File(root, version)
            val languagesDir = File(targetDir, "Languages")
            val installed = File(targetDir, "vox-rules-version.txt")
            if (
                File(targetDir, "prefs.yaml").exists() &&
                languagesDir.exists() &&
                languagesDir.list()?.isNotEmpty() == true &&
                installed.exists() &&
                installed.readText().trim() == version
            ) {
                return targetDir.absolutePath
            }

            try {
                root.deleteRecursively()
                targetDir.mkdirs()

                val rootAssets = context.assets.list("")?.toList() ?: emptyList()
                //Log.d(TAG, "Assets 根目录清单: $rootAssets")

                // 自动寻找包含 rules 的 asset 目录
                val assetFolderName = when {
                    rootAssets.contains("mathcat_rules") -> "mathcat_rules"
                    rootAssets.contains("Rules") -> "Rules"
                    rootAssets.contains("rules") -> "rules"
                    else -> "mathcat_rules"
                }
                //Log.i(TAG, "从 Assets 目录 [$assetFolderName] 开始递归解压...")

                copyAssetFolder(context, assetFolderName, targetDir)
                
                //Log.i(TAG, "✅ MathCAT 解压完成！根目录文件: ${targetDir.list()?.contentToString()}")
                //Log.i(TAG, "✅ Languages 目录内容: ${languagesDir.list()?.contentToString()}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 解压 MathCAT 规则失败", e)
            }
            return targetDir.absolutePath
        }

        private fun copyAssetFolder(context: Context, srcPath: String, dstDir: File) {
            val assetManager = context.assets
            val list = assetManager.list(srcPath)

            if (list.isNullOrEmpty()) {
                // 如果是叶子文件，尝试读取并写入
                try {
                    assetManager.open(srcPath).use { input ->
                        if (!dstDir.parentFile.exists()) dstDir.parentFile.mkdirs()
                        FileOutputStream(dstDir).use { output ->
                            input.copyTo(output)
                        }
                    }
                    //Log.d(TAG, "成功解压文件: $srcPath")
                } catch (e: Exception) {
                    // 如果既不是文件又不是非空目录，则忽略
                }
            } else {
                // 是目录，递归深入
                if (!dstDir.exists()) dstDir.mkdirs()
                for (file in list) {
                    val nextSrc = if (srcPath.isEmpty()) file else "$srcPath/$file"
                    val nextDst = File(dstDir, file)
                    copyAssetFolder(context, nextSrc, nextDst)
                }
            }
        }
    }
}

internal fun toMathCatLanguage(locale: String): String {
    val tag = locale.lowercase(java.util.Locale.ROOT).replace('_', '-')
    val language = tag.substringBefore('-')
    return when (language) {
        "zh" -> "zh-tw"
        "" -> "en"
        else -> language
    }
}

internal fun prefersSimplifiedChinese(locale: String): Boolean {
    val tag = locale.lowercase(java.util.Locale.ROOT).replace('_', '-')
    if (!tag.startsWith("zh")) return false
    return !tag.contains("hant") &&
        !tag.contains("tw") &&
        !tag.contains("hk") &&
        !tag.contains("mo")
}

private fun simplifyChinese(speech: String): String = MathSpeechMarkup.simplifyText(speech)

private fun normalizeMathml(mathml: String): String =
    if (mathml.contains("xmlns=")) {
        mathml
    } else {
        mathml.replaceFirst("<math", "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"")
    }

private val silentMathTags = setOf(
    "annotation",
    "annotation-xml",
    "mspace",
    "malignmark",
    "maligngroup",
)

private fun acceptableSpeech(speech: String, locale: String): Boolean {
    if (locale.startsWith("zh", ignoreCase = true)) return true
    val letters = speech.count { it.isLetter() }
    if (letters == 0) return true
    val han = speech.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    return han * 2 < letters
}

internal fun latexToPlainSpeech(latex: String): String {
    var speech = latex
    while (speech.contains("\\frac") || speech.contains("frac")) {
        val replaced = speech
            .replace(Regex("""\\?frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}"""), " $1 over $2 ")
            .replace(Regex("""\\?frac\s*([^{}\s]+)\s*([^{}\s]+)"""), " $1 over $2 ")
        if (replaced == speech) break
        speech = replaced
    }
    return speech
        .replace(Regex("""(\w+)\^2"""), "$1 squared")
        .replace(Regex("""(\w+)\^3"""), "$1 cubed")
        .replace(Regex("""\^2"""), " squared")
        .replace(Regex("""\^3"""), " cubed")
        .replace(Regex("""\\sqrt\{([^}]+)\}"""), " square root of $1 ")
        .replace("\\sqrt", " square root of ")
        .replace(Regex("""_\{([^}]+)\}"""), " sub $1 ")
        .replace(Regex("""_(\w)"""), " sub $1 ")
        .replace("\\times", " times ")
        .replace("\\cdot", " times ")
        .replace("\\leq", " less than or equal to ")
        .replace("\\le", " less than or equal to ")
        .replace("\\geq", " greater than or equal to ")
        .replace("\\ge", " greater than or equal to ")
        .replace("\\neq", " not equal to ")
        .replace("\\infty", " infinity ")
        .replace("\\lim", " limit ")
        .replace("\\to", " to ")
        .replace("{", " ")
        .replace("}", " ")
        .replace("\\", "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

// 顶层降级函数
internal fun latexToChineseSpeech(latex: String): String {
    var s = latex
    while (s.contains("\\frac") || s.contains("frac")) {
        val replaced = s
            .replace(Regex("""\\?frac\s*\{([^{}]+)\}\s*\{([^{}]+)\}"""), " $2分之$1 ")
            .replace(Regex("""\\?frac\s*([^{}\s]+)\s*([^{}\s]+)"""), " $2分之$1 ")
        if (replaced == s) break
        s = replaced
    }

    return s
        .replace(Regex("""(\w+)\^2"""), "$1的平方")
        .replace(Regex("""(\w+)\^3"""), "$1的立方")
        .replace(Regex("""\^2"""), "的平方")
        .replace(Regex("""\^3"""), "的立方")
        .replace(Regex("""\\sqrt\{([^}]+)\}"""), "根号下$1")
        .replace("\\sqrt", "根号下")
        .replace(Regex("""_\{([^}]+)\}"""), "下标$1")
        .replace(Regex("""_(\w)"""), "下标$1")
        .replace("\\times", "乘以")
        .replace("\\cdot", "点乘")
        .replace("\\cdots", "等等")
        .replace("\\dots", "等等")
        .replace("⋯", "等等")
        .replace("…", "等等")
        .replace("\\div", "除以")
        .replace("\\pm", "正负")
        .replace("\\leq", "小于等于")
        .replace("\\le", "小于等于")
        .replace("≤", "小于等于")
        .replace("\\geq", "大于等于")
        .replace("\\ge", "大于等于")
        .replace("≥", "大于等于")
        .replace("\\neq", "不等于")
        .replace("≠", "不等于")
        .replace("\\approx", "约等于")
        .replace("≈", "约等于")
        .replace("\\infty", "无穷")
        .replace("∞", "无穷")
        .replace("\\lim", "极限")
        .replace("\\to", "趋近于")
        .replace("→", "趋近于")
        .replace("frac", "分之")
        .replace("{", "")
        .replace("}", "")
        .replace("\\", "")
        .trim()
}

internal fun Element.toMathmlLatex(): String {
    return when (normalName()) {
        "math", "semantics" -> children().joinToString(" ") { it.toMathmlLatex() }
        "mfrac" -> "\\frac{${children().getOrNull(0)?.toMathmlLatex().orEmpty()}}{${children().getOrNull(1)?.toMathmlLatex().orEmpty()}}"
        "msup" -> "${children().getOrNull(0)?.toMathmlLatex().orEmpty()}^{${children().getOrNull(1)?.toMathmlLatex().orEmpty()}}"
        "msub" -> "${children().getOrNull(0)?.toMathmlLatex().orEmpty()}_{${children().getOrNull(1)?.toMathmlLatex().orEmpty()}}"
        "msubsup", "munderover" -> "${children().getOrNull(0)?.toMathmlLatex().orEmpty()}_{${children().getOrNull(1)?.toMathmlLatex().orEmpty()}}^{${children().getOrNull(2)?.toMathmlLatex().orEmpty()}}"
        "msqrt" -> "\\sqrt{${children().joinToString(" ") { it.toMathmlLatex() }}}"
        "mi", "mn", "mo", "mtext" -> text()
        else -> children().joinToString(" ") { it.toMathmlLatex() }.ifBlank { text() }
    }
}