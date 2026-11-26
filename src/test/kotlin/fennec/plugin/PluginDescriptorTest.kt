package fennec.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Headless checks that the shipped descriptor and the source tree agree.
 *
 * Air has no third-party composer action rendering contract in this build, so Fennec installs its
 * own compact composer bar. The descriptor must retain the Air dependency without registering
 * invisible actions or the removed side tool window.
 */
object PluginDescriptorTest {

    /** Interfaces whose implementations the platform can only reach through a descriptor entry. */
    private val EXTENSION_INTERFACES = listOf("Configurable", "ProjectActivity")

    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir") ?: ".").toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(descriptorOf(dir))) return dir
            dir = dir.parent
        }
        throw AssertionError("no src/main/resources/META-INF/plugin.xml at or above user.dir")
    }

    private fun descriptorOf(root: Path): Path =
        root.resolve("src/main/resources/META-INF/plugin.xml").normalize()

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** True when a top-level class in this file lists `iface` among its supertypes. */
    private fun implementsInterface(source: String, iface: String): Boolean {
        val header = Regex("""^class\s+\w+[^{]*""", RegexOption.MULTILINE).find(source) ?: return false
        return Regex("""[,:]\s*$iface\b""").containsMatchIn(header.value)
    }

    private fun topLevelClassName(source: String): String? =
        Regex("""^class\s+(\w+)""", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)

    private fun declaredPackage(source: String): String? =
        Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)

    fun run() {
        val root = repoRoot()
        val descriptor = Files.readString(descriptorOf(root))
        val sourcesRoot = root.resolve("src/main/kotlin")
        val sources = Files.walk(sourcesRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }

        TestMain.check("descriptor keeps Air integration without invisible actions") {
            TestMain.expect(
                Regex("""<depends>com\.intellij\.air</depends>""").containsMatchIn(descriptor),
                "missing Air module dependency",
            )
            TestMain.expect(!descriptor.contains("<actions>"), "obsolete composer actions are still registered")
            TestMain.expect(!descriptor.contains("<toolWindow"), "removed side tool window is still registered")
        }

        TestMain.check("composer control bar is shipped") {
            val bar = sourcesRoot.resolve("fennec/plugin/FennecComposerBar.kt")
            TestMain.expect(Files.isRegularFile(bar), "missing Air composer control bar")
            TestMain.expect(
                Files.readString(bar).contains("EditorTextField") &&
                    Files.readString(bar).contains("Describe the task for the agent"),
                "composer control bar does not identify Air's prompt field",
            )
            TestMain.expect(
                Files.readString(bar).contains("Download new model") &&
                    Files.readString(bar).contains("reasoningLevels"),
                "composer control bar is missing model download or reasoning levels",
            )
        }

        TestMain.check("every extension-point implementation is registered") {
            for (iface in EXTENSION_INTERFACES) {
                for (file in sources) {
                    val text = Files.readString(file)
                    if (!implementsInterface(text, iface)) continue
                    val pkg = declaredPackage(text)
                    val cls = topLevelClassName(text)
                    TestMain.expect(
                        descriptor.contains("\"$pkg.$cls\""),
                        "$iface $pkg.$cls is compiled but never registered in plugin.xml"
                    )
                }
            }
        }

        TestMain.check("every registered class exists in the source tree") {
            val refs = Regex("""\b(?:implementation|factoryClass|class)\s*=\s*"([^"]+)"""")
                .findAll(descriptor).map { it.groupValues[1] }.toList()
            TestMain.expect(refs.isNotEmpty(), "no implementation references parsed")
            for (qualified in refs) {
                val pkg = qualified.substringBeforeLast('.')
                val cls = qualified.substringAfterLast('.')
                val rel = qualified.replace('.', '/') + ".kt"
                var file = sourcesRoot.resolve(rel)
                if (!Files.isRegularFile(file)) {
                    file = sources.firstOrNull { candidate ->
                        val text = Files.readString(candidate)
                        declaredPackage(text) == pkg && Regex("""class\s+$cls\b""").containsMatchIn(text)
                    } ?: throw AssertionError("$qualified has no source file")
                }
                val text = Files.readString(file)
                TestMain.expect(
                    declaredPackage(text) == pkg,
                    "${file.fileName} does not declare package $pkg"
                )
                TestMain.expect(
                    Regex("""class\s+$cls\b""").containsMatchIn(text),
                    "${file.fileName} has no class $cls"
                )
            }
        }
    }
}
