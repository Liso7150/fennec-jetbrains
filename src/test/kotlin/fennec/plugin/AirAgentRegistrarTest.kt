package fennec.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Headless checks for Air registration: file merges, overlay, vmoptions, idempotency, legacy cleanup. */
object AirAgentRegistrarTest {

    private class Fixture {
        val home: Path = Files.createTempDirectory("fennec-home")
        val configDir: Path = Files.createTempDirectory("fennec-config")
        val commonDataDir: Path = Files.createTempDirectory("fennec-common")
        val systemDir: Path = Files.createTempDirectory("fennec-system")
        val sidecar: ByteArray = "fake-sidecar-bytes-1.0.0".toByteArray()

        val launcher: Path = SidecarLocator.installedPath(home)
        val registry: Path = home.resolve(".fennec").resolve("air").resolve("registry.json")
        val overlay: Path = home.resolve(".jetbrains").resolve("acp-registry-installed-overlay.json")
        val installed: Path = commonDataDir.resolve("acp-agents").resolve("installed.json")
        val acpJson: Path = home.resolve(".jetbrains").resolve("acp.json")
        val vmoptions: Path = configDir.resolve(
            when {
                SharedConfig.isWindows() -> "idea64.exe.vmoptions"
                SharedConfig.isMac() -> "idea.vmoptions"
                else -> "idea64.vmoptions"
            }
        )

        init {
            System.clearProperty("jb.vmOptionsFile")
        }

        fun cacheRegistry(text: String, name: String = "registry-1.json", mtime: Long? = null) {
            val dir = systemDir.resolve("acp-agents")
            Files.createDirectories(dir)
            val file = dir.resolve(name)
            Files.writeString(file, text)
            if (mtime != null) Files.setLastModifiedTime(file, FileTime.fromMillis(mtime))
        }

        fun register(version: String = "1.0.0") {
            AirAgentRegistrar.register(home, configDir, commonDataDir, systemDir, version, sidecar)
        }

        fun readJson(path: Path): JsonObject = JsonParser.parseString(Files.readString(path)).asJsonObject

        fun agentIds(registry: JsonObject): List<String> =
            registry.getAsJsonArray("agents").map { it.asJsonObject.get("id").asString }
    }

    private val cachedCatalog = """{"version":"1.0.0","agents":[{"id":"other","name":"Other"}]}"""

    private fun writeColibri(f: Fixture, arg: String) {
        val args = JsonArray()
        args.add(arg)
        val colibri = JsonObject()
        colibri.add("args", args)
        val servers = JsonObject()
        servers.add("Colibri", colibri)
        val root = JsonObject()
        root.add("agent_servers", servers)
        Files.createDirectories(f.acpJson.parent)
        Files.writeString(f.acpJson, root.toString())
    }

    fun run() {
        TestMain.check("installs the sidecar binary") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            TestMain.expect(Files.isRegularFile(f.launcher), "launcher installed at ${f.launcher}")
            TestMain.expect(Files.readAllBytes(f.launcher).contentEquals(f.sidecar), "launcher bytes match")
        }

