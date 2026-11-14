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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Publishes Fennec as an agent card in Air's agent catalog.
 *
 * Air builds its Agents page from the external ACP registry, not from acp.json.
 * A local command distribution cannot be expressed in the registry itself, so
 * the launch command - the bundled Rust sidecar - is injected through Air's
 * installed overlay, which is read from next to acp.json.
 *
 * Everything is idempotent and rebuilt on every IDE start, because Air may
 * refresh its own cached registry at any time.
 */
internal object AirAgentRegistrar {

    private val LOG = Logger.getLogger("fennec.air")
    private val STARTED = AtomicBoolean()

    private const val AGENT_ID = "fennec"
    private const val INSTALL_KEY = "acp.registry.$AGENT_ID"
    // Pre-rebrand releases published under these; leaving them behind shows a second, dead card.
    private const val LEGACY_AGENT_ID = "colibri"
    private const val LEGACY_INSTALL_KEY = "acp.registry.$LEGACY_AGENT_ID"
    private const val AGENT_NAME = "Fennec"
    private const val REPOSITORY = "https://github.com/Liso7150/fennec-jetbrains"
    private const val AIR_PLUGIN_ID = "com.intellij.air"
    private const val PLUGIN_ID = "com.fennec.ide"
    private const val REGISTRY_PROPERTY = "air.acp.registry.path"
    private const val FALLBACK_VERSION = "1.0.0"

    fun registerOnce() {
        if (!STARTED.compareAndSet(false, true)) return
        try {
            if (!airAvailable()) {
                LOG.info("Air is not installed in this IDE; the Fennec agent is not registered")
                return
            }
            val sidecar = SidecarLocator.resourceBytes()
            if (sidecar == null) {
                LOG.warning("No Fennec sidecar is bundled for ${SidecarLocator.osDir()}; the Fennec agent is not registered")
                return
            }
            register(
                Path.of(System.getProperty("user.home")),
                Path.of(PathManager.getConfigPath()),
                PathManager.getCommonDataPath(),
                Path.of(PathManager.getSystemPath()),
                pluginVersion(),
                sidecar,
            )
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Fennec Air agent registration failed: ${e.message}", e)
        }
    }

    internal fun register(
        home: Path,
        configDir: Path,
        commonDataDir: Path,
        systemDir: Path,
        version: String,
        sidecar: ByteArray,
    ) {
        val launcher = SidecarLocator.install(home, sidecar)
        removeLegacyPickerEntry(home, launcher)

        val agent = registryEntry(version)
        val registry = home.resolve(".fennec").resolve("air").resolve("registry.json")
        val overlay = home.resolve(".jetbrains").resolve("acp-registry-installed-overlay.json")
        val published = buildRegistry(registry, agent, systemDir)
        if (published) {
            val registryPath = registry.toString().replace('\\', '/')
            System.setProperty(REGISTRY_PROPERTY, registryPath)
            ensureRegistryVmOption(configDir, registryPath)
            LOG.info("Fennec registry published at $registry ($registryPath)")
            // The overlay is the only place Air accepts a local command, so it is
            // what actually launches the ACP agent on this machine.
            writeInstalled(commonDataDir, agent)
            writeOverlay(overlay, launcher)
        } else {
            LOG.warning(
                "No cached Air registry found at ${systemDir.resolve("acp-agents")} and no previous " +
                    "Fennec registry to extend; leaving Air's catalog untouched"
            )
            forgetRecords(commonDataDir, overlay)
        }
    }

