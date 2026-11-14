package fennec.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.logging.Logger

/** Locates, installs and resolves the bundled Rust sidecar binary for this OS. */
object SidecarLocator {

    private val LOG = Logger.getLogger("fennec.sidecar")

    fun binaryName(): String = if (SharedConfig.isWindows()) "fennec-acp.exe" else "fennec-acp"

    fun installedPath(home: Path): Path =
        home.resolve(".fennec").resolve("air").resolve(binaryName())

    fun osDir(): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        val arm = arch == "aarch64" || arch == "arm64"
        return when {
            os.startsWith("windows") -> if (arm) "windows-arm64" else "windows-x64"
            os.startsWith("mac") -> if (arm) "macos-arm64" else "macos-x64"
            else -> if (arm) "linux-arm64" else "linux-x64"
        }
    }

    fun resourcePath(): String = "/sidecar/${osDir()}/${binaryName()}"

    fun resourceBytes(): ByteArray? =
        SidecarLocator::class.java.getResourceAsStream(resourcePath())?.use { it.readBytes() }

    /** Idempotently writes the sidecar to its per-user install path and returns it. */
    fun install(home: Path, bytes: ByteArray): Path {
        val target = installedPath(home)
        val unchanged = Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)
        if (!unchanged) {
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
            LOG.info("Installed the Fennec ACP sidecar at $target")
        }
        if (!SharedConfig.isWindows()) target.toFile().setExecutable(true, false)
        return target
    }

    /** Extracts the bundled binary for this OS, or null when none is bundled. */
    fun ensureInstalled(home: Path): Path? = resourceBytes()?.let { install(home, it) }
}