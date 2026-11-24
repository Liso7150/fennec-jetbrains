package fennec.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Headless checks that the shipped descriptor and the source tree agree.
 *
 * The tool window regression this guards against is invisible to every other test here: a
 * factory class can compile, be named by the README and the plugin description, and still
 * never run, because nothing in the Kotlin sources asks the platform to instantiate it — only
 * plugin.xml does. So the two are compared directly, in both directions.
 */
object PluginDescriptorTest {

    /** Interfaces whose implementations the platform can only reach through a descriptor entry. */
    private val EXTENSION_INTERFACES = listOf("ToolWindowFactory", "Configurable", "ProjectActivity")

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

    private fun toolWindowTags(text: String): List<String> =
        Regex("""<toolWindow\b[^>]*>""").findAll(text).map { it.value }.toList()

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

        TestMain.check("descriptor registers the Fennec tool window") {
            val tags = toolWindowTags(descriptor)
            TestMain.expectEquals(1, tags.size, "toolWindow declarations")
            TestMain.expectEquals("Fennec", attr(tags[0], "id"), "tool window id")
            TestMain.expectEquals("right", attr(tags[0], "anchor"), "tool window anchor")
        }

        TestMain.check("tool window icon resolves inside resources") {
            val tag = toolWindowTags(descriptor).firstOrNull() ?: return@check
            val icon = attr(tag, "icon")
            TestMain.expect(icon != null, "toolWindow declares no icon")
            val file = root.resolve("src/main/resources").resolve(icon!!.trimStart('/'))
            TestMain.expect(Files.isRegularFile(file), "missing icon ${file.fileName}")
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
            val refs = Regex("""\b(?:implementation|factoryClass)\s*=\s*"([^"]+)"""")
                .findAll(descriptor).map { it.groupValues[1] }.toList()
            TestMain.expect(refs.isNotEmpty(), "no implementation references parsed")
            for (qualified in refs) {
                val rel = qualified.replace('.', '/') + ".kt"
                val file = sourcesRoot.resolve(rel)
                TestMain.expect(Files.isRegularFile(file), "$qualified has no $rel")
                val text = Files.readString(file)
                TestMain.expect(
                    declaredPackage(text) == qualified.substringBeforeLast('.'),
                    "$rel does not declare package ${qualified.substringBeforeLast('.')}"
                )
                TestMain.expect(
                    Regex("""class\s+${qualified.substringAfterLast('.')}""").containsMatchIn(text),
                    "$rel has no class ${qualified.substringAfterLast('.')}"
                )
            }
        }
    }
}
