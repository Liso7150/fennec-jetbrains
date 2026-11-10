package colibri.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Plugin overrides on top of the suite's shared configuration.
 *
 * Every field is optional: blank means "use colibri-core's config.json and the
 * COLIBRI_* environment", so one machine configures the desktop app, the CLI
 * and this plugin at once. Nothing here assumes a developer's drive letter.
 */
@State(name = "ColibriSettings", storages = @Storage("colibri-code.xml"))
public class ColibriSettings implements PersistentStateComponent<ColibriSettings.State> {

    public static class State {
        public String engineUrl = "";
        public String modelId = "";
        public String apiKey = "";
        public String modelDir = "";
        public String python = "";
        public String runtime = "";
        public String policy = "";
        public String backend = "";
        public int ctx = -1;
        public boolean autoStart = false;
        public int maxTokens = 4096;
        public double temperature = 0.2;
    }

    // Values written by the first 1.0.0 build; they pinned a drive and port that
    // the suite no longer uses, so treat them as "not configured".
    private static final Set<String> LEGACY = Set.of(
            "http://127.0.0.1:8765",
            "D:\\Colibri\\.venv\\Scripts\\python.exe",
            "D:\\colibri-models\\.colibri\\runtime\\colibri-v2.1.0",
            "qwen3-coder-30b-a3b-colibri");

    public State state = new State();

    public static ColibriSettings getInstance() {
        return ApplicationManager.getApplication().getService(ColibriSettings.class);
    }

    /** Settings resolved against the shared configuration; never returns blanks. */
    public State effectiveState() {
        SharedConfig shared;
        try {
            shared = SharedConfig.load();
        } catch (IllegalArgumentException broken) {
            // Keep the plugin usable; the engine check will surface the real error.
            shared = SharedConfig.load(Map.of(), java.nio.file.Path.of(System.getProperty("user.home")));
        }
        State raw = state == null ? new State() : state;
        State resolved = new State();
        resolved.engineUrl = trimUrl(override(raw.engineUrl, shared.engineUrl));
        resolved.modelId = override(raw.modelId, "");
        resolved.apiKey = raw.apiKey != null && !raw.apiKey.isBlank() ? raw.apiKey : shared.apiKey;
        resolved.modelDir = override(raw.modelDir, shared.model);
        resolved.python = override(raw.python, shared.python);
        resolved.runtime = override(raw.runtime, shared.engineRoot + File.separator + "c");
        resolved.policy = raw.policy != null && !raw.policy.isBlank() ? raw.policy : shared.policy;
        resolved.backend = raw.backend != null && !raw.backend.isBlank() ? raw.backend : shared.backend;
        resolved.ctx = raw.ctx >= 0 ? raw.ctx : shared.ctx;
        resolved.autoStart = raw.autoStart;
        resolved.maxTokens = raw.maxTokens;
        resolved.temperature = raw.temperature;
        return resolved;
    }

    private static String override(String raw, String fallback) {
        if (raw == null || raw.isBlank() || LEGACY.contains(raw.trim())) return fallback;
        return raw.trim();
    }

    private static String trimUrl(String url) {
        return url.replaceAll("/+$", "");
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
