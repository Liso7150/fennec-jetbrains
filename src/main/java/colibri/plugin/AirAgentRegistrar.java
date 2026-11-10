package colibri.plugin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs the managed ACP bridge and keeps Air's acp.json pointed at it.
 *
 * The plugin has no UI of its own: Colibri is an Air agent picked from Air's
 * agent menu. Registration is idempotent and never touches other servers.
 */
final class AirAgentRegistrar {
    private static final Logger LOG = Logger.getLogger("colibri.air");
    private static final String AGENT = "Colibri";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private AirAgentRegistrar() {}

    static void registerOnce() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            register(Path.of(System.getProperty("user.home")));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Colibri Air agent registration failed: " + e.getMessage(), e);
        }
    }

    static void register(Path home) throws IOException {
        Path bridge = home.resolve(".colibri").resolve("air").resolve("colibri-acp.py");
        installBridge(bridge);
        Path acpFile = home.resolve(".jetbrains").resolve("acp.json");
        JsonObject root = read(acpFile);
        JsonObject servers = object(root, "agent_servers");
        JsonObject existing = servers.has(AGENT) && servers.get(AGENT).isJsonObject()
                ? servers.getAsJsonObject(AGENT) : null;
        List<String> agentCommand = existing != null ? acpCommand(existing) : new ArrayList<>();
        if (agentCommand.isEmpty()) agentCommand = detectAgentCommand();
        if (agentCommand.isEmpty()) {
            LOG.warning("Colibri Air agent not registered: no ACP agent command found. "
                    + "Install Qwen Code or put the command after -- in " + acpFile);
            return;
        }
        JsonObject entry = existing != null ? existing.deepCopy() : new JsonObject();
        entry.addProperty("command", SharedConfig.pythonForBridge());
        JsonArray args = new JsonArray();
        args.add(bridge.toString());
        args.add("--");
        for (String part : agentCommand) args.add(part);
        entry.add("args", args);
        if (entry.equals(existing)) return;
        backup(acpFile);
        JsonObject updated = new JsonObject();
        for (var member : servers.entrySet()) {
            if (!member.getKey().equals(AGENT)) updated.add(member.getKey(), member.getValue());
        }
        updated.add(AGENT, entry);
        root.add("agent_servers", updated);
        write(acpFile, root);
        LOG.info("Colibri Air agent registered in " + acpFile);
    }

    private static void installBridge(Path target) throws IOException {
        byte[] content;
        try (InputStream in = AirAgentRegistrar.class.getResourceAsStream("/colibri/air/colibri-acp.py")) {
            if (in == null) throw new IOException("colibri-acp.py is missing from the plugin");
            content = in.readAllBytes();
        }
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) return;
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        LOG.info("Installed the Colibri bridge at " + target);
    }

    private static JsonObject read(Path file) throws IOException {
        if (!Files.exists(file)) {
            JsonObject root = new JsonObject();
            root.add("agent_servers", new JsonObject());
            return root;
        }
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

    /** The ACP agent command: everything after --, or a direct command+args entry. */
    private static List<String> acpCommand(JsonObject entry) {
        List<String> args = strings(entry.get("args"));
        int separator = args.indexOf("--");
        if (separator >= 0) return new ArrayList<>(args.subList(separator + 1, args.size()));
        List<String> command = new ArrayList<>();
        String executable = string(entry.get("command"));
        if (!executable.isBlank()) command.add(executable);
        command.addAll(args);
        return command;
    }

    private static List<String> detectAgentCommand() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path dir = Path.of(localAppData, "qwen-code", "qwen-code");
            Path node = dir.resolve("node").resolve(SharedConfig.isWindows() ? "node.exe" : "node");
            Path script = dir.resolve("lib").resolve("cli-entry.js");
            if (Files.isRegularFile(node) && Files.isRegularFile(script)) {
                return new ArrayList<>(List.of(node.toString(), script.toString(), "--acp"));
            }
        }
        if (SharedConfig.isWindows()) {
            String qwenExe = findOnPath("qwen.exe");
            if (qwenExe != null) return new ArrayList<>(List.of(qwenExe, "--acp"));
            String qwenCmd = findOnPath("qwen.cmd");
            if (qwenCmd != null) return new ArrayList<>(List.of("cmd.exe", "/c", qwenCmd, "--acp"));
        } else {
            String qwen = findOnPath("qwen");
            if (qwen != null) return new ArrayList<>(List.of(qwen, "--acp"));
        }
        return new ArrayList<>();
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

    private static void backup(Path file) {
        if (!Files.exists(file)) return;
        Path backup = file.resolveSibling("acp.json.bak-colibri");
        if (Files.exists(backup)) return;
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warning("Could not back up " + file + ": " + e.getMessage());
        }
    }

    private static void write(Path file, JsonObject root) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("acp.json.tmp-colibri");
        String text = new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
