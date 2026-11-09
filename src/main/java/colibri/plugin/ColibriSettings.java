package colibri.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Everything the plugin needs to find the local Colibri engine.
 *
 * Defaults point at the layout the desktop installer uses, so a fresh install
 * connects without opening the settings page.
 */
@State(name = "ColibriSettings", storages = @Storage("colibri-code.xml"))
public class ColibriSettings implements PersistentStateComponent<ColibriSettings.State> {

    public static class State {
        public String engineUrl = "http://127.0.0.1:8765";
        public String modelId = "qwen3-coder-30b-a3b-colibri";
        public String apiKey = System.getenv("COLI_API_KEY") == null ? "" : System.getenv("COLI_API_KEY");
        public String modelDir = "";
        public String python = "D:\\Colibri\\.venv\\Scripts\\python.exe";
        public String runtime = "D:\\colibri-models\\.colibri\\runtime\\colibri-v2.1.0";
        public boolean autoStart = false;
        public int maxTokens = 2048;
        public double temperature = 0.2;
    }

    public State state = new State();

    public static ColibriSettings getInstance() {
        return ApplicationManager.getApplication().getService(ColibriSettings.class);
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
