package fennec.plugin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object Updater {
    private val LOG = Logger.getInstance(Updater::class.java)
    private const val GITHUB_REPO = "Liso7150/fennec-jetbrains"
    private const val CURRENT_VERSION = "1.0.0"
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    data class Release(
        val version: String,
        val tag: String,
        val publishedAt: String,
        val message: String,
    )

    fun startBackgroundCheck() {
        scheduler.scheduleAtFixedRate({
            try {
                checkForUpdates(false)
            } catch (e: Exception) {
                LOG.warn("Update check failed", e)
            }
        }, 5, 60, TimeUnit.MINUTES)
    }

    fun checkForUpdates(experimental: Boolean): Release? = try {
        if (experimental) checkExperimental() else checkStable()
    } catch (e: Exception) {
        LOG.warn("Update check failed", e)
        null
    }

    private fun checkStable(): Release? {
        val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "fennec-updater")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val status = conn.responseCode
        if (status == 404) return null
        require(status == 200) { "GitHub API returned $status" }

        val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        val json = Gson().fromJson(body, JsonObject::class.java)
        val tag = json.get("tag_name").asString
        val version = tag.removePrefix("v")

        if (version <= CURRENT_VERSION) return null

        return Release(
            version = version,
            tag = tag,
            publishedAt = json.get("published_at")?.asString ?: "",
            message = "New version available: $version (current: $CURRENT_VERSION)",
        )
    }

    private fun checkExperimental(): Release {
        val url = "https://api.github.com/repos/$GITHUB_REPO/commits/main"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "fennec-updater")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val status = conn.responseCode
        require(status == 200) { "GitHub API returned $status" }

        val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        val json = Gson().fromJson(body, JsonObject::class.java)
        val sha = json.get("sha").asString
        val shortSha = sha.take(7)

        return Release(
            version = "dev-$shortSha",
            tag = sha,
            publishedAt = json.getAsJsonObject("commit")
                ?.getAsJsonObject("author")
                ?.get("date")?.asString ?: "",
            message = "Experimental update available: dev-$shortSha",
        )
    }

    fun notifyUpdate(release: Release) {
        ApplicationManager.getApplication().invokeLater {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Fennec Updates")
                    .createNotification(
                        "Fennec Update Available",
                        "${release.message}\nRestart your IDE to apply the update.",
                        NotificationType.INFORMATION,
                    )
                    .notify(null)
            } catch (e: Exception) {
                LOG.warn("Failed to show update notification", e)
            }
        }
    }

    fun stopBackgroundCheck() {
        scheduler.shutdown()
    }
}
