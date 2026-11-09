package colibri.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal streaming client for the Colibri engine's OpenAI-compatible endpoint.
 *
 * Deliberately dependency-free: HttpURLConnection plus Gson (bundled with the
 * platform) keeps the plugin loadable in any IntelliJ-based IDE without shipping
 * extra libraries.
 */
public final class ColibriClient {

    public interface Callback {
        void onDelta(@NotNull String text);
        void onDone();
        void onError(@NotNull String message);
    }

    private ColibriClient() {}

    /** The engine names its own model id; asking it avoids a 404 from a guessed name. */
    public static String resolveModelId(@NotNull String baseUrl, @NotNull String fallback, @NotNull String apiKey) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/v1/models").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            if (!apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
            if (c.getResponseCode() != 200) return fallback;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            c.disconnect();
            JsonObject root = JsonParser.parseString(body.toString()).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            if (data != null && !data.isEmpty()) {
                String id = data.get(0).getAsJsonObject().get("id").getAsString();
                if (id != null && !id.isEmpty()) return id;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static boolean isHealthy(@NotNull String baseUrl) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/health").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (IOException e) {
            return false;
        }
    }

    /** Streams a chat completion on a background thread; tokens arrive on that thread. */
    public static void streamChat(@NotNull String baseUrl,
                                  @NotNull String model,
                                  @NotNull List<JsonObject> messages,
                                  double temperature,
                                  int maxTokens,
                                  @NotNull String apiKey,
                                  @NotNull Callback callback) {
        Thread worker = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", model);
                body.addProperty("stream", true);
                body.addProperty("temperature", temperature);
                body.addProperty("max_tokens", maxTokens);
                JsonArray arr = new JsonArray();
                for (JsonObject m : messages) arr.add(m);
                body.add("messages", arr);

                conn = (HttpURLConnection) new URL(baseUrl + "/v1/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(600000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream");
                if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    String detail = "";
                    if (conn.getErrorStream() != null) {
                        try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = err.readLine()) != null) sb.append(line).append('\n');
                            detail = sb.toString();
                        }
                    }
                    callback.onError("engine returned " + code + ": " + truncate(detail));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String payload = line.substring(5).trim();
                        if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                        try {
                            JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
                            JsonArray choices = chunk.getAsJsonArray("choices");
                            if (choices == null || choices.isEmpty()) continue;
                            JsonObject choice = choices.get(0).getAsJsonObject();
                            JsonElement delta = choice.get("delta");
                            if (delta == null || !delta.isJsonObject()) continue;
                            JsonElement content = delta.getAsJsonObject().get("content");
                            if (content != null && !content.isJsonNull()) {
                                String text = content.getAsString();
                                if (!text.isEmpty()) callback.onDelta(text);
                            }
                        } catch (RuntimeException ignored) {
                            // a malformed keep-alive line must not end the answer
                        }
                    }
                }
                callback.onDone();
            } catch (IOException e) {
                callback.onError("cannot reach the Colibri engine at " + baseUrl + " (" + e.getMessage() + ")");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "Colibri-stream");
        worker.setDaemon(true);
        worker.start();
    }

    private static String truncate(String s) {
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    public static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