        TestMain.check("publishes a merged registry") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            val registry = f.readJson(f.registry)
            TestMain.expectEquals("1.0.0", registry.get("version").asString, "registry version")
            TestMain.expectEquals(listOf("other", "fennec"), f.agentIds(registry), "merged agents")
        }

        TestMain.check("removes legacy colibri from the merged registry") {
            val f = Fixture()
            f.cacheRegistry("""{"agents":[{"id":"colibri"},{"id":"other"},{"id":"fennec"}]}""")
            f.register()
            TestMain.expectEquals(listOf("other", "fennec"), f.agentIds(f.readJson(f.registry)), "colibri dropped")
        }

        TestMain.check("writes the registry vmoption") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            val text = Files.readString(f.vmoptions)
            val expected = "-Dair.acp.registry.path=${f.registry.toString().replace('\\', '/')}"
            TestMain.expect(text.contains(expected), "vmoption present in <$text>")
        }

        TestMain.check("replaces an existing registry vmoption") {
            val f = Fixture()
            Files.writeString(f.vmoptions, "-Xmx1024m\n-Dair.acp.registry.path=/old/path\n-Dother=value\n")
            f.cacheRegistry(cachedCatalog)
            f.register()
            val text = Files.readString(f.vmoptions)
            TestMain.expect(!text.contains("/old/path"), "old path removed")
            TestMain.expect(text.contains("-Xmx1024m") && text.contains("-Dother=value"), "other options kept")
            TestMain.expectEquals(1, text.split("-Dair.acp.registry.path=").size - 1, "exactly one registry option")
        }

        TestMain.check("preserves CRLF in vmoptions") {
            val f = Fixture()
            Files.writeString(f.vmoptions, "-Xmx1024m\r\n")
            f.cacheRegistry(cachedCatalog)
            f.register()
            val text = Files.readString(f.vmoptions)
            TestMain.expect(text.contains("\r\n"), "CRLF preserved")
            TestMain.expect(!text.replace("\r\n", "").contains("\n"), "no bare LF introduced")
        }

        TestMain.check("overlay command is the sidecar with empty args") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            val local = f.readJson(f.overlay)
                .getAsJsonObject("agents").getAsJsonObject("acp.registry.fennec")
                .getAsJsonObject("agent").getAsJsonObject("distribution").getAsJsonObject("local")
            TestMain.expectEquals(f.launcher.toString(), local.get("command").asString, "overlay command")
            TestMain.expectEquals(0, local.getAsJsonArray("args").size(), "overlay args empty")
        }

        TestMain.check("records the agent as installed") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            val record = f.readJson(f.installed).getAsJsonObject("agents").getAsJsonObject("acp.registry.fennec")
            TestMain.expect(record.has("installedAt") && record.has("lastUsedAt"), "timestamps present")
            TestMain.expectEquals(true, record.get("isExplicitlyInstalled").asBoolean, "explicitly installed")
            TestMain.expectEquals("DEFAULT", record.get("autoUpdatePolicy").asString, "auto update policy")
        }

        TestMain.check("preserves installedAt across re-registration") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            val first = f.readJson(f.installed).getAsJsonObject("agents")
                .getAsJsonObject("acp.registry.fennec").get("installedAt").asLong
            f.register()
            val second = f.readJson(f.installed).getAsJsonObject("agents")
                .getAsJsonObject("acp.registry.fennec").get("installedAt").asLong
            TestMain.expectEquals(first, second, "installedAt preserved")
        }

        TestMain.check("is idempotent") {
            val f = Fixture()
            f.cacheRegistry(cachedCatalog)
            f.register()
            val before = listOf(f.registry, f.overlay, f.installed).map { Files.readString(it) }
            f.register()
            val after = listOf(f.registry, f.overlay, f.installed).map { Files.readString(it) }
            TestMain.expectEquals(before, after, "files unchanged on second register")
        }

        TestMain.check("no cache and no previous registry forgets records") {
            val f = Fixture()
            Files.createDirectories(f.installed.parent)
            Files.writeString(f.installed, """{"agents":{"acp.registry.fennec":{},"acp.registry.other":{}}}""")
            Files.createDirectories(f.overlay.parent)
            Files.writeString(f.overlay, """{"agents":{"acp.registry.fennec":{},"acp.registry.other":{}}}""")
            f.register()
            TestMain.expect(!Files.exists(f.registry), "no registry published")
            val installedAgents = f.readJson(f.installed).getAsJsonObject("agents")
            val overlayAgents = f.readJson(f.overlay).getAsJsonObject("agents")
            TestMain.expect(!installedAgents.has("acp.registry.fennec"), "installed record forgotten")
            TestMain.expect(!overlayAgents.has("acp.registry.fennec"), "overlay record forgotten")
            TestMain.expect(installedAgents.has("acp.registry.other"), "other installed agent kept")
            TestMain.expect(overlayAgents.has("acp.registry.other"), "other overlay agent kept")
        }

        TestMain.check("extends a previous registry when no cache") {
            val f = Fixture()
            Files.createDirectories(f.registry.parent)
            Files.writeString(f.registry, """{"version":"0.9.0","agents":[{"id":"other"}]}""")
            f.register()
            val registry = f.readJson(f.registry)
            TestMain.expectEquals(listOf("other", "fennec"), f.agentIds(registry), "previous registry extended")
            TestMain.expectEquals("0.9.0", registry.get("version").asString, "previous version kept")
        }

        TestMain.check("removes a legacy Colibri picker entry pointing at the old shim") {
            val f = Fixture()
            val oldShim = f.launcher.parent.resolve("fennec-acp.py")
            writeColibri(f, oldShim.toString())
            f.cacheRegistry(cachedCatalog)
            f.register()
            TestMain.expect(!f.readJson(f.acpJson).getAsJsonObject("agent_servers").has("Colibri"), "Colibri removed")
        }

        TestMain.check("removes a legacy Colibri picker entry pointing at the sidecar") {
            val f = Fixture()
            writeColibri(f, f.launcher.toString())
            f.cacheRegistry(cachedCatalog)
            f.register()
            TestMain.expect(!f.readJson(f.acpJson).getAsJsonObject("agent_servers").has("Colibri"), "Colibri removed")
        }

        TestMain.check("leaves an unrelated Colibri picker entry alone") {
            val f = Fixture()
            writeColibri(f, "/unrelated/agent.py")
            f.cacheRegistry(cachedCatalog)
            f.register()
            TestMain.expect(f.readJson(f.acpJson).getAsJsonObject("agent_servers").has("Colibri"), "Colibri kept")
        }

        TestMain.check("newest cached registry wins") {
            val f = Fixture()
            f.cacheRegistry("""{"agents":[{"id":"old"}]}""", "registry-old.json", mtime = 1_000)
            f.cacheRegistry("""{"agents":[{"id":"new"}]}""", "registry-new.json", mtime = 2_000)
            f.register()
            TestMain.expectEquals(listOf("new", "fennec"), f.agentIds(f.readJson(f.registry)), "newest cache used")
        }

        TestMain.check("skips an unreadable cache and uses the next") {
            val f = Fixture()
            f.cacheRegistry("""{"agents":[{"id":"good"}]}""", "registry-good.json", mtime = 1_000)
            f.cacheRegistry("{not json", "registry-bad.json", mtime = 2_000)
            f.register()
            TestMain.expectEquals(listOf("good", "fennec"), f.agentIds(f.readJson(f.registry)), "bad cache skipped")
        }

        TestMain.check("backs up vmoptions once") {
            val f = Fixture()
            Files.writeString(f.vmoptions, "-Xmx1024m\n")
            f.cacheRegistry(cachedCatalog)
            f.register()
            val backup = f.vmoptions.resolveSibling("${f.vmoptions.fileName}.bak-fennec")
            TestMain.expectEquals("-Xmx1024m\n", Files.readString(backup), "backup created")
            Files.writeString(f.vmoptions, "-Xmx2048m\n")
            f.register()
            TestMain.expectEquals("-Xmx1024m\n", Files.readString(backup), "backup not overwritten")
        }
    }
}