package colibri.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** HTTP/SSE client using only the JDK and the platform's bundled Gson. */
public final class ColibriClient {
    public interface Callback {
        void onDelta(String text);
        void onReasoning(String text);
        void onDone();
        void onError(String message);
    }

    /** Owns both discovery and streaming. No callbacks begin after cancel returns. */
    public static final class StreamHandle implements AutoCloseable {
        private boolean cancelled;
        private boolean terminal;
        private HttpURLConnection connection;
        private Thread worker;
        private final CountDownLatch terminated = new CountDownLatch(1);

        public void cancel() {
            HttpURLConnection c;
            Thread t;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                c = connection;
                connection = null;
                t = worker;
            }
            // Virtual-thread socket I/O is interruptible, including an idle SSE read.
            if (t != null) t.interrupt();
            // disconnect can wait for an in-flight read; never do that on the EDT.
            if (c != null) Thread.ofVirtual().name("Colibri-disconnect").start(c::disconnect);
        }

        @Override
        public void close() { cancel(); }

        public synchronized boolean isCancelled() { return cancelled; }

        private synchronized void attach(HttpURLConnection c) throws IOException {
            if (cancelled) throw new IOException("request cancelled");
            connection = c;
        }

        private void release(HttpURLConnection c) {
            synchronized (this) {
                if (connection == c) connection = null;
            }
            if (c != null) c.disconnect();
        }

        private synchronized void delta(Callback callback, String text) {
            if (!cancelled && !terminal) callback.onDelta(text);
        }

        private synchronized void reasoning(Callback callback, String text) {
            if (!cancelled && !terminal) callback.onReasoning(text);
        }

        private synchronized void finish(Callback callback, String error) {
            if (cancelled || terminal) return;
            terminal = true;
            if (error == null) callback.onDone();
            else callback.onError(error);
        }

        boolean awaitTermination(long milliseconds) throws InterruptedException {
            return terminated.await(milliseconds, TimeUnit.MILLISECONDS);
        }
    }

    private ColibriClient() {}

    private static HttpURLConnection connect(String baseUrl, String path, String apiKey,
                                             int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl.replaceAll("/+$", "") + path).openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(false);
        if (!apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
        return c;
    }

    /** Blocking helper: callers must not invoke this on the EDT. */
    public static String resolveModelId(String baseUrl, String fallback, String apiKey) {
        return resolveModelId(baseUrl, fallback, apiKey, new StreamHandle());
    }

    private static String resolveModelId(String baseUrl, String fallback, String apiKey, StreamHandle handle) {
        HttpURLConnection c = null;
        try {
            c = connect(baseUrl, "/v1/models", apiKey, 2000, 3000);
            handle.attach(c);
            if (c.getResponseCode() != 200) return fallback;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                JsonArray data = JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("data");
                if (data != null) {
                    for (JsonElement item : data) {
                        if (!item.isJsonObject()) continue;
                        JsonElement id = item.getAsJsonObject().get("id");
                        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                                && !id.getAsString().isBlank()) return id.getAsString();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Offline/older engines can still accept the explicitly configured model id.
        } finally {
            handle.release(c);
        }
        return fallback;
    }

    public static boolean isHealthy(String baseUrl, String apiKey) {
        return healthStatus(baseUrl, apiKey) == 200;
    }

    /** -1 means no HTTP response; an unhealthy responding server must not be replaced. */
    static int healthStatus(String baseUrl, String apiKey) {
        HttpURLConnection c = null;
        try {
            c = connect(baseUrl, "/health", apiKey, 1200, 1500);
            return c.getResponseCode();
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Discovery and tokens run off the EDT; cancel covers either phase. */
    public static StreamHandle streamChat(String baseUrl, String fallbackModel, List<JsonObject> messages,
                                          double temperature, int maxTokens, String apiKey, Callback callback) {
        StreamHandle handle = new StreamHandle();
        JsonArray snapshot = new JsonArray();
        for (JsonObject message : messages) snapshot.add(message.deepCopy());
        handle.worker = Thread.ofVirtual().name("Colibri-stream").unstarted(() -> {
            HttpURLConnection conn = null;
            try {
                String model = resolveModelId(baseUrl, fallbackModel, apiKey, handle);
                if (handle.isCancelled()) return;
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("stream", true);
                body.addProperty("temperature", temperature);
                body.addProperty("max_tokens", maxTokens);
                body.add("messages", snapshot);

                conn = connect(baseUrl, "/v1/chat/completions", apiKey, 5000, 600000);
                handle.attach(conn);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }

                int code = conn.getResponseCode();
                if (code != 200) {
                    String detail = "";
                    if (conn.getErrorStream() != null) {
                        try (var err = conn.getErrorStream()) {
                            detail = new String(err.readNBytes(600), StandardCharsets.UTF_8);
                        }
                    }
                    handle.finish(callback, "engine returned " + code + ": " + detail);
                    return;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder event = new StringBuilder();
                    String line;
                    while (!handle.isCancelled() && (line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (dispatch(event.toString(), handle, callback)) return;
                            event.setLength(0);
                        } else if (line.startsWith("data:")) {
                            if (event.length() > 0) event.append('\n');
                            event.append(line.substring(5).stripLeading());
                        }
                    }
                    if (dispatch(event.toString(), handle, callback)) return;
                }
                handle.finish(callback, "engine stream ended before [DONE]");
            } catch (IOException | RuntimeException e) {
                handle.finish(callback, "cannot reach the Colibri engine at " + baseUrl + " (" + e.getMessage() + ")");
            } finally {
                handle.release(conn);
                handle.terminated.countDown();
            }
        });
        handle.worker.start();
        return handle;
    }

    private static boolean dispatch(String payload, StreamHandle handle, Callback callback) {
        if ("[DONE]".equals(payload.trim())) {
            handle.finish(callback, null);
            return true;
        }
        String text = null;
        String reasoning = null;
        try {
            if (payload.isBlank()) return false;
            JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return false;
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            if (delta != null) {
                JsonElement content = delta.get("content");
                if (content != null && !content.isJsonNull()) text = content.getAsString();
                JsonElement thought = delta.has("reasoning_content") ? delta.get("reasoning_content") : delta.get("reasoning");
                if (thought != null && !thought.isJsonNull()) reasoning = thought.getAsString();
            }
        } catch (RuntimeException ignored) {
            // Ignore malformed/keep-alive events, not callback failures.
        }
        if (reasoning != null && !reasoning.isEmpty()) handle.reasoning(callback, reasoning);
        if (text != null && !text.isEmpty()) handle.delta(callback, text);
        return false;
    }

    public static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
