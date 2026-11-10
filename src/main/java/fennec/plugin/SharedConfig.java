package fennec.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** The shared Fennec configuration Air needs: where the engine's Python lives. */
final class SharedConfig {
    private SharedConfig() {}

    static String pythonForBridge() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String configured = env("FENNEC_CONFIG");
        Path location = configured != null ? resolve(configured, home) : home.resolve(".fennec/config.json");
        JsonObject data = read(location);
        String rootValue = env("FENNEC_HOME");
        if (rootValue == null) rootValue = env("FENNEC_ENGINE_ROOT");
        if (rootValue == null) rootValue = text(data, "engine_root", home.resolve("Colibri").toString());
        Path root = resolve(rootValue, home);
        Path venv = root.resolve(isWindows() ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        String python = env("FENNEC_PYTHON");
        if (python == null) {
            python = text(data, "python",
                    Files.isRegularFile(venv) ? venv.toString() : isWindows() ? "python.exe" : "python3");
        }
        if (python.contains("/") || python.contains("\\") || python.startsWith("~")) {
            python = resolve(python, home).toString();
        }
        return python;
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static JsonObject read(Path location) {
        if (!Files.exists(location)) {
            if (env("FENNEC_CONFIG") != null) {
                throw new IllegalArgumentException("Fennec configuration does not exist: " + location);
            }
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(location, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Fennec configuration must be a JSON object: " + location);
            }
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read Fennec configuration: " + location, e);
        }
    }

    private static String text(JsonObject data, String key, String fallback) {
        JsonElement value = data.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Fennec configuration field must be a string: " + key);
        }
        return value.getAsString();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static Path resolve(String value, Path home) {
        if (value.equals("~")) return home;
        if (value.startsWith("~/") || value.startsWith("~\\")) value = home.resolve(value.substring(2)).toString();
        Path result = Path.of(value);
        return (result.isAbsolute() ? result : home.resolve(result)).toAbsolutePath().normalize();
    }
}
