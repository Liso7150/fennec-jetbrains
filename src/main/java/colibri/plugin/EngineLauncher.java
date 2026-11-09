package colibri.plugin;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Starts the Colibri engine for the plugin when the IDE opens it.
 *
 * The engine is a normal child process (python openai_server.py). It is left
 * running when the IDE closes: the desktop app may be using it too, and Colibri
 * keeps its warm tier between sessions on purpose.
 */
public final class EngineLauncher {

    private static volatile Process process;

    private EngineLauncher() {}

    public static boolean start(@Nullable Consumer<String> status) {
        ColibriSettings.State cfg = ColibriSettings.getInstance().getState();
        if (ColibriClient.isHealthy(cfg.engineUrl)) {
            if (status != null) status.accept("engine already running");
            return true;
        }
        if (cfg.modelDir == null || cfg.modelDir.isBlank()) {
            if (status != null) status.accept("set a model directory in Settings → Colibri Code");
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.python);
        cmd.add("openai_server.py");
        cmd.add("--model");
        cmd.add(cfg.modelDir);
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(portOf(cfg.engineUrl)));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(cfg.runtime));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                new File(System.getProperty("java.io.tmpdir"), "colibri-plugin-engine.log")));
        try {
            process = pb.start();
            if (status != null) status.accept("starting engine (first load can take minutes)…");
            return true;
        } catch (IOException e) {
            if (status != null) status.accept("could not start the engine: " + e.getMessage());
            return false;
        }
    }

    public static boolean isRunning() {
        return process != null && process.isAlive();
    }

    private static int portOf(@NotNull String url) {
        try {
            String withoutScheme = url.substring(url.indexOf("://") + 3);
            int colon = withoutScheme.indexOf(':');
            if (colon < 0) return 80;
            String port = withoutScheme.substring(colon + 1).replaceAll("/.*$", "");
            return Integer.parseInt(port);
        } catch (RuntimeException e) {
            return 8765;
        }
    }
}
