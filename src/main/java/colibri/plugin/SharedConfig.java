package colibri.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only shared configuration. Paths are never tied to a developer's drive. */
final class SharedConfig {
    final String engineRoot;
    final String python;
    final String model;
    final String engineUrl;
    final String apiKey;
    final String policy;
    final String backend;
    final int ctx;

    private SharedConfig(String engineRoot, String python, String model, String engineUrl, String apiKey,
                         String policy, String backend, int ctx) {
        this.engineRoot = engineRoot;
        this.python = python;
        this.model = model;
        this.engineUrl = engineUrl;
        this.apiKey = apiKey;
        this.policy = policy;
        this.backend = backend;
        this.ctx = ctx;
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static SharedConfig load() {
        return load(System.getenv(), Path.of(System.getProperty("user.home")));
    }

    static SharedConfig load(Map<String, String> env, Path home) {
        home = home.toAbsolutePath().normalize();
        Path location = path(env.getOrDefault("COLIBRI_CONFIG", home.resolve(".colibri/config.json").toString()),
                home, Path.of("").toAbsolutePath());
        JsonObject data = new JsonObject();
        if (Files.exists(location)) {
            try (Reader reader = Files.newBufferedReader(location, StandardCharsets.UTF_8)) {
                data = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("Cannot read Colibri configuration: " + location);
            }
        } else if (env.containsKey("COLIBRI_CONFIG")) {
            throw new IllegalArgumentException("Colibri configuration does not exist: " + location);
        }
        Path base = location.getParent();
        String rootValue = value(data, env, "engine_root", "COLIBRI_HOME", home.resolve("Colibri").toString());
        // COLIBRI_HOME is the suite convention; accept the descriptive alias too.
        rootValue = env.getOrDefault("COLIBRI_ENGINE_ROOT", rootValue);
        Path root = path(rootValue, home, base);
        Path models = path(value(data, env, "models_root", "COLIBRI_MODELS", home.resolve("colibri-models").toString()), home, base);
        String model = value(data, env, "model", "COLIBRI_MODEL", "");
        if (!model.isBlank()) model = path(model, home, models).toString();
        Path venv = root.resolve(isWindows() ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        String python = value(data, env, "python", "COLIBRI_PYTHON",
                Files.isRegularFile(venv) ? venv.toString() : isWindows() ? "pythonw.exe" : "python3");
        if (python.contains("/") || python.contains("\\") || python.startsWith("~")) {
            python = path(python, home, base).toString();
        }
        String url = value(data, env, "engine_url", "COLIBRI_ENGINE_URL", "http://127.0.0.1:8184");
        String key = env.getOrDefault("COLIBRI_API_KEY", env.getOrDefault("COLI_API_KEY", "local"));
        if (key.isBlank()) key = "local";
        String policy = text(data, "policy", "quality");
        if (!Set.of("quality", "balanced", "experimental-fast").contains(policy)) {
            throw new IllegalArgumentException("Colibri configuration policy must be quality, balanced or experimental-fast");
        }
        String backend = value(data, env, "backend", "COLIBRI_BACKEND", "auto").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "vulkan", "cuda", "metal", "cpu").contains(backend)) {
            throw new IllegalArgumentException("Colibri configuration backend must be auto, vulkan, cuda, metal or cpu");
        }
        int ctx = 0;
        JsonElement ctxValue = data.get("ctx");
        if (ctxValue != null) {
            if (!ctxValue.isJsonPrimitive() || !ctxValue.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Colibri configuration field must be an integer: ctx");
            }
            ctx = ctxValue.getAsInt();
            if (ctx < 0 || ctx > 1048576) {
                throw new IllegalArgumentException("Colibri configuration ctx must be 0..1048576");
            }
        }
        return new SharedConfig(root.toString(), python, model, url.replaceAll("/+$", ""), key, policy, backend, ctx);
    }

    private static String value(JsonObject data, Map<String, String> env, String key, String variable, String fallback) {
        if (env.containsKey(variable)) return env.get(variable);
        return text(data, key, fallback);
    }

    private static String text(JsonObject data, String key, String fallback) {
        JsonElement value = data.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Colibri configuration field must be a string: " + key);
        }
        return value.getAsString();
    }

    private static Path path(String value, Path home, Path base) {
        if (value.equals("~")) return home;
        if (value.startsWith("~/") || value.startsWith("~\\")) value = home.resolve(value.substring(2)).toString();
        Path result = Path.of(value);
        return (result.isAbsolute() ? result : base.resolve(result)).toAbsolutePath().normalize();
    }
}
