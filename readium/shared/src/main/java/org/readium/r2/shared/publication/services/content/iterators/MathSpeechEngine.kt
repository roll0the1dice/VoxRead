package org.readium.r2.shared.publication.services.content.iterators

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileOutputStream

public class MathSpeechEngine private constructor(private val context: Context) {

    private val rulesDir: String by lazy {
        setupRulesDir(context)
    }
public suspend fun toSpeech(
        mathml: String,
        locale: String = "zh",
        outputSsml: Boolean = false
    ): String = withContext(Dispatchers.Default) {
        if (mathml.isBlank()) return@withContext ""

        if (!isLibraryLoaded) {
            Log.w(TAG, "🟡 [MathSpeech] .so 库未加载成功，直接走本地 LaTeX 降级")
            return@withContext fallbackSpeech(mathml)
        }

        try {
            // 🌟 修复点 1：确保拥有合法的 MathML 命名空间（MathCAT 严格要求）
            val normalizedXml = if (!mathml.contains("xmlns=")) {
                mathml.replaceFirst("<math", "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"")
            } else {
                mathml
            }

            //Log.d(TAG, "🔍 [MathSpeech] 传入 MathML: $normalizedXml")
            //Log.d(TAG, "🔍 [MathSpeech] rulesDir 路径: $rulesDir")

            // 调用 Native JNI
            val speech = mathCatToSpeech(normalizedXml, rulesDir)

            if (speech.isNotBlank()) {
                val simplified = speech
                    .replace("等於", "等于")
                    .replace("大於", "大于")
                    .replace("小於", "小于")
                    .replace("趨近於", "趋近于")
                    .replace("極限", "极限")
                    .replace("根號", "根号")
                    .replace("下標", "下标")
                    .replace("上標", "上标")
                
                //Log.d("EdgeTtsDebug", "🟢 [MathSpeech - MathCAT成功] '$simplified'")
                return@withContext simplified
            } else {
                Log.w(TAG, "🟡 [MathSpeech] MathCAT 返回空白文本，当前目录内容为: ${File(rulesDir).list()?.contentToString()}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [MathSpeech] JNI 调用异常: ${e.message}", e)
        }

        // 兜底降级
        val fallback = fallbackSpeech(mathml)
        //Log.d("EdgeTtsDebug", "🟡 [MathSpeech - 本地降级成功] '$fallback'")
        return@withContext fallback
    }

    private fun fallbackSpeech(mathml: String): String {
        return try {
            val doc = Jsoup.parseBodyFragment(mathml)
            val mathNode = doc.selectFirst("math") ?: return ""
            val annotation = mathNode.getElementsByTag("annotation").firstOrNull()
            val latex = annotation?.text()?.takeIf { it.isNotBlank() } ?: mathNode.toMathmlLatex()
            latexToChineseSpeech(latex)
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
        private external fun mathCatToSpeech(mathml: String, rulesDirPath: String): String

        @Volatile
        private var INSTANCE: MathSpeechEngine? = null

        public fun getInstance(context: Context): MathSpeechEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MathSpeechEngine(context.applicationContext).also { INSTANCE = it }
            }

        // 🌟 检查并保证完整解压规则目录
private fun setupRulesDir(context: Context): String {
            val targetDir = File(context.filesDir, "mathcat_rules")
            val languagesDir = File(targetDir, "Languages")
            
            // 🌟 必须保证 Languages 目录存在且包含内容才算成功，否则强制重新解压
            if (File(targetDir, "prefs.yaml").exists() && languagesDir.exists() && (languagesDir.list()?.isNotEmpty() == true)) {
                //Log.d(TAG, "MathCAT Rules 规则已就绪: ${targetDir.absolutePath}")
                return targetDir.absolutePath
            }

            try {
                //Log.i(TAG, "⚠️ 规则不完整，强制清空并重新解压到: ${targetDir.absolutePath}")
                targetDir.deleteRecursively()
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