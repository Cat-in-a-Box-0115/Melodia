import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

// 界面多语言：不改上游源码，而是在打包前改写编译产物——
// 词典里有译文的中文常量在加载处补一次 I18n.t() 查表，字符串模板改走 I18n.concat 引导方法按译文重排。
// 上游新增的文案没有译文时保持中文，合并上游不会产生冲突
object MelodiaI18n {
    private const val RUNTIME = "com/lin0721/linmusic/desktop/i18n/I18n"
    private const val CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory"
    private const val CONCAT_DESC =
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"
    private const val LOGGER = "com/lin0721/linmusic/core/log/AppLogger"
    private val CJK = Regex("[\u3400-\u9fff]")

    // 紧跟比较、查找的常量是逻辑用值（when 分支、equals、startsWith 等），翻译会改变程序行为
    private val LOGIC_CALLS = setOf(
        "kotlin/jvm/internal/Intrinsics.areEqual",
        "java/lang/String.equals", "java/lang/String.startsWith", "java/lang/String.endsWith",
        "java/lang/String.contains", "java/lang/String.indexOf", "java/lang/String.lastIndexOf",
        "java/lang/String.replace", "java/lang/String.split",
        "kotlin/text/StringsKt.startsWith\$default", "kotlin/text/StringsKt.endsWith\$default",
        "kotlin/text/StringsKt.contains\$default", "kotlin/text/StringsKt.indexOf\$default",
        "kotlin/text/StringsKt.replace\$default", "kotlin/text/StringsKt.split\$default",
        "kotlin/text/StringsKt.removePrefix", "kotlin/text/StringsKt.removeSuffix",
        "kotlin/text/StringsKt.substringBefore\$default", "kotlin/text/StringsKt.substringAfter\$default",
        "kotlin/text/Regex.<init>"
    )

    // 数据层的中文常量可能发给接口、与接口返回比对或持久化（如分类“全部”、歌词“纯音乐”、默认名“未知歌手”），
    // 整类不改写；代价是这些类里少量报错文案保持中文
    private val DATA_LAYER = Regex(
        ".*(Repository|RepositoryImpl|Api|Request|Parser|Parsers|Matcher|Preferences|TagIo|Interceptor|Client|Store|Support|Models|Model|Prelude|CrashHandler|LyricsViewLogic)(Kt)?"
    )

    private fun isDataLayer(classEntry: String) =
        DATA_LAYER.matches(classEntry.substringAfterLast('/').substringBefore('$').removeSuffix(".class"))

    fun hasChinese(s: String) = CJK.containsMatchIn(s)

    // 模板以 {0}、{1} 依次标出插值位置；含 \u0002 引用常量的配方不处理
    fun recipeKey(recipe: String): String? {
        if ('\u0002' in recipe) return null
        var index = 0
        return buildString { recipe.forEach { if (it == '\u0001') append('{').append(index++).append('}') else append(it) } }
    }

