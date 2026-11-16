package fennec.plugin;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.updateSettings.impl.UpdateChecker;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Auto-update checker for Fennec JetBrains plugin.
 * Checks GitHub releases for updates and notifies the user.
 */
public class Updater {
    private static final Logger LOG = Logger.getInstance(Updater.class);
    private static final String GITHUB_REPO = "Liso7150/fennec-jetbrains";
    private static final String CURRENT_VERSION = "1.0.0";
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    public static class Release {
        public String version;
        public String tag;
        public String published_at;
        public String message;
    }
    
    /**
     * Start background update checking
     */
    public static void startBackgroundCheck() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForUpdates(false);
            } catch (Exception e) {
                LOG.warn("Update check failed", e);
            }
        }, 5, 60, TimeUnit.MINUTES); // Check every hour after initial 5 min delay
    }
    
    /**
     * Check for updates
     * @param experimental If true, check master branch; otherwise check releases
     * @return Release info if update available, null otherwise
     */
    public static Release checkForUpdates(boolean experimental) {
        try {
            if (experimental) {
                return checkExperimental();
            } else {
                return checkStable();
            }
        } catch (Exception e) {
            LOG.warn("Update check failed", e);
            return null;
        }
    }
    
    private static Release checkStable() throws Exception {
        String url = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "fennec-updater");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        int status = conn.getResponseCode();
        if (status == 404) {
            return null; // No releases yet
        }
        if (status != 200) {
            throw new Exception("GitHub API returned " + status);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        JsonObject json = new Gson().fromJson(response.toString(), JsonObject.class);
        String tag = json.get("tag_name").getAsString();
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        
        if (version.compareTo(CURRENT_VERSION) <= 0) {
            return null; // Already up to date
        }
        
        Release release = new Release();
        release.version = version;
        release.tag = tag;
        release.published_at = json.has("published_at") ? json.get("published_at").getAsString() : "";
        release.message = "New version available: " + version + " (current: " + CURRENT_VERSION + ")";
        
        return release;
    }
    
    private static Release checkExperimental() throws Exception {
        String url = "https://api.github.com/repos/" + GITHUB_REPO + "/commits/master";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "fennec-updater");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new Exception("GitHub API returned " + status);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        JsonObject json = new Gson().fromJson(response.toString(), JsonObject.class);
        String sha = json.get("sha").getAsString();
        String shortSha = sha.substring(0, Math.min(7, sha.length()));
        
        Release release = new Release();
        release.version = "dev-" + shortSha;
        release.tag = sha;
        release.published_at = json.has("commit") ? 
            json.getAsJsonObject("commit").getAsJsonObject("author").get("date").getAsString() : "";
        release.message = "Experimental update available: dev-" + shortSha;
        
        return release;
    }
    
    /**
     * Show notification to user about available update
     */
    public static void notifyUpdate(Release release) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Fennec Updates")
                    .createNotification(
                        "Fennec Update Available",
                        release.message + "\nRestart your IDE to apply the update.",
                        NotificationType.INFORMATION
                    )
                    .notify(null);
            } catch (Exception e) {
                LOG.warn("Failed to show update notification", e);
            }
        });
    }
    
    /**
     * Stop background update checking
     */
    public static void stopBackgroundCheck() {
        scheduler.shutdown();
    }
}
