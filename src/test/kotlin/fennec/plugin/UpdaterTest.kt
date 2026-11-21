package fennec.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Headless checks for the updater's decisions: no network, no IDE, no notification. */
object UpdaterTest {

    private fun releasePayload(tag: String, vararg assets: String): JsonObject {
        val json = JsonObject()
        json.addProperty("tag_name", tag)
        val array = JsonArray()
        assets.forEach { name -> array.add(JsonObject().apply { addProperty("name", name) }) }
        json.add("assets", array)
        return json
    }

    private fun commitPayload(sha: String, message: String): JsonObject = JsonObject().apply {
        addProperty("sha", sha)
        add("commit", JsonObject().apply { addProperty("message", message) })
    }

    fun run() {
        TestMain.check("numeric compare ranks 1.10.0 above 1.9.0, which a string compare gets wrong") {
            TestMain.expect(Updater.isNewer("1.10.0", "1.9.0"), "1.10.0 over 1.9.0")
            TestMain.expect(!Updater.isNewer("1.9.0", "1.10.0"), "1.9.0 must not look newer")
            TestMain.expect(Updater.isNewer("2.0.0", "1.99.99"), "2.0.0 over 1.99.99")
        }

        TestMain.check("equal and missing components are not updates") {
            TestMain.expect(!Updater.isNewer("1.0.0", "1.0.0"), "same version")
            TestMain.expect(!Updater.isNewer("1.0", "1.0.0"), "shorter equal version")
            TestMain.expect(!Updater.isNewer("1.0.0-beta.1", "1.0.0"), "pre-release suffix of the same version")
            TestMain.expect(Updater.isNewer("1.1", "1.0.4"), "one component longer and higher")
        }

        TestMain.check("a newer tagged release with a zip is offered by name") {
            val found = Updater.releaseFrom(
                releasePayload("v1.4.0", "Fennec-plugin-1.4.0.zip", "checksums.txt"),
                installed = "1.0.0",
            )
            TestMain.expect(found != null, "expected an offer")
            TestMain.expectEquals("1.4.0", found?.version, "version drops the v")
            TestMain.expect(found?.detail?.contains("Fennec-plugin-1.4.0.zip") == true, "names the zip")
            TestMain.expect(found?.url?.endsWith("/releases/tag/v1.4.0") == true, "links the tag")
        }

        TestMain.check("a newer tag with no zip is still reported, as having no artifact") {
            val found = Updater.releaseFrom(releasePayload("v1.4.0"), installed = "1.0.0")
            TestMain.expect(found?.detail?.contains("no zip") == true, "says the release carries no zip")
        }

        TestMain.check("the published 0.9.0 offers nothing to a 1.0.0 plugin") {
            val found = Updater.releaseFrom(
                releasePayload("v0.9.0", "Fennec-plugin-0.9.0.zip"),
                installed = "1.0.0",
            )
            TestMain.expect(found == null, "a lower tag must stay silent, got $found")
        }

        TestMain.check("a payload without a tag_name is not an update") {
            TestMain.expect(Updater.releaseFrom(JsonObject(), installed = "1.0.0") == null, "no tag")
            TestMain.expect(Updater.trunkFrom(JsonObject()) == null, "no sha")
        }

        TestMain.check("the experimental channel names the trunk commit, first line only") {
            val found = Updater.trunkFrom(commitPayload("0123456789abcdef", "Subject line\n\nBody that must not leak."))
            TestMain.expectEquals("dev-0123456", found?.version, "short sha")
            TestMain.expect(found?.detail?.contains("Subject line") == true, "carries the subject")
            TestMain.expect(found?.detail?.contains("Body") == false, "ignores the body")
            TestMain.expect(found?.detail?.contains("build-plugin.sh") == true, "tells how to get it")
        }
    }
}
