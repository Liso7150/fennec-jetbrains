package fennec.plugin

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import java.io.IOException
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import java.util.stream.Stream

internal object AirAgentRegistrar {
    private val LOG = Logger.getLogger("fennec.air")
    private val STARTED = AtomicBoolean()

    private const val AGENT_ID = "fennec"
    private const val INSTALL_KEY = "acp.registry.$AGENT_ID"
    private const val LEGACY_AGENT_ID = "colibri"
    private const val LEGACY_INSTALL_KEY = "acp.registry.$LEGACY_AGENT_ID"
    private const val AGENT_NAME = "Fennec"
    private const val REPOSITORY = "https://github.com/Liso7150/fennec-jetbrains"
    private const val AIR_PLUGIN_ID = "com.intellij.air"
    private const val PLUGIN_ID = "com.fennec.ide"
    private const val REGISTRY_PROPERTY = "air.acp.registry.path"
    private const val FALLBACK_VERSION = "1.0.0"

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun registerOnce() {
        if (!STARTED.compareAndSet(false, true)) return
        try {
            if (!airAvailable()) {
                LOG.info("Air is not installed in this IDE; the Fennec agent is not registered")
                return
            }
            register(
                home = Path.of(System.getProperty("user.home")),
                configDir = Path.of(PathManager.getConfigPath()),
                commonDataDir = PathManager.getCommonDataPath(),
                systemDir = Path.of(PathManager.getSystemPath()),
                version = pluginVersion(),
            )
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Fennec Air agent registration failed: ${e.message}", e)
        }
    }

    private fun register(home: Path, configDir: Path, commonDataDir: Path, systemDir: Path, version: String) {
        val launcher = home.resolve(".fennec/air/fennec-acp.py")
        installLauncher(launcher)
        removeLegacyPickerEntry(home, launcher)

        val agent = registryEntry(version)
        val registry = home.resolve(".fennec/air/registry.json")
        val overlay = home.resolve(".jetbrains/acp-registry-installed-overlay.json")
        val published = buildRegistry(registry, agent, systemDir)
        if (published) {
            val registryPath = registry.toString().replace('\\', '/')
            System.setProperty(REGISTRY_PROPERTY, registryPath)
            ensureRegistryVmOption(configDir, registryPath)
            LOG.info("Fennec registry published at $registry ($registryPath)")
            writeInstalled(commonDataDir, agent)
            writeOverlay(overlay, launcher)
        } else {
            LOG.warning("No cached Air registry found at ${systemDir.resolve("acp-agents")}" +
                    " and no previous Fennec registry to extend; leaving Air's catalog untouched")
            forgetRecords(commonDataDir, overlay)
        }
    }

    private fun forgetRecords(commonDataDir: Path, overlay: Path) {
        removeOurKey(commonDataDir.resolve("acp-agents/installed.json"), INSTALL_KEY)
        removeOurKey(overlay, INSTALL_KEY)
    }

    private fun removeOurKey(file: Path, key: String) {
        if (!Files.isRegularFile(file)) return
        try {
            val root = readJson(file)
            val agents = root.get("agents")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            if (!agents.has(key)) return
            agents.remove(key)
            agents.remove(LEGACY_INSTALL_KEY)
            root.add("agents", agents)
            if (writeJsonIfChanged(file, root)) LOG.info("Removed the stale Fennec record from $file")
        } catch (e: Exception) {
            LOG.warning("Left $file untouched: ${e.message}")
        }
    }

    private fun airAvailable(): Boolean = try {
        PluginManagerCore.getPlugin(PluginId.getId(AIR_PLUGIN_ID)) != null
    } catch (_: RuntimeException) {
        false
    }

    private fun pluginVersion(): String = try {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        descriptor?.version ?: FALLBACK_VERSION
    } catch (_: RuntimeException) {
        FALLBACK_VERSION
    }

    private fun registryEntry(version: String): JsonObject = JsonObject().apply {
        addProperty("id", AGENT_ID)
        addProperty("name", AGENT_NAME)
        addProperty("version", version)
        addProperty("description",
            "Coding agent backed by the Fennec model on this machine, with optional local " +
            "browser outlets (jev-ultrafast / OpenBot) for URL-scoped tasks. The engine " +
            "starts with the first prompt and stops after it has been idle.")
        addProperty("website", REPOSITORY)
        addProperty("repository", REPOSITORY)
        add("authors", JsonArray().apply { add("Fennec") })
        addProperty("license", "proprietary")
        addProperty("icon", "https://raw.githubusercontent.com/Liso7150/fennec-jetbrains/main/icon.svg")
    }

