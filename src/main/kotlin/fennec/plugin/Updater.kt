package fennec.plugin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Polls this repository's GitHub releases and says so when a newer plugin is published.
 *
 * The plugin never replaces itself: an IDE plugin is only written by the IDE's own
 * installer, and a silent overwrite of the jar the classloader is reading would need a
 * restart anyway. So the result of a check is one balloon carrying the release page.
 * Tools &rarr; Fennec decides whether the check runs and which channel it reads.
 */
object Updater {

    private val LOG = Logger.getInstance(Updater::class.java)
    private const val REPO = "Liso7150/fennec-jetbrains"
    private const val NOTIFICATION_GROUP = "Fennec Updates"
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val announced: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** What was found: the version to offer, the page to get it from, and what carries it. */
    data class Release(val version: String, val url: String, val detail: String)

    fun startBackgroundCheck() {
        scheduler.scheduleAtFixedRate({
            try {
                checkOnce()
            } catch (e: Exception) {
                LOG.warn("Fennec update check failed", e)
            }
        }, 5, 60, TimeUnit.MINUTES)
    }

    /**
     * One pass over the configured channel, announcing each version at most once per IDE run.
     * Returns the release that was announced, or null when there is nothing to say.
     */
    internal fun checkOnce(): Release? {
        val state = FennecSettings.getInstance().state
        if (!state.updateChecks) return null
        val found = if (state.experimentalChannel) trunkHead() else newerRelease()
        if (found == null) return null
        if (!announced.add(found.version)) return null
        notify(found)
        return found
    }

    /** The published release, when its tag is newer than the running plugin. */
    internal fun newerRelease(): Release? =
        fetch("https://api.github.com/repos/$REPO/releases/latest")
            ?.let { releaseFrom(it, AirAgentRegistrar.pluginVersion()) }

    /**
     * A release payload from GitHub's API plus the installed version. Public so the
     * shapes that matter - no zip, a tag that is not newer - are covered headless.
     */
    internal fun releaseFrom(json: JsonObject, installed: String): Release? {
        val tag = json.get("tag_name")?.asString ?: return null
        val version = tag.removePrefix("v")
        if (!isNewer(version, installed)) return null
        val asset = json.getAsJsonArray("assets")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.get("name")?.asString?.endsWith(".zip") == true }
            ?.get("name")?.asString
        return Release(
            version = version,
            url = "https://github.com/$REPO/releases/tag/$tag",
            detail = if (asset == null) {
                "A newer Fennec plugin is published, but the release carries no zip."
            } else {
                "A newer Fennec plugin is published. It is carried by $asset."
            },
        )
    }

    /**
     * The trunk head, for the experimental channel. Trunk is never a plugin artifact: it has to
     * be built with build-plugin.sh, which is what the message says.
     */
    internal fun trunkHead(): Release? =
        fetch("https://api.github.com/repos/$REPO/commits/main")?.let { trunkFrom(it) }

    internal fun trunkFrom(json: JsonObject): Release? {
        val sha = json.get("sha")?.asString ?: return null
        val subject = json.getAsJsonObject("commit")?.get("message")?.asString
            ?.lineSequence()?.first()?.take(90).orEmpty()
        return Release(
            version = "dev-${sha.take(7)}",
            url = "https://github.com/$REPO/tree/main",
            detail = "Fennec trunk has \"$subject\" which no published release has. Build it with build-plugin.sh.",
        )
    }

    /**
     * Dot-separated numeric compare, because a string compare ranks 1.10.0 below 1.9.0.
     * A pre-release suffix is ignored: a tag that only differs in its suffix is not an update.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    /** GitHub's API, or null for anything that is not a JSON object with HTTP 200. */
    private fun fetch(endpoint: String): JsonObject? = try {
        val conn = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "fennec-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            null
        } else {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                Gson().fromJson(it, JsonObject::class.java)
            }
        }
    } catch (e: Exception) {
        LOG.info("Fennec could not reach $endpoint: ${e.message}")
        null
    }

    private fun notify(release: Release) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val balloon: Notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        "Fennec ${release.version}",
                        "${release.detail} ${release.url}",
                        NotificationType.INFORMATION,
                    )
                balloon.addAction(
                    NotificationAction.createSimple("Open the release page") {
                        BrowserUtil.browse(release.url)
                    },
                )
                balloon.notify(null)
            } catch (e: Exception) {
                LOG.warn("Fennec could not show the update notification", e)
            }
        }
    }

    fun stopBackgroundCheck() {
        scheduler.shutdown()
    }
}
