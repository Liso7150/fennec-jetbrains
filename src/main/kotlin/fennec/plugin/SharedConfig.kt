package fennec.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** The shared Fennec configuration Air needs: where the engine's Python lives. */
object SharedConfig {

    /**
     * The interpreter the sidecar spawns, resolved the way fennec-core resolves
     * its own config: a blank or bare value is searched on PATH, a path is made
     * absolute, and the answer is never an empty string Air cannot exec.
     */
    fun pythonForLauncher(): String =
        pythonForLauncher(System::getenv, Path.of(System.getProperty("user.home")))

    internal fun pythonForLauncher(env: (String) -> String?, home: Path): String {
        val home = home.toAbsolutePath().normalize()
        fun value(name: String): String? = env(name)?.takeIf { it.isNotBlank() }

        val configured = value("FENNEC_CONFIG")
        val location = configured?.let { resolve(it, home) } ?: home.resolve(".fennec/config.json")
        val data = read(location, configured != null)
        var python = value("FENNEC_PYTHON")
        if (python == null) {
            val field = data.get("python")
            if (field != null && !field.isJsonNull) {
                require(field.isJsonPrimitive && field.asJsonPrimitive.isString) {
                    "Fennec configuration field must be a string: python"
                }
                python = field.asString
            }
        }
        val defaults = if (isWindows()) arrayOf("python.exe", "python3.exe") else arrayOf("python3", "python")
        return when {
            python.isNullOrBlank() -> search(env, defaults)
            !python.startsWith("~") && !python.contains('/') && !python.contains('\\') -> search(env, arrayOf(python))
            else -> resolve(python, home).toString()
        }
    }

    /** A bare name is what the config stores; PATH is what makes it an executable. */
    private fun search(env: (String) -> String?, names: Array<String>): String {
        env("PATH")?.split(File.pathSeparator)?.forEach { directory ->
            if (directory.isBlank()) return@forEach
            for (name in names) {
                val candidate = Path.of(directory).resolve(name)
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString()
                }
            }
        }
        return names[0]
    }

    fun isWindows(): Boolean = osName().startsWith("windows")

    internal fun isMac(): Boolean = osName().startsWith("mac")

    private fun osName(): String = System.getProperty("os.name").lowercase(Locale.ROOT)

    private fun read(location: Path, explicit: Boolean): JsonObject {
        if (!Files.exists(location)) {
            if (explicit) throw IllegalArgumentException("Fennec configuration does not exist: $location")
            return JsonObject()
        }
        try {
            Files.newBufferedReader(location, StandardCharsets.UTF_8).use { reader ->
                val parsed = JsonParser.parseReader(reader)
                if (!parsed.isJsonObject) {
                    throw IllegalArgumentException("Fennec configuration must be a JSON object: $location")
                }
                return parsed.asJsonObject
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot read Fennec configuration: $location", e)
        }
    }

    private fun resolve(value: String, home: Path): Path {
        val expanded = when {
            value == "~" -> home
            value.startsWith("~/") || value.startsWith("~\\") -> home.resolve(value.substring(2))
            else -> Path.of(value)
        }
        return (if (expanded.isAbsolute) expanded else home.resolve(expanded)).toAbsolutePath().normalize()
    }
}