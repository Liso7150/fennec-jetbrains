package fennec.plugin

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Headless checks for the SharedConfig contract, with injected env and temp homes. */
object SharedConfigTest {

    private fun tempHome(): Path = Files.createTempDirectory("fennec-shared-config")

    private fun writeConfig(home: Path, text: String) {
        val dir = home.resolve(".fennec")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("config.json"), text)
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    private fun fakeBin(home: Path, name: String): Path {
        val bin = home.resolve("bin")
        Files.createDirectories(bin)
        val exe = bin.resolve(name)
        Files.writeString(exe, "fake")
        exe.toFile().setExecutable(true, false)
        return exe
    }

    private fun absolutePython(): String =
        if (SharedConfig.isWindows()) "C:\\opt\\py.exe" else "/opt/py.exe"

    fun run() {
        TestMain.check("missing config falls back to the first default name") {
            val home = tempHome()
            val python = SharedConfig.pythonForLauncher(env("PATH" to home.resolve("empty").toString()), home)
            val expected = if (SharedConfig.isWindows()) "python.exe" else "python3"
            TestMain.expectEquals(expected, python, "default fallback")
        }

        TestMain.check("config python path is resolved against home") {
            val home = tempHome()
            writeConfig(home, "{\"python\": \"tools/py.exe\"}")
            val python = SharedConfig.pythonForLauncher(env(), home)
            TestMain.expectEquals(home.resolve("tools").resolve("py.exe").toString(), python, "relative path")
        }

        TestMain.check("config tilde python is resolved against home") {
            val home = tempHome()
            writeConfig(home, "{\"python\": \"~/py.exe\"}")
            val python = SharedConfig.pythonForLauncher(env(), home)
            TestMain.expectEquals(home.resolve("py.exe").toString(), python, "tilde path")
        }

        TestMain.check("FENNEC_PYTHON overrides the config") {
            val home = tempHome()
            writeConfig(home, "{\"python\": \"configured.exe\"}")
            val abs = absolutePython()
            val python = SharedConfig.pythonForLauncher(env("FENNEC_PYTHON" to abs), home)
            TestMain.expectEquals(Path.of(abs).normalize().toString(), python, "env override")
        }

        TestMain.check("a bare python name is searched on PATH") {
            val home = tempHome()
            val name = if (SharedConfig.isWindows()) "python.exe" else "python3"
            val exe = fakeBin(home, name)
            writeConfig(home, "{\"python\": \"$name\"}")
            val python = SharedConfig.pythonForLauncher(env("PATH" to exe.parent.toString()), home)
            TestMain.expectEquals(exe.toString(), python, "bare name search")
        }

        TestMain.check("default names are searched on PATH") {
            val home = tempHome()
            val name = if (SharedConfig.isWindows()) "python.exe" else "python3"
            val exe = fakeBin(home, name)
            val python = SharedConfig.pythonForLauncher(env("PATH" to exe.parent.toString()), home)
            TestMain.expectEquals(exe.toString(), python, "default name search")
        }

        TestMain.check("a non-string python field is an error") {
            val home = tempHome()
            writeConfig(home, "{\"python\": 42}")
            val error = runCatching { SharedConfig.pythonForLauncher(env(), home) }.exceptionOrNull()?.message
            TestMain.expect(error != null && error.contains("must be a string: python"), "type error, got <$error>")
        }

        TestMain.check("a null python field is ignored") {
            val home = tempHome()
            writeConfig(home, "{\"python\": null}")
            val python = SharedConfig.pythonForLauncher(env("PATH" to home.toString()), home)
            TestMain.expect(python.isNotEmpty(), "null field ignored")
        }

        TestMain.check("an explicit missing config is an error") {
            val home = tempHome()
            val missing = home.resolve("nope.json")
            val error = runCatching {
                SharedConfig.pythonForLauncher(env("FENNEC_CONFIG" to missing.toString()), home)
            }.exceptionOrNull()?.message
            TestMain.expect(error != null && error.contains("does not exist"), "missing config, got <$error>")
        }

        TestMain.check("a non-object config is an error") {
            val home = tempHome()
            writeConfig(home, "[1, 2]")
            val error = runCatching { SharedConfig.pythonForLauncher(env(), home) }.exceptionOrNull()?.message
            TestMain.expect(error != null && error.contains("Cannot read Fennec configuration"), "non-object, got <$error>")
        }

        TestMain.check("blank env values are absent") {
            val home = tempHome()
            writeConfig(home, "{\"python\": \"~/py.exe\"}")
            val python = SharedConfig.pythonForLauncher(env("FENNEC_PYTHON" to "   "), home)
            TestMain.expectEquals(home.resolve("py.exe").toString(), python, "blank env ignored")
        }

        TestMain.check("FENNEC_CONFIG selects a custom config") {
            val home = tempHome()
            val custom = home.resolve("custom.json")
            Files.writeString(custom, "{\"python\": \"~/custom.exe\"}")
            val python = SharedConfig.pythonForLauncher(env("FENNEC_CONFIG" to custom.toString()), home)
            TestMain.expectEquals(home.resolve("custom.exe").toString(), python, "custom config")
        }

        TestMain.check("an absolute python path is used verbatim") {
            val home = tempHome()
            val abs = absolutePython()
            val config = JsonObject()
            config.addProperty("python", abs)
            writeConfig(home, config.toString())
            val python = SharedConfig.pythonForLauncher(env(), home)
            TestMain.expectEquals(Path.of(abs).normalize().toString(), python, "absolute path")
        }

        TestMain.check("a blank config python falls back to PATH search") {
            val home = tempHome()
            val name = if (SharedConfig.isWindows()) "python.exe" else "python3"
            val exe = fakeBin(home, name)
            writeConfig(home, "{\"python\": \"   \"}")
            val python = SharedConfig.pythonForLauncher(env("PATH" to exe.parent.toString()), home)
            TestMain.expectEquals(exe.toString(), python, "blank python search")
        }

        TestMain.check("PATH search skips blank directories") {
            val home = tempHome()
            val name = if (SharedConfig.isWindows()) "python.exe" else "python3"
            val exe = fakeBin(home, name)
            val sep = File.pathSeparator
            val path = "$sep${exe.parent}$sep"
            val python = SharedConfig.pythonForLauncher(env("PATH" to path), home)
            TestMain.expectEquals(exe.toString(), python, "blank dirs skipped")
        }

        TestMain.check("isWindows matches the running os") {
            val osName = System.getProperty("os.name").lowercase()
            TestMain.expectEquals(osName.startsWith("windows"), SharedConfig.isWindows(), "isWindows")
        }
    }
}