package com.lin0721.linmusic.desktop.i18n

import java.io.File
import java.lang.invoke.CallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.StringConcatFactory
import java.util.Properties

// 打包时 buildSrc/MelodiaI18n.kt 把词典里的中文常量改写为 t() 调用、字符串模板改走 concat()。
// 语言在启动时读一次，切换后重启生效。本类不能依赖任何会被改写的类，否则初始化时会回调到自己
object I18n {
    enum class Language(val code: String) { Chinese("zh"), English("en") }

    private const val FILE_NAME = "language.properties"
    private const val KEY = "language"
    private const val DICTIONARY = "/i18n/en.tsv"

    private val settingsFile: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        File(File(base, "Melodia"), FILE_NAME)
    }

    private fun readSaved(): Language = runCatching {
        val props = Properties()
        settingsFile.takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use(props::load)
        Language.entries.firstOrNull { it.code == props.getProperty(KEY) }
    }.getOrNull() ?: Language.Chinese

    // 本次启动生效的语言
    val active: Language = readSaved()

    // 设置页里选中的语言，可能尚未生效
    @Volatile
    var selected: Language = active
        private set

    fun select(language: Language) {
        selected = language
        runCatching {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writer(Charsets.UTF_8).use { Properties().apply { setProperty(KEY, language.code) }.store(it, null) }
        }.onFailure { System.err.println("Melodia: failed to save language: $it") }
    }

    private val table: Map<String, String> =
        if (active == Language.Chinese) emptyMap() else runCatching { loadDictionary() }.getOrElse {
            System.err.println("Melodia: failed to load dictionary: $it")
            emptyMap()
        }

    private fun loadDictionary(): Map<String, String> {
        val stream = I18n::class.java.getResourceAsStream(DICTIONARY) ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
                .associate { unescape(it.substringBefore('\t')) to unescape(it.substringAfter('\t')) }
                .filterValues { it.isNotEmpty() }
        }
    }

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    @JvmStatic
    fun t(text: String): String = table[text] ?: text

    // 字符串模板的引导方法：配方 "共\u0001首" 记作 "共{0}首" 查词典，按译文里占位符的顺序重排参数
    @JvmStatic
    fun concat(
        lookup: MethodHandles.Lookup,
        name: String,
        type: MethodType,
        recipe: String,
        vararg constants: Any
    ): CallSite {
        val translated = translateRecipe(recipe, type.parameterCount())
            ?: return StringConcatFactory.makeConcatWithConstants(lookup, name, type, recipe, *constants)
        val (newRecipe, order) = translated
        val reorderedType = MethodType.methodType(String::class.java, order.map { type.parameterType(it) })
        val site = StringConcatFactory.makeConcatWithConstants(lookup, name, reorderedType, newRecipe, *constants)
        return java.lang.invoke.ConstantCallSite(MethodHandles.permuteArguments(site.target, type, *order.toIntArray()))
    }

    private val PLACEHOLDER = Regex("""\{(\d+)\}""")

    // 返回新配方与各插值位置对应的原参数下标；译文缺失或占位符对不上时返回 null
    private fun translateRecipe(recipe: String, argCount: Int): Pair<String, List<Int>>? {
        if (table.isEmpty() || '\u0002' in recipe) return null
        var index = 0
        val key = buildString { recipe.forEach { if (it == '\u0001') append('{').append(index++).append('}') else append(it) } }
        val english = table[key] ?: return null
        val order = mutableListOf<Int>()
        val newRecipe = PLACEHOLDER.replace(english.replace("\u0001", "")) { match ->
            order += match.groupValues[1].toInt()
            "\u0001"
        }
        if (index != argCount || order.any { it !in 0 until argCount } || order.toSet().size != argCount) return null
        return newRecipe to order
    }
}
