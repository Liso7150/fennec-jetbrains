package fennec.plugin

import com.google.gson.JsonParser

object FennecControlsTest {
    fun run() {
        TestMain.check("control snapshot parses access, model, and local models") {
            val json = JsonParser.parseString(
                """{"access":"auto","model":"/models/qwen","models_root":"/models","models":[{"name":"qwen","path":"/models/qwen"}]}""",
            ).asJsonObject
            val snapshot = FennecControlState.parse(json)
            TestMain.expectEquals("auto", snapshot.access, "access")
            TestMain.expectEquals("/models/qwen", snapshot.model, "model")
            TestMain.expectEquals("/models", snapshot.modelsRoot, "models root")
            TestMain.expectEquals(1, snapshot.models.size, "model count")
            TestMain.expectEquals("qwen", snapshot.models[0].name, "model name")
        }

        TestMain.check("control snapshot defaults to safe access") {
            val snapshot = FennecControlState.parse(JsonParser.parseString("{}").asJsonObject)
            TestMain.expectEquals("ask", snapshot.access, "default access")
            TestMain.expectEquals("", snapshot.model, "default model")
            TestMain.expect(snapshot.models.isEmpty(), "default models")
        }

        TestMain.check("control apply sends explicit access and model values") {
            val params = FennecControlState.params("full", "/models/edge0")
            TestMain.expectEquals("full", params.get("access").asString, "access param")
            TestMain.expectEquals("/models/edge0", params.get("model").asString, "model param")
        }

        TestMain.check("access labels match the desktop vocabulary") {
            TestMain.expectEquals("Ask every time", FennecControlState.label("ask"), "ask label")
            TestMain.expectEquals("Auto approve", FennecControlState.label("auto"), "auto label")
            TestMain.expectEquals("Full access", FennecControlState.label("full"), "full label")
        }
    }
}
