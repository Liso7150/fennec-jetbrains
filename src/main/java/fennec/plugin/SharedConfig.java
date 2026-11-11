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

    /**
     * The interpreter the overlay spawns, resolved the way fennec-core resolves
     * its own config: a blank or bare value is searched on PATH, a path is made
     * absolute, and the answer is never an empty string Air cannot exec.
     */
    static String pythonForLauncher() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String configured = env("FENNEC_CONFIG");
        Path location = configured != null ? resolve(configured, home) : home.resolve(".fennec/config.json");
        JsonObject data = read(location);
        String python = env("FENNEC_PYTHON");
        if (python == null) {
            JsonElement value = data.get("python");
            if (value != null && !value.isJsonNull()) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Fennec configuration field must be a string: python");
                }
                python = value.getAsString();
            }
        }
        if (python == null || python.isBlank()) {
            python = search(isWindows() ? new String[] {"python.exe", "python3.exe"}
                                        : new String[] {"python3", "python"});
        } else if (!python.startsWith("~") && !isPathLike(python)) {
            python = search(new String[] {python});
        } else {
            python = resolve(python, home).toString();
        }
        return python;
    }

    private static boolean isPathLike(String value) {
        return value.contains("/") || value.contains("\\");
    }

    /** A bare name is what the config stores; PATH is what makes it an executable. */
    private static String search(String[] names) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) continue;
                for (String name : names) {
                    Path candidate = Path.of(directory).resolve(name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().normalize().toString();
                    }
                }
            }
        }
        return names[0];
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
