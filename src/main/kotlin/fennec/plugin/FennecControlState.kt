package fennec.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class ModelChoice(val name: String, val path: String, val ready: Boolean = false) {
    override fun toString(): String = if (path.isBlank()) "Default" else name
}

internal data class ControlSnapshot(
    val access: String,
    val model: String,
    val reasoningEffort: String,
    val modelsRoot: String,
    val python: String,
    val suiteRoot: String,
    val models: List<ModelChoice>,
)

internal object FennecControlState {
    val accessModes: List<String> = listOf("ask", "auto", "full")
    val reasoningLevels: List<String> = listOf("auto", "low", "medium", "high")

    fun reasoningLabel(level: String): String = when (level) {
        "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        else -> "Auto"
    }

    fun label(access: String): String = when (access) {
        "auto" -> "Auto approve"
        "full" -> "Full access"
        else -> "Ask every time"
    }

    fun parse(result: JsonObject): ControlSnapshot {
        val access = result.get("access")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it in accessModes } ?: "ask"
        val model = result.get("model")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val reasoning = result.get("reasoning_effort")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it in reasoningLevels } ?: "auto"
        val root = result.get("models_root")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val python = result.get("python")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val suiteRoot = result.get("suite_root")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val models = (result.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
            .mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val path = item.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val name = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: path.substringAfterLast('/').substringAfterLast('\\')
                ModelChoice(name, path, item.get("ready")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
            }
        return ControlSnapshot(access, model, reasoning, root, python, suiteRoot, models)
    }

    fun params(access: String, model: String, reasoningEffort: String? = null): JsonObject = JsonObject().apply {
        addProperty("access", access)
        addProperty("model", model)
        if (reasoningEffort != null) addProperty("reasoning_effort", reasoningEffort)
    }
}
