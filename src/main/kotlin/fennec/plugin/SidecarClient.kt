package fennec.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Talks to the bundled Rust sidecar over JSON-RPC 2.0 (NDJSON on stdio).
 *
 * The subprocess is launched with `--rpc`; a stdout pump correlates responses
 * to callers by id and republishes every frame on a Flow, while stderr is
 * republished as a log Flow. Callers suspend on [request]; the UI collects the
 * Flows and hops to the EDT. No raw threads: all pumping is coroutine work on
 * [Dispatchers.IO], and the process is torn down on [dispose].
 */
@Service(Service.Level.PROJECT)
class SidecarClient(private val project: Project) : Disposable {

    class RpcException(message: String) : RuntimeException(message)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    private val _frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val frames: SharedFlow<JsonObject> = _frames.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: java.io.BufferedWriter? = null

    suspend fun request(method: String, params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        ensureStarted()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        try {
            writeMutex.withLock {
                val out = writer ?: throw RpcException("The Fennec sidecar is not running")
                out.write(payload.toString())
                out.newLine()
                out.flush()
            }
            val response = withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
            response.get("error")?.takeIf { it.isJsonObject }?.let { error ->
                throw RpcException(error.asJsonObject.get("message")?.asString ?: "The Fennec sidecar returned an error")
            }
            response.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw RpcException("The Fennec sidecar returned no result for $method")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw RpcException("The Fennec sidecar timed out on $method")
        } finally {
            pending.remove(id)
        }
    }

    @Synchronized
    private fun ensureStarted() {
        process?.let { if (it.isAlive) return }
        val binary = resolveBinary()
            ?: throw RpcException("No Fennec sidecar binary is bundled for ${SidecarLocator.osDir()}")
        val proc = try {
            ProcessBuilder(binary.toString(), "--rpc").start()
        } catch (e: IOException) {
            throw RpcException("Could not start the Fennec sidecar at $binary: ${e.message}")
        }
        process = proc
        writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        scope.launch { pumpStdout(proc.inputStream.bufferedReader(Charsets.UTF_8)) }
        scope.launch { pumpStderr(proc.errorStream.bufferedReader(Charsets.UTF_8)) }
        scope.launch { watchExit(proc) }
    }

    private fun resolveBinary(): Path? {
        val override = FennecSettings.getInstance().state.sidecarPath
        if (override.isNotBlank()) return Path.of(override)
        return SidecarLocator.ensureInstalled(Path.of(System.getProperty("user.home")))
    }

    private fun pumpStdout(reader: BufferedReader) {
        try {
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val frame = try {
                    JsonParser.parseString(line).asJsonObject
                } catch (e: Exception) {
                    return@forEachLine
                }
                val id = frame.get("id")?.takeIf { it.isJsonPrimitive }?.asLong
                if (id != null) pending.remove(id)?.complete(frame)
                _frames.tryEmit(frame)
            }
        } catch (e: IOException) {
            _logs.tryEmit("sidecar stdout closed: ${e.message}")
        }
    }

    private fun pumpStderr(reader: BufferedReader) {
        try {
            reader.forEachLine { line -> _logs.tryEmit(line) }
        } catch (e: IOException) {
            // the stream closes on shutdown; nothing to report
        }
    }

    private fun watchExit(proc: Process) {
        val code = try {
            proc.waitFor()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        _logs.tryEmit("sidecar exited with code $code")
        val failure = RpcException("The Fennec sidecar exited with code $code")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }

    override fun dispose() {
        scope.cancel()
        val proc = process
        process = null
        writer = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        if (proc != null && proc.isAlive) proc.destroyForcibly()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 180_000L
    }
}