    private fun buildRegistry(target: Path, agent: JsonObject, systemDir: Path): Boolean {
        var base = readNewestCachedRegistry(systemDir.resolve("acp-agents"))
        if (base == null && Files.isRegularFile(target)) {
            try { base = readJson(target) }
            catch (e: IOException) { LOG.warning("Ignoring the previous Fennec registry: ${e.message}") }
        }
        if (base == null) return false

        val merged = JsonArray()
        if (base.has("agents") && base.get("agents").isJsonArray) {
            for (item in base.getAsJsonArray("agents")) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val id = string(obj.get("id"))
                if (id != AGENT_ID && id != LEGACY_AGENT_ID) merged.add(obj)
            }
        }
        merged.add(agent)

        val root = JsonObject().apply {
            addProperty("version", stringOr(base.get("version"), "1.0.0"))
            add("agents", merged)
        }
        writeJsonIfChanged(target, root)
        return true
    }

    private fun writeOverlay(file: Path, launcher: Path) {
        val root = try {
            if (Files.isRegularFile(file)) readJson(file) else JsonObject()
        } catch (e: IOException) {
            LOG.warning("Leaving $file untouched: ${e.message}")
            return
        }

        val local = JsonObject().apply {
            addProperty("command", SharedConfig.pythonForLauncher())
            add("args", JsonArray().apply { add(launcher.toString()) })
            add("env", JsonObject())
        }
        val entry = JsonObject().apply {
            add("agent", JsonObject().apply {
                add("distribution", JsonObject().apply { add("local", local) })
            })
        }
        val agents = objectOrNew(root, "agents")
        agents.add(INSTALL_KEY, entry)
        agents.remove(LEGACY_INSTALL_KEY)
        root.add("agents", agents)
        try {
            if (writeJsonIfChanged(file, root)) LOG.info("Fennec launch overlay updated in $file")
        } catch (e: IOException) {
            LOG.warning("Could not write $file: ${e.message}")
        }
    }

    private fun writeInstalled(commonDataDir: Path, agent: JsonObject) {
        val file = commonDataDir.resolve("acp-agents/installed.json")
        val root = try {
            if (Files.isRegularFile(file)) readJson(file) else JsonObject()
        } catch (e: IOException) {
            LOG.warning("Leaving $file untouched: ${e.message}")
            return
        }

        val agents = objectOrNew(root, "agents")
        val existing = agents.get(INSTALL_KEY)?.takeIf { it.isJsonObject }?.asJsonObject
        val now = System.currentTimeMillis()
        val installedAt = existing?.let { longOr(it.get("installedAt"), now) } ?: now
        val lastUsedAt = existing?.let { longOr(it.get("lastUsedAt"), now) } ?: now

        val record = JsonObject().apply {
            add("agent", agent)
            addProperty("installedAt", installedAt)
            addProperty("lastUsedAt", lastUsedAt)
            add("acceptedTermsOfServiceVersion", JsonNull.INSTANCE)
            addProperty("isExplicitlyInstalled", true)
            addProperty("autoUpdatePolicy", "DEFAULT")
            add("skippedVersion", JsonNull.INSTANCE)
        }
        agents.add(INSTALL_KEY, record)
        agents.remove(LEGACY_INSTALL_KEY)
        root.add("agents", agents)
        if (!root.has("disabledAgents") || !root.get("disabledAgents").isJsonArray) {
            root.add("disabledAgents", JsonArray())
        }
        if (!root.has("acceptedTermsOfServiceVersions") || !root.get("acceptedTermsOfServiceVersions").isJsonObject) {
            root.add("acceptedTermsOfServiceVersions", JsonObject())
        }

        try {
            if (writeJsonIfChanged(file, root)) LOG.info("Fennec recorded as installed in $file")
        } catch (e: IOException) {
            LOG.warning("Could not write $file: ${e.message}")
        }
    }

    private fun ensureRegistryVmOption(configDir: Path, registryPath: String) {
        val file = vmOptionsFile(configDir)
        val option = "-D$REGISTRY_PROPERTY=$registryPath"
        val text = if (Files.isRegularFile(file)) Files.readString(file, StandardCharsets.UTF_8) else ""
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"

        val lines = if (text.isEmpty()) mutableListOf() else text.split("\r?\n".toRegex()).toMutableList()
        var present = false
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("-D$REGISTRY_PROPERTY=")) {
                lines[i] = option
                present = true
            }
        }
        if (!present) {
            if (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.add(option)
        }

        val updated = lines.joinToString(newline) + newline
        if (updated == text) return
        backupOnce(file)
        Files.createDirectories(file.parent)
        Files.writeString(file, updated, StandardCharsets.UTF_8)
        LOG.info("Air registry override registered in $file (applies on the next IDE start)")
    }

    private fun vmOptionsFile(configDir: Path): Path {
        val current = System.getProperty("jb.vmOptionsFile")
        if (!current.isNullOrBlank()) {
            val name = Path.of(current).fileName
            if (name != null && name.toString().endsWith(".vmoptions")) return configDir.resolve(name.toString())
        }
        try {
            Files.list(configDir).use { files ->
                val existing = files
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".vmoptions") }
                    .min(Comparator.comparing { it.fileName.toString() })
                    .orElse(null)
                if (existing != null) return existing
            }
        } catch (_: IOException) {}
        val name = when {
            SystemInfo.isWindows -> "idea64.exe.vmoptions"
            SystemInfo.isMac -> "idea.vmoptions"
            else -> "idea64.vmoptions"
        }
        return configDir.resolve(name)
    }

    private fun installLauncher(target: Path) {
        val content = AirAgentRegistrar::class.java.getResourceAsStream("/fennec/air/fennec-acp.py")?.use { it.readAllBytes() }
            ?: throw IOException("fennec-acp.py is missing from the plugin")
        if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(content)) return
        Files.createDirectories(target.parent)
        Files.write(target, content)
        LOG.info("Installed the Fennec ACP launcher at $target")
    }

    private fun removeLegacyPickerEntry(home: Path, launcher: Path) {
        val file = home.resolve(".jetbrains/acp.json")
        if (!Files.isRegularFile(file)) return
        try {
            val root = readJson(file)
            val servers = root.get("agent_servers")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val entry = servers.get("Colibri")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            if (!pointsAtLauncher(entry, launcher)) return
            backupOnce(file)
            servers.remove("Colibri")
            writeJson(file, root)
            LOG.info("Removed the legacy Colibri picker entry from $file")
        } catch (e: Exception) {
            LOG.warning("Could not clean $file: ${e.message}")
        }
    }

    private fun pointsAtLauncher(entry: JsonObject, launcher: Path): Boolean {
        val needle = normalizePath(launcher.toString())
        return strings(entry.get("args")).any { normalizePath(it) == needle }
    }

    private fun normalizePath(value: String) = value.replace('\\', '/').lowercase(Locale.ROOT)

    private fun readNewestCachedRegistry(dir: Path): JsonObject? {
        if (!Files.isDirectory(dir)) return null
        val candidates: List<Path> = try {
            Files.list(dir).use { files ->
                files.filter { Files.isRegularFile(it) }
                    .filter { p ->
                        val name = p.fileName.toString()
                        name.startsWith("registry-") && name.endsWith(".json")
                    }
                    .sorted(Comparator.comparingLong<Path> { lastModified(it) }.reversed())
                    .toList()
            }
        } catch (e: IOException) {
            LOG.warning("Could not list $dir: ${e.message}")
            return null
        }
        for (candidate in candidates) {
            try { return readJson(candidate) }
            catch (e: IOException) { LOG.warning("Ignoring unreadable registry cache $candidate: ${e.message}") }
        }
        return null
    }

    private fun lastModified(file: Path): Long = try {
        Files.getLastModifiedTime(file).toMillis()
    } catch (_: IOException) { 0L }

    private fun readJson(file: Path): JsonObject {
        return Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
            val parsed = JsonParser.parseReader(reader)
            require(parsed.isJsonObject) { "not a JSON object: $file" }
            parsed.asJsonObject
        }
    }

    private fun objectOrNew(root: JsonObject, key: String): JsonObject {
        val existing = root.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        if (existing != null) return existing
        val created = JsonObject()
        root.add(key, created)
        return created
    }

    private fun strings(element: JsonElement?): List<String> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
        }
    }

    private fun string(element: JsonElement?): String =
        if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else ""

    private fun stringOr(element: JsonElement?, fallback: String): String {
        val value = string(element)
        return value.ifBlank { fallback }
    }

    private fun longOr(element: JsonElement?, fallback: Long): Long = try {
        if (element != null && element.isJsonPrimitive) element.asLong else fallback
    } catch (_: RuntimeException) { fallback }

    private fun writeJsonIfChanged(file: Path, root: JsonObject): Boolean {
        if (Files.isRegularFile(file)) {
            try { if (readJson(file) == root) return false }
            catch (_: IOException) {}
        }
        writeJson(file, root)
        return true
    }

    private fun backupOnce(file: Path) {
        if (!Files.exists(file)) return
        val backup = file.resolveSibling("${file.fileName}.bak-fennec")
        if (Files.exists(backup)) return
        try { Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING) }
        catch (e: IOException) { LOG.warning("Could not back up $file: ${e.message}") }
    }

    private fun writeJson(file: Path, root: JsonObject) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp-fennec")
        val text = GSON.toJson(root) + System.lineSeparator()
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
