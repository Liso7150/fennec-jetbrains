package fennec.plugin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.SystemInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Publishes Fennec as an agent card in Air's agent catalog.
 *
 * Air builds its Agents page from the external ACP registry, not from
 * acp.json (entries there only appear in the session agent picker). A local
 * command distribution cannot be expressed in the registry itself - that
 * field is transient - so the launch command is injected through Air's
 * installed overlay, which is read from next to acp.json.
 *
 * Everything is idempotent and rebuilt on every IDE start, because Air may
 * refresh its own cached registry at any time.
 */
final class AirAgentRegistrar {
    private static final Logger LOG = Logger.getLogger("fennec.air");
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static final String AGENT_ID = "fennec";
    private static final String INSTALL_KEY = "acp.registry." + AGENT_ID;
    // Pre-rebrand releases published under these; leaving them behind shows a second, dead card.
    private static final String LEGACY_AGENT_ID = "colibri";
    private static final String LEGACY_INSTALL_KEY = "acp.registry." + LEGACY_AGENT_ID;
    private static final String AGENT_NAME = "Fennec Desktop";
    private static final String REPOSITORY = "https://github.com/Liso7150/fennec-jetbrains";
    private static final String AIR_PLUGIN_ID = "com.intellij.air";
    private static final String PLUGIN_ID = "com.fennec.ide";
    private static final String REGISTRY_PROPERTY = "air.acp.registry.path";
    private static final String FALLBACK_VERSION = "1.9.0";

    private AirAgentRegistrar() {}

