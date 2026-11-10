package colibri.plugin;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Starts the Colibri engine for the plugin when the IDE opens it.
 *
 * Uses the same launcher and serve flags as the rest of the suite, and only for
 * a local http endpoint. The child is left running when the IDE closes: the
 * desktop app may be using it too, and Colibri keeps its warm tier on purpose.
 */
public final class EngineLauncher {

    private static volatile Process process;

    private EngineLauncher() {}

    public static boolean start(@Nullable Consumer<String> status) {
        ColibriSettings.State cfg;
        try {
            cfg = ColibriSettings.getInstance().effectiveState();
        } catch (RuntimeException e) {
            report(status, "cannot read Colibri settings: " + e.getMessage());
            return false;
        }
        try {
            if (ColibriClient.isHealthy(cfg.engineUrl, cfg.apiKey)) {
                report(status, "engine already running");
                return true;
            }
            if (cfg.modelDir.isBlank() || !new File(cfg.modelDir).isDirectory()) {
                report(status, "set an installed model directory in Settings → Colibri Code");
                return false;
            }
            if (!new File(cfg.python).isFile()) {
                report(status, "Python runtime not found: " + cfg.python);
                return false;
            }
            File launcher = new File(cfg.runtime, "coli");
            if (!launcher.isFile()) {
                report(status, "Colibri launcher missing: " + launcher);
                return false;
            }
            URI uri = URI.create(cfg.engineUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !isLoopback(uri.getHost())) {
                report(status, "the engine URL is not a local http endpoint; start that server separately");
                return false;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(cfg.python);
            cmd.add(launcher.getPath());
            cmd.add("serve");
            cmd.add("--model");
            cmd.add(cfg.modelDir);
            cmd.add("--auto-tier");
            cmd.add("--policy");
            cmd.add(cfg.policy);
            cmd.add("--ctx");
            cmd.add(String.valueOf(cfg.ctx));
            cmd.add("--host");
            cmd.add(uri.getHost());
            cmd.add("--port");
            cmd.add(String.valueOf(uri.getPort() < 0 ? 80 : uri.getPort()));
            cmd.add("--api-key");
            cmd.add(cfg.apiKey);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(cfg.runtime));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                    new File(System.getProperty("java.io.tmpdir"), "colibri-plugin-engine.log")));
            pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
            pb.environment().putIfAbsent("PYTHONUTF8", "1");
            process = pb.start();
            report(status, "starting engine (first load can take minutes)…");
            return true;
        } catch (IOException | RuntimeException e) {
            report(status, "could not start the engine: " + e.getMessage());
            return false;
        }
    }

    public static boolean isRunning() {
        return process != null && process.isAlive();
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "::1".equals(host) || "[::1]".equals(host)
                || host.matches("127(\\.\\d{1,3}){3}");
    }

    private static void report(@Nullable Consumer<String> status, String text) {
        if (status != null) status.accept(text);
    }
}
