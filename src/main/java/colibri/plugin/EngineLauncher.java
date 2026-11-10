package colibri.plugin;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the Colibri engine on demand: started for the first request, stopped
 * when the IDE closes if this plugin owns the process. An engine that was
 * already running (desktop app, user shell) is attached to, never stopped.
 */
public final class EngineLauncher {

    private static final long STARTUP_TIMEOUT_MS = 15 * 60 * 1000L;
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

    /** Attach when healthy, otherwise start and wait until the endpoint answers. */
    public static synchronized boolean ensureRunning(@Nullable Consumer<String> status) {
        ColibriSettings.State cfg;
        try {
            cfg = ColibriSettings.getInstance().effectiveState();
        } catch (RuntimeException e) {
            report(status, "cannot read Colibri settings: " + e.getMessage());
            return false;
        }
        if (ColibriClient.isHealthy(cfg.engineUrl, cfg.apiKey)) {
            report(status, "engine ready");
            return true;
        }
        if (process == null || !process.isAlive()) {
            if (!start(status)) return false;
        } else {
            report(status, "waiting for the engine to load…");
        }
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        int tick = 0;
        while (System.currentTimeMillis() < deadline) {
            if (ColibriClient.isHealthy(cfg.engineUrl, cfg.apiKey)) {
                report(status, "engine ready");
                return true;
            }
            if (process == null || !process.isAlive()) {
                report(status, "the engine exited — see " + new File(System.getProperty("java.io.tmpdir"), "colibri-plugin-engine.log"));
                return false;
            }
            if (++tick % 5 == 0) report(status, "loading model… (" + (tick * 2) + "s)");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                report(status, "engine start interrupted");
                return false;
            }
        }
        report(status, "engine did not become ready in time");
        return false;
    }

    /** Stop only a process this plugin spawned; attached engines are left alone. */
    public static synchronized void stopOwned() {
        Process owned = process;
        if (owned == null) return;
        process = null;
        if (!owned.isAlive()) return;
        owned.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (RuntimeException ignored) {
                // already gone
            }
        });
        owned.destroyForcibly();
        try {
            owned.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(10),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