    static void registerOnce() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            if (!airAvailable()) {
                LOG.info("Air is not installed in this IDE; the Fennec agent is not registered");
                return;
            }
            register(Path.of(System.getProperty("user.home")),
                    Path.of(PathManager.getConfigPath()),
                    PathManager.getCommonDataPath(),
                    Path.of(PathManager.getSystemPath()),
                    pluginVersion());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Fennec Air agent registration failed: " + e.getMessage(), e);
        }
    }

    static void register(Path home, Path configDir, Path commonDataDir, Path systemDir, String version) throws IOException {
        Path bridge = home.resolve(".fennec").resolve("air").resolve("fennec-acp.py");
        installBridge(bridge);
        removeLegacyPickerEntry(home, bridge);

        // Launch detection kept for legacy/overlay use if needed.
        // Launch launch = detectAgent();

        JsonObject agent = registryEntry(version);
        Path registry = home.resolve(".fennec").resolve("air").resolve("registry.json");
        if (buildRegistry(registry, agent, systemDir)) {
            String registryPath = registry.toString().replace('\\', '/');
            System.setProperty(REGISTRY_PROPERTY, registryPath);
            ensureRegistryVmOption(configDir, registryPath);
            LOG.info("Fennec registry published at " + registry + " (" + registryPath + ")");
        } else {
            LOG.warning("No cached Air registry found at " + systemDir.resolve("acp-agents")
                    + " and no previous Fennec registry to extend; leaving Air's catalog untouched");
        }

        // Let Air manage install/update via the registry distribution.
        // writeInstalled(commonDataDir, agent);
        // writeOverlay(home.resolve(".jetbrains").resolve("acp-registry-installed-overlay.json"), bridge, launch);
    }

    private static boolean airAvailable() {
        try {
            return PluginManagerCore.getPlugin(PluginId.getId(AIR_PLUGIN_ID)) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String pluginVersion() {
        try {
            IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
            if (descriptor != null && descriptor.getVersion() != null) return descriptor.getVersion();
        } catch (RuntimeException ignored) {
        }
        return FALLBACK_VERSION;
    }

    private static JsonObject registryEntry(String version) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", AGENT_ID);
        entry.addProperty("name", AGENT_NAME);
        entry.addProperty("version", version);
        entry.addProperty("description",
                "Coding agent backed by the Fennec model on this machine. "
                        + "The engine starts with the first prompt and stops after it has been idle.");
        entry.addProperty("website", REPOSITORY);
        entry.addProperty("repository", REPOSITORY);
        JsonArray authors = new JsonArray();
        authors.add("Fennec Desktop");
        entry.add("authors", authors);
        entry.addProperty("license", "proprietary");

        JsonObject win = new JsonObject();
        win.addProperty("archive", "https://github.com/Liso7150/fennec-jetbrains/releases/download/v" + version + "/fennec-acp-windows-x86_64.zip");
        win.addProperty("sha256", "d218cab56f18b6d94ba56479759e9cc7d7d1eee2ea3ff0f75009510efccabc37");
        win.addProperty("cmd", "fennec-acp.cmd");
        JsonObject binary = new JsonObject();
        binary.add("windows-x86_64", win);
        JsonObject distribution = new JsonObject();
        distribution.add("binary", binary);
        entry.add("distribution", distribution);
        return entry;
    }

    /**
     * Air's registry override replaces the whole catalog, so the cached
     * remote registry is merged with the Fennec entry instead of replaced.
     */
    private static boolean buildRegistry(Path target, JsonObject agent, Path systemDir) throws IOException {
        JsonObject base = readNewestCachedRegistry(systemDir.resolve("acp-agents"));
        if (base == null && Files.isRegularFile(target)) {
            try {
                base = readJson(target);
            } catch (IOException e) {
                LOG.warning("Ignoring the previous Fennec registry: " + e.getMessage());
            }
        }
        if (base == null) return false;

        JsonArray merged = new JsonArray();
        if (base.has("agents") && base.get("agents").isJsonArray()) {
            for (JsonElement item : base.getAsJsonArray("agents")) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                String id = string(object.get("id"));
                if (!AGENT_ID.equals(id) && !LEGACY_AGENT_ID.equals(id)) merged.add(object);
            }
        }
        merged.add(agent);

        JsonObject root = new JsonObject();
        root.addProperty("version", stringOr(base.get("version"), "1.0.0"));
        root.add("agents", merged);
        writeJsonIfChanged(target, root);
        return true;
    }

    /**
     * The installed overlay is the only place Air accepts a local command
     * distribution; the registry models it as transient by design.
     */
    private static void writeOverlay(Path file, Path bridge, Launch launch) {
        JsonObject root;
        try {
            root = Files.isRegularFile(file) ? readJson(file) : new JsonObject();
        } catch (IOException e) {
            LOG.warning("Leaving " + file + " untouched: " + e.getMessage());
            return;
        }

        JsonObject local = new JsonObject();
        local.addProperty("command", SharedConfig.pythonForBridge());
        JsonArray args = new JsonArray();
        args.add(bridge.toString());
        args.add("--");
        for (String part : launch.command()) args.add(part);
        local.add("args", args);
        JsonObject env = new JsonObject();
        if (launch.launcher() != null) {
            env.addProperty("QWEN_CODE_LAUNCHER_PATH", launch.launcher().toString());
        }
        local.add("env", env);

        JsonObject distribution = new JsonObject();
        distribution.add("local", local);
        JsonObject agent = new JsonObject();
        agent.add("distribution", distribution);
        JsonObject entry = new JsonObject();
        entry.add("agent", agent);

        JsonObject agents = object(root, "agents");
        agents.add(INSTALL_KEY, entry);
        agents.remove(LEGACY_INSTALL_KEY);
        root.add("agents", agents);
        try {
            if (writeJsonIfChanged(file, root)) LOG.info("Fennec launch overlay updated in " + file);
        } catch (IOException e) {
            LOG.warning("Could not write " + file + ": " + e.getMessage());
        }
    }

    /**
     * A record in Air's shared installed.json is what turns a registry entry
     * card into an installed agent that can be enabled, disabled or removed.
     */
    private static void writeInstalled(Path commonDataDir, JsonObject agent) {
        Path file = commonDataDir.resolve("acp-agents").resolve("installed.json");
        JsonObject root;
        try {
            root = Files.isRegularFile(file) ? readJson(file) : new JsonObject();
        } catch (IOException e) {
            LOG.warning("Leaving " + file + " untouched: " + e.getMessage());
            return;
        }

        JsonObject agents = object(root, "agents");
        JsonObject existing = agents.has(INSTALL_KEY) && agents.get(INSTALL_KEY).isJsonObject()
                ? agents.getAsJsonObject(INSTALL_KEY) : null;
        long now = System.currentTimeMillis();
        long installedAt = existing != null ? longOr(existing.get("installedAt"), now) : now;
        long lastUsedAt = existing != null ? longOr(existing.get("lastUsedAt"), now) : now;

        JsonObject record = new JsonObject();
        record.add("agent", agent);
        record.addProperty("installedAt", installedAt);
        record.addProperty("lastUsedAt", lastUsedAt);
        record.add("acceptedTermsOfServiceVersion", JsonNull.INSTANCE);
        record.addProperty("isExplicitlyInstalled", true);
        record.addProperty("autoUpdatePolicy", "DEFAULT");
        record.add("skippedVersion", JsonNull.INSTANCE);
        agents.add(INSTALL_KEY, record);
        agents.remove(LEGACY_INSTALL_KEY);
        root.add("agents", agents);
        if (!root.has("disabledAgents") || !root.get("disabledAgents").isJsonArray()) {
            root.add("disabledAgents", new JsonArray());
        }
        if (!root.has("acceptedTermsOfServiceVersions") || !root.get("acceptedTermsOfServiceVersions").isJsonObject()) {
            root.add("acceptedTermsOfServiceVersions", new JsonObject());
        }

        try {
            if (writeJsonIfChanged(file, root)) LOG.info("Fennec recorded as installed in " + file);
        } catch (IOException e) {
            LOG.warning("Could not write " + file + ": " + e.getMessage());
        }
    }

    /**
     * The registry override is a system property; persisting it in the IDE's
     * user vmoptions file makes Air read the merged catalog on every start.
     */
    private static void ensureRegistryVmOption(Path configDir, String registryPath) throws IOException {
        Path file = vmOptionsFile(configDir);
        String option = "-D" + REGISTRY_PROPERTY + "=" + registryPath;
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String newline = text.contains("\r\n") ? "\r\n" : "\n";

        List<String> lines = new ArrayList<>();
        if (!text.isEmpty()) lines.addAll(Arrays.asList(text.split("\r?\n")));
        boolean present = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("-D" + REGISTRY_PROPERTY + "=")) {
                lines.set(i, option);
                present = true;
            }
        }
        if (!present) {
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            lines.add(option);
        }

        String updated = String.join(newline, lines) + newline;
        if (updated.equals(text)) return;
        backupOnce(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        LOG.info("Air registry override registered in " + file + " (applies on the next IDE start)");
    }

    private static Path vmOptionsFile(Path configDir) {
        String current = System.getProperty("jb.vmOptionsFile");
        if (current != null && !current.isBlank()) {
            Path name = Path.of(current).getFileName();
            if (name != null && name.toString().endsWith(".vmoptions")) return configDir.resolve(name.toString());
        }
        try (Stream<Path> files = Files.list(configDir)) {
            Path existing = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".vmoptions"))
                    .min(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
            if (existing != null) return existing;
        } catch (IOException ignored) {
        }
        String name = SystemInfo.isWindows ? "idea64.exe.vmoptions"
                : SystemInfo.isMac ? "idea.vmoptions" : "idea64.vmoptions";
        return configDir.resolve(name);
    }

    private static void installBridge(Path target) throws IOException {
        byte[] content;
        try (InputStream in = AirAgentRegistrar.class.getResourceAsStream("/fennec/air/fennec-acp.py")) {
            if (in == null) throw new IOException("fennec-acp.py is missing from the plugin");
            content = in.readAllBytes();
        }
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) return;
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        LOG.info("Installed the Fennec bridge at " + target);
    }

    /**
     * 1.3.0 registered itself through acp.json under the pre-rebrand name
     * "Colibri"; such an entry would linger in Air's picker next to the registry
     * card. Only entries that point at the managed bridge are removed - other
     * custom agents are left alone.
     */
    private static void removeLegacyPickerEntry(Path home, Path bridge) {
        Path file = home.resolve(".jetbrains").resolve("acp.json");
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = readJson(file);
            if (!root.has("agent_servers") || !root.get("agent_servers").isJsonObject()) return;
            JsonObject servers = root.getAsJsonObject("agent_servers");
            if (!servers.has("Colibri") || !servers.get("Colibri").isJsonObject()) return;
            JsonObject entry = servers.getAsJsonObject("Colibri");
            if (!pointsAtBridge(entry, bridge)) return;
            backupOnce(file);
            servers.remove("Colibri");
            writeJson(file, root);
            LOG.info("Removed the legacy Colibri picker entry from " + file);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not clean " + file + ": " + e.getMessage());
        }
    }

    private static boolean pointsAtBridge(JsonObject entry, Path bridge) {
        String needle = normalizePath(bridge.toString());
        for (String arg : strings(entry.get("args"))) {
            if (normalizePath(arg).equals(needle)) return true;
        }
        return false;
    }

    private static String normalizePath(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static JsonObject readNewestCachedRegistry(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        List<Path> candidates;
        try (Stream<Path> files = Files.list(dir)) {
            candidates = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("registry-") && name.endsWith(".json");
                    })
                    .sorted(Comparator.comparingLong(AirAgentRegistrar::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            LOG.warning("Could not list " + dir + ": " + e.getMessage());
            return null;
        }
        for (Path candidate : candidates) {
            try {
                return readJson(candidate);
            } catch (IOException e) {
                LOG.warning("Ignoring unreadable registry cache " + candidate + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Launch detectAgent() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path dir = Path.of(localAppData, "qwen-code", "qwen-code");
            Path node = dir.resolve("node").resolve(SharedConfig.isWindows() ? "node.exe" : "node");
            Path script = dir.resolve("lib").resolve("cli-entry.js");
            Path launcher = dir.resolve("bin").resolve(SharedConfig.isWindows() ? "qwen.cmd" : "qwen");
            if (Files.isRegularFile(node) && Files.isRegularFile(script)) {
                return new Launch(new ArrayList<>(List.of(node.toString(), script.toString(), "--acp")),
                        Files.isRegularFile(launcher) ? launcher : null);
            }
        }
        if (SharedConfig.isWindows()) {
            String qwenExe = findOnPath("qwen.exe");
            if (qwenExe != null) return new Launch(new ArrayList<>(List.of(qwenExe, "--acp")), null);
            String qwenCmd = findOnPath("qwen.cmd");
            if (qwenCmd != null) {
                return new Launch(new ArrayList<>(List.of("cmd.exe", "/c", qwenCmd, "--acp")), Path.of(qwenCmd));
            }
        } else {
            String qwen = findOnPath("qwen");
            if (qwen != null) return new Launch(new ArrayList<>(List.of(qwen, "--acp")), Path.of(qwen));
        }
        return null;
    }

    private static String findOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isRegularFile(candidate)) return candidate.toString();
        }
        return null;
    }

    private static JsonObject readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IOException("not a JSON object: " + file);
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("cannot parse " + file + ": " + e.getMessage(), e);
        }
    }

    private static JsonObject object(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonObject()) return root.getAsJsonObject(key);
        JsonObject created = new JsonObject();
        root.add(key, created);
        return created;
    }

    private static List<String> strings(JsonElement element) {
        List<String> values = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) values.add(item.getAsString());
            }
        }
        return values;
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : "";
    }

    private static String stringOr(JsonElement element, String fallback) {
        String value = string(element);
        return value.isBlank() ? fallback : value;
    }

    private static long longOr(JsonElement element, long fallback) {
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean writeJsonIfChanged(Path file, JsonObject root) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                if (readJson(file).equals(root)) return false;
            } catch (IOException ignored) {
            }
        }
        writeJson(file, root);
        return true;
    }

    private static void backupOnce(Path file) {
        if (!Files.exists(file)) return;
        Path backup = file.resolveSibling(file.getFileName() + ".bak-fennec");
        if (Files.exists(backup)) return;
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warning("Could not back up " + file + ": " + e.getMessage());
        }
    }

    private static void writeJson(Path file, JsonObject root) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-fennec");
        String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
                + System.lineSeparator();
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Launch(List<String> command, Path launcher) {}
}
