package fennec.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal object SharedConfig {

    fun pythonForLauncher(): String {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        val configured = env("FENNEC_CONFIG")
        val location = if (configured != null) resolve(configured, home) else home.resolve(".fennec/config.json")
        val data = read(location)
        var python = env("FENNEC_PYTHON")
        if (python == null) {
            val value = data.get("python")
            if (value != null && !value.isJsonNull) {
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "Fennec configuration field must be a string: python"
                }
                python = value.asString
            }
        }
        python = when {
            python.isNullOrBlank() -> search(if (isWindows()) arrayOf("python.exe", "python3.exe") else arrayOf("python3", "python"))
            !python.startsWith("~") && !python.contains('/') && !python.contains('\\') -> search(arrayOf(python))
            else -> resolve(python, home).toString()
        }
        return python
    }

    private fun isPathLike(value: String) = value.contains('/') || value.contains('\\')

    private fun search(names: Array<String>): String {
        val pathEnv = System.getenv("PATH")
        if (pathEnv != null) {
            for (directory in pathEnv.split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) continue
                for (name in names) {
                    val candidate = Path.of(directory).resolve(name)
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        return candidate.toAbsolutePath().normalize().toString()
                    }
                }
            }
        }
        return names[0]
    }

    fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")

    private fun read(location: Path): JsonObject {
        if (!Files.exists(location)) {
            require(env("FENNEC_CONFIG") == null) { "Fennec configuration does not exist: $location" }
            return JsonObject()
        }
        return Files.newBufferedReader(location, StandardCharsets.UTF_8).use { reader ->
            val parsed = JsonParser.parseReader(reader)
            require(parsed.isJsonObject) { "Fennec configuration must be a JSON object: $location" }
            parsed.asJsonObject
        }
    }

    private fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun resolve(value: String, home: Path): Path {
        val expanded = when {
            value == "~" -> home.toString()
            value.startsWith("~/") || value.startsWith("~\\") -> home.resolve(value.substring(2)).toString()
            else -> value
        }
        val result = Path.of(expanded)
        return (if (result.isAbsolute) result else home.resolve(result)).toAbsolutePath().normalize()
    }
}