    // 词典每行“中文<TAB>译文”，# 开头为注释；\n、\t、\\ 转义
    fun loadKeys(dictionary: File): Set<String> =
        dictionary.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }
            .map { unescape(it.substringBefore('\t')) }
            .toSet()

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n'); 't' -> append('\t'); '\\' -> append('\\'); else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c); i++
            }
        }
    }

    fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    // 常量之后遇到的第一个方法调用：中间可能夹着默认参数、类型转换等指令（如 contains(x, false) 的 iconst/aconst_null）
    private fun firstCallAfter(insn: AbstractInsnNode): AbstractInsnNode? {
        var n = insn.next
        var steps = 0
        while (n != null && steps < 16) {
            if (n is MethodInsnNode || n is InvokeDynamicInsnNode) return n
            if (n is LdcInsnNode || n.opcode in Opcodes.IFEQ..Opcodes.RETURN) return null
            n = n.next
            steps++
        }
        return null
    }

    private fun isLogicUse(insn: AbstractInsnNode): Boolean {
        val call = firstCallAfter(insn) as? MethodInsnNode ?: return false
        return "${call.owner}.${call.name}" in LOGIC_CALLS
    }

    // 常量之后遇到的第一个方法调用是日志输出，即为日志文案；日志保持中文，便于向上游反馈
    private fun isLogUse(insn: AbstractInsnNode): Boolean {
        var n = insn.next
        var steps = 0
        while (n != null && steps < 16) {
            if (n is MethodInsnNode) return n.owner == LOGGER
            if (n is InvokeDynamicInsnNode && n.bsm.owner != CONCAT_FACTORY) return false
            n = n.next
            steps++
        }
        return false
    }

    private fun isConcat(insn: InvokeDynamicInsnNode) =
        insn.bsm.owner == CONCAT_FACTORY && insn.bsm.name == "makeConcatWithConstants" && insn.bsmArgs.firstOrNull() is String

    private fun eachClass(jar: File, action: (name: String, bytes: ByteArray) -> ByteArray?): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        JarFile(jar).use { file ->
            for (entry in file.entries()) {
                if (entry.isDirectory) continue
                val bytes = file.getInputStream(entry).use { it.readBytes() }
                out[entry.name] = if (entry.name.endsWith(".class")) action(entry.name, bytes) ?: bytes else bytes
            }
        }
        return out
    }

    // 原地改写 jar；返回改写的常量与模板数量
    fun rewriteJar(jar: File, keys: Set<String>): Int {
        var count = 0
        val entries = eachClass(jar) { name, bytes ->
            if (name.startsWith(RUNTIME) || isDataLayer(name)) return@eachClass null
            val node = ClassNode()
            ClassReader(bytes).accept(node, 0)
            var changed = false
            for (method in node.methods) {
                for (insn in method.instructions.toArray()) {
                    if (insn is LdcInsnNode) {
                        val value = insn.cst as? String ?: continue
                        if (value !in keys || isLogicUse(insn)) continue
                        method.instructions.insert(
                            insn,
                            MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "t", "(Ljava/lang/String;)Ljava/lang/String;", false)
                        )
                        changed = true
                        count++
                    } else if (insn is InvokeDynamicInsnNode && isConcat(insn)) {
                        val key = recipeKey(insn.bsmArgs[0] as String) ?: continue
                        if (key !in keys) continue
                        insn.bsm = Handle(Opcodes.H_INVOKESTATIC, RUNTIME, "concat", CONCAT_DESC, false)
                        changed = true
                        count++
                    }
                }
            }
            if (!changed) return@eachClass null
            // 只在原常量后插入同类型的查表调用，栈深与栈帧均不变
            ClassWriter(0).also(node::accept).toByteArray()
        }
        val temp = File(jar.parentFile, jar.name + ".i18n")
        JarOutputStream(temp.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        if (!jar.delete() || !temp.renameTo(jar)) error("替换 ${jar.name} 失败")
        return count
    }

    // 列出 jar 里全部待译中文常量与模板，注明所在类以及是否日志、逻辑用途，供整理词典
    fun extract(jars: List<File>, output: File) {
        data class Use(val classes: MutableSet<String> = sortedSetOf(), var ui: Boolean = false, var log: Boolean = false, var logic: Boolean = false)
        val found = sortedMapOf<String, Use>()
        fun record(key: String, cls: String, insn: AbstractInsnNode, ldc: Boolean) {
            val use = found.getOrPut(key) { Use() }
            use.classes += cls.substringAfterLast('/').substringBefore('$')
            when {
                isLogUse(insn) -> use.log = true
                ldc && isLogicUse(insn) -> use.logic = true
                else -> use.ui = true
            }
        }
        jars.forEach { jar ->
            eachClass(jar) { name, bytes ->
                val node = ClassNode()
                ClassReader(bytes).accept(node, 0)
                node.methods.forEach { method: MethodNode ->
                    method.instructions.forEach { insn ->
                        if (insn is LdcInsnNode && insn.cst is String && hasChinese(insn.cst as String)) {
                            record(insn.cst as String, node.name, insn, ldc = true)
                        } else if (insn is InvokeDynamicInsnNode && isConcat(insn)) {
                            recipeKey(insn.bsmArgs[0] as String)?.takeIf(::hasChinese)?.let { record(it, node.name, insn, ldc = false) }
                        }
                    }
                }
                null
            }
        }
        output.parentFile.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { w ->
            found.forEach { (key, use) ->
                val flags = listOfNotNull("ui".takeIf { use.ui }, "log".takeIf { use.log }, "logic".takeIf { use.logic }).joinToString(",")
                w.write("${escape(key)}\t$flags\t${use.classes.take(4).joinToString(",")}\n")
            }
        }
    }
}
