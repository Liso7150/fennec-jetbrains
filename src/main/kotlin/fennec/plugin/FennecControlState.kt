package fennec.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class ModelChoice(val name: String, val path: String) {
    override fun toString(): String = if (path.isBlank()) "Default" else name
}

internal data class ControlSnapshot(
    val access: String,
    val model: String,
    val modelsRoot: String,
    val models: List<ModelChoice>,
)

internal object FennecControlState {
    val accessModes: List<String> = listOf("ask", "auto", "full")

    fun label(access: String): String = when (access) {
        "auto" -> "Auto approve"
        "full" -> "Full access"
        else -> "Ask every time"
    }

    fun parse(result: JsonObject): ControlSnapshot {
        val access = result.get("access")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it in accessModes } ?: "ask"
        val model = result.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val root = result.get("models_root")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val models = (result.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
            .mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val path = item.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val name = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: path.substringAfterLast('/').substringAfterLast('\\')
                ModelChoice(name, path)
            }
        return ControlSnapshot(access, model, root, models)
    }

    fun params(access: String, model: String): JsonObject = JsonObject().apply {
        addProperty("access", access)
        addProperty("model", model)
    }
}