    /**
     * Air renders installed.json through the catalog, so a record whose card was
     * never published is an agent the user can see, cannot use and cannot remove.
     * Only our own keys go; other agents in those files are left exactly as found.
     */
    private fun forgetRecords(commonDataDir: Path, overlay: Path) {
        removeOurKey(commonDataDir.resolve("acp-agents").resolve("installed.json"), INSTALL_KEY)
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

    private fun airAvailable(): Boolean =
        try {
            PluginManagerCore.getPlugin(PluginId.getId(AIR_PLUGIN_ID)) != null
        } catch (e: RuntimeException) {
            false
        }

    private fun pluginVersion(): String {
        try {
            val version = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
            if (version != null) return version
        } catch (e: RuntimeException) {
            // fall through to the bundled version
        }
        return FALLBACK_VERSION
    }

    private fun registryEntry(version: String): JsonObject {
        val entry = JsonObject()
        entry.addProperty("id", AGENT_ID)
        entry.addProperty("name", AGENT_NAME)
        entry.addProperty("version", version)
        entry.addProperty(
            "description",
            "Coding agent backed by the Fennec model on this machine, with optional local " +
                "browser outlets (jev-ultrafast / OpenBot) for URL-scoped tasks. The engine " +
                "starts with the first prompt and stops after it has been idle."
        )
        entry.addProperty("website", REPOSITORY)
        entry.addProperty("repository", REPOSITORY)
        val authors = JsonArray()
        authors.add("Fennec")
        entry.add("authors", authors)
        entry.addProperty("license", "proprietary")
        entry.addProperty("icon", "https://raw.githubusercontent.com/Liso7150/fennec-jetbrains/main/icon.svg")
        // No distribution here: Air's registry only models downloadable binaries, and
        // Fennec runs from this machine. writeOverlay carries the launch command.
        return entry
    }

    /**
     * Air's registry override replaces the whole catalog, so the cached
     * remote registry is merged with the Fennec entry instead of replaced.
     */
    private fun buildRegistry(target: Path, agent: JsonObject, systemDir: Path): Boolean {
        var base = readNewestCachedRegistry(systemDir.resolve("acp-agents"))
        if (base == null && Files.isRegularFile(target)) {
            try {
                base = readJson(target)
            } catch (e: IOException) {
                LOG.warning("Ignoring the previous Fennec registry: ${e.message}")
            }
        }
        if (base == null) return false

        val merged = JsonArray()
        val baseAgents = base.get("agents")
        if (baseAgents != null && baseAgents.isJsonArray) {
            for (item in baseAgents.asJsonArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val id = string(obj.get("id"))
                if (id != AGENT_ID && id != LEGACY_AGENT_ID) merged.add(obj)
            }
        }
        merged.add(agent)

        val root = JsonObject()
        root.addProperty("version", stringOr(base.get("version"), "1.0.0"))
        root.add("agents", merged)
        writeJsonIfChanged(target, root)
        return true
    }

    /**
     * The installed overlay is the only place Air accepts a local command
     * distribution; the registry models it as transient by design. The sidecar
     * is a self-contained executable, so it is the command and takes no args.
     */
    private fun writeOverlay(file: Path, launcher: Path) {
        val root = try {
            if (Files.isRegularFile(file)) readJson(file) else JsonObject()
        } catch (e: IOException) {
            LOG.warning("Leaving $file untouched: ${e.message}")
            return
        }

        val local = JsonObject()
        local.addProperty("command", launcher.toString())
        local.add("args", JsonArray())
        local.add("env", JsonObject())

        val distribution = JsonObject()
        distribution.add("local", local)
        val agent = JsonObject()
        agent.add("distribution", distribution)
        val entry = JsonObject()
        entry.add("agent", agent)

        val agents = objectAt(root, "agents")
        agents.add(INSTALL_KEY, entry)
        agents.remove(LEGACY_INSTALL_KEY)
        root.add("agents", agents)
        try {
            if (writeJsonIfChanged(file, root)) LOG.info("Fennec launch overlay updated in $file")
        } catch (e: IOException) {
            LOG.warning("Could not write $file: ${e.message}")
        }
    }

    /**
     * A record in Air's shared installed.json is what turns a registry entry
     * card into an installed agent that can be enabled, disabled or removed.
     */
    private fun writeInstalled(commonDataDir: Path, agent: JsonObject) {
        val file = commonDataDir.resolve("acp-agents").resolve("installed.json")
        val root = try {
            if (Files.isRegularFile(file)) readJson(file) else JsonObject()
        } catch (e: IOException) {
            LOG.warning("Leaving $file untouched: ${e.message}")
            return
        }

        val agents = objectAt(root, "agents")
        val existingElement = agents.get(INSTALL_KEY)
        val existing = existingElement?.takeIf { it.isJsonObject }?.asJsonObject
        val now = System.currentTimeMillis()
        val installedAt = if (existing != null) longOr(existing.get("installedAt"), now) else now
        val lastUsedAt = if (existing != null) longOr(existing.get("lastUsedAt"), now) else now

        val record = JsonObject()
        record.add("agent", agent)
        record.addProperty("installedAt", installedAt)
        record.addProperty("lastUsedAt", lastUsedAt)
        record.add("acceptedTermsOfServiceVersion", JsonNull.INSTANCE)
        record.addProperty("isExplicitlyInstalled", true)
        record.addProperty("autoUpdatePolicy", "DEFAULT")
        record.add("skippedVersion", JsonNull.INSTANCE)
        agents.add(INSTALL_KEY, record)
        agents.remove(LEGACY_INSTALL_KEY)
        root.add("agents", agents)
        val disabled = root.get("disabledAgents")
        if (disabled == null || !disabled.isJsonArray) root.add("disabledAgents", JsonArray())
        val terms = root.get("acceptedTermsOfServiceVersions")
        if (terms == null || !terms.isJsonObject) root.add("acceptedTermsOfServiceVersions", JsonObject())

        try {
            if (writeJsonIfChanged(file, root)) LOG.info("Fennec recorded as installed in $file")
        } catch (e: IOException) {
            LOG.warning("Could not write $file: ${e.message}")
        }
    }

    /**
     * The registry override is a system property; persisting it in the IDE's
     * user vmoptions file makes Air read the merged catalog on every start.
     */
    private fun ensureRegistryVmOption(configDir: Path, registryPath: String) {
        val file = vmOptionsFile(configDir)
        val option = "-D$REGISTRY_PROPERTY=$registryPath"
        val text = if (Files.isRegularFile(file)) Files.readString(file, StandardCharsets.UTF_8) else ""
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"

        val lines = mutableListOf<String>()
        // Java's String.split drops trailing empties; keep that behavior exactly.
        if (text.isNotEmpty()) lines.addAll(text.split(Regex("\r?\n")).dropLastWhile { it.isEmpty() })
        var present = false
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("-D$REGISTRY_PROPERTY=")) {
                lines[i] = option
                present = true
            }
        }
        if (!present) {
            if (lines.isNotEmpty() && lines[lines.size - 1].isBlank()) lines.removeAt(lines.size - 1)
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
                    .min(compareBy<Path> { it.fileName.toString() })
                    .orElse(null)
                if (existing != null) return existing
            }
        } catch (e: IOException) {
            // fall through to the platform default name
        }
        val name = when {
            SharedConfig.isWindows() -> "idea64.exe.vmoptions"
            SharedConfig.isMac() -> "idea.vmoptions"
            else -> "idea64.vmoptions"
        }
        return configDir.resolve(name)
    }

    /**
     * 1.3.0 registered itself through acp.json under the pre-rebrand name
     * "Colibri"; such an entry would linger in Air's picker next to the registry
     * card. Only entries that point at a managed launcher are removed - other
     * custom agents are left alone. Both the current sidecar and the retired
     * Python shim count as managed.
     */
    private fun removeLegacyPickerEntry(home: Path, launcher: Path) {
        val file = home.resolve(".jetbrains").resolve("acp.json")
        if (!Files.isRegularFile(file)) return
        try {
            val root = readJson(file)
            val servers = root.get("agent_servers")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val entry = servers.get("Colibri")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val legacyShim = launcher.parent.resolve("fennec-acp.py")
            if (!pointsAtLauncher(entry, launcher, legacyShim)) return
            backupOnce(file)
            servers.remove("Colibri")
            writeJson(file, root)
            LOG.info("Removed the legacy Colibri picker entry from $file")
        } catch (e: Exception) {
            LOG.warning("Could not clean $file: ${e.message}")
        }
    }

    private fun pointsAtLauncher(entry: JsonObject, launcher: Path, legacyShim: Path): Boolean {
        val needles = setOf(normalizePath(launcher.toString()), normalizePath(legacyShim.toString()))
        return strings(entry.get("args")).any { normalizePath(it) in needles }
    }

    private fun normalizePath(value: String): String = value.replace('\\', '/').lowercase(Locale.ROOT)

    private fun readNewestCachedRegistry(dir: Path): JsonObject? {
        if (!Files.isDirectory(dir)) return null
        val candidates: List<Path>
        try {
            Files.list(dir).use { files ->
                candidates = files
                    .filter { Files.isRegularFile(it) }
                    .filter {
                        val name = it.fileName.toString()
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
            try {
                return readJson(candidate)
            } catch (e: IOException) {
                LOG.warning("Ignoring unreadable registry cache $candidate: ${e.message}")
            }
        }
        return null
    }

    private fun lastModified(file: Path): Long =
        try {
            Files.getLastModifiedTime(file).toMillis()
        } catch (e: IOException) {
            0L
        }

    private fun readJson(file: Path): JsonObject {
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                val parsed = JsonParser.parseReader(reader)
                if (!parsed.isJsonObject) throw IOException("not a JSON object: $file")
                return parsed.asJsonObject
            }
        } catch (e: RuntimeException) {
            throw IOException("cannot parse $file: ${e.message}", e)
        }
    }

    private fun objectAt(root: JsonObject, key: String): JsonObject {
        val existing = root.get(key)
        if (existing != null && existing.isJsonObject) return existing.asJsonObject
        val created = JsonObject()
        root.add(key, created)
        return created
    }

    private fun strings(element: JsonElement?): List<String> {
        val values = mutableListOf<String>()
        if (element != null && element.isJsonArray) {
            for (item in element.asJsonArray) {
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) values.add(item.asString)
            }
        }
        return values
    }

    private fun string(element: JsonElement?): String =
        if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else ""

    private fun stringOr(element: JsonElement?, fallback: String): String {
        val value = string(element)
        return if (value.isBlank()) fallback else value
    }

    private fun longOr(element: JsonElement?, fallback: Long): Long =
        try {
            if (element != null && element.isJsonPrimitive) element.asLong else fallback
        } catch (e: RuntimeException) {
            fallback
        }

    private fun writeJsonIfChanged(file: Path, root: JsonObject): Boolean {
        if (Files.isRegularFile(file)) {
            try {
                if (readJson(file) == root) return false
            } catch (e: IOException) {
                // unreadable current content: rewrite it
            }
        }
        writeJson(file, root)
        return true
    }

    private fun backupOnce(file: Path) {
        if (!Files.exists(file)) return
        val backup = file.resolveSibling("${file.fileName}.bak-fennec")
        if (Files.exists(backup)) return
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            LOG.warning("Could not back up $file: ${e.message}")
        }
    }

    private fun writeJson(file: Path, root: JsonObject) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling("${file.fileName}.tmp-fennec")
        val text = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) +
            System.lineSeparator()
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}