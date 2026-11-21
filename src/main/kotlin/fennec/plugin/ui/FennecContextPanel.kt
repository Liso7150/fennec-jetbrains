package fennec.plugin.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import fennec.plugin.FennecSettings
import fennec.plugin.SidecarClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * Indexes the open file into the sidecar's structural chunks, embeds them, and
 * runs semantic search against the loopback embedding service. PSI is read in
 * [readAction]; every Swing mutation hops to the EDT; sidecar logs arrive on a
 * Flow. The panel owns a coroutine scope cancelled when its content is disposed.
 */
class FennecContextPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private data class Source(val text: String, val path: String, val language: String)
    private data class Chunk(val symbol: String, val startLine: Int, val text: String)
    private data class ResultItem(val chunkIndex: Int, val symbol: String, val line: Int, val score: Double) {
        override fun toString(): String = "$symbol  (line $line, score %.3f)".format(score)
    }

    private val client = project.getService(SidecarClient::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val listModel = DefaultListModel<ResultItem>()
    private val queryField = JBTextField()
    private val logArea = JBTextArea()
    private val statusLabel = JLabel(" ")

    private var indexedPath: String? = null
    private var indexedChunks: List<Chunk> = emptyList()

    init {
        val indexButton = JButton("Index Current File")
        val searchButton = JButton("Search")
        val toolbar = JPanel(BorderLayout(6, 0))
        toolbar.add(indexButton, BorderLayout.WEST)
        toolbar.add(queryField, BorderLayout.CENTER)
        toolbar.add(searchButton, BorderLayout.EAST)

        val resultList = JBList(listModel)
        resultList.emptyText.text = "Index a file, then search its symbols"
        logArea.isEditable = false
        logArea.lineWrap = true

        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(resultList),
            JBScrollPane(logArea),
        )
        split.resizeWeight = 0.6

        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        indexButton.addActionListener { indexCurrentFile() }
        searchButton.addActionListener { search() }
        queryField.addActionListener { search() }
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = resultList.selectedIndex
                if (index >= 0) navigate(listModel.getElementAt(index))
            }
        })

        scope.launch {
            client.logs.collect { line ->
                ApplicationManager.getApplication().invokeLater {
                    logArea.append(line + "\n")
                    logArea.caretPosition = logArea.document.length
                }
            }
        }
    }

    private fun k(): Int = FennecSettings.getInstance().state.resultLimit.coerceIn(1, 20)

    private fun indexCurrentFile() {
        scope.launch {
            try {
                val source = readAction {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    val psi = editor?.let { PsiDocumentManager.getInstance(project).getPsiFile(it.document) }
                    if (psi == null) {
                        null
                    } else {
                        Source(psi.text, psi.virtualFile?.path ?: psi.name, psi.language.id)
                    }
                }
                if (source == null) {
                    setStatus("No file is open in the editor")
                    return@launch
                }
                val chunkParams = JsonObject().apply {
                    addProperty("text", source.text)
                    addProperty("path", source.path)
                    addProperty("language", source.language)
                }
                val chunks = parseChunks(client.request("context/chunk", chunkParams))
                if (chunks.isEmpty()) {
                    setStatus("No structural chunks found in ${source.path}")
                    return@launch
                }
                val documents = JsonArray().apply {
                    chunks.forEachIndexed { index, chunk ->
                        add(JsonObject().apply {
                            addProperty("id", index.toString())
                            addProperty("text", chunk.text)
                        })
                    }
                }
                val searchParams = JsonObject().apply {
                    add("documents", documents)
                    addProperty("k", k())
                }
                val result = client.request("context/search", searchParams)
                indexedPath = source.path
                indexedChunks = chunks
                val indexed = result.get("indexed")?.asInt ?: chunks.size
                setStatus("Indexed $indexed chunks from ${source.path}")
            } catch (e: Exception) {
                setStatus("Indexing failed: ${e.message}")
            }
        }
    }

    private fun search() {
        val query = queryField.text.trim()
        if (query.isEmpty()) {
            setStatus("Enter a search query")
            return
        }
        scope.launch {
            try {
                val params = JsonObject().apply {
                    addProperty("query", query)
                    addProperty("k", k())
                }
                val result = client.request("context/search", params)
                val hits = result.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
                val items = hits.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val index = obj.get("id")?.asString?.toIntOrNull() ?: return@mapNotNull null
                    val chunk = indexedChunks.getOrNull(index) ?: return@mapNotNull null
                    ResultItem(index, chunk.symbol, chunk.startLine, obj.get("score")?.asDouble ?: 0.0)
                }
                ApplicationManager.getApplication().invokeLater {
                    listModel.clear()
                    items.forEach { listModel.addElement(it) }
                }
                setStatus(if (items.isEmpty()) "No matches for \"$query\"" else "${items.size} matches for \"$query\"")
            } catch (e: Exception) {
                setStatus("Search failed: ${e.message}")
            }
        }
    }

    private fun navigate(item: ResultItem) {
        val path = indexedPath ?: return
        scope.launch {
            val file = readAction { LocalFileSystem.getInstance().findFileByPath(path) } ?: return@launch
            ApplicationManager.getApplication().invokeLater {
                OpenFileDescriptor(project, file, (item.line - 1).coerceAtLeast(0), 0).navigate(true)
            }
        }
    }

    private fun parseChunks(result: JsonObject): List<Chunk> {
        val array = result.get("chunks")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asJsonObject
            val symbol = obj.get("symbol")?.asString ?: return@mapNotNull null
            Chunk(symbol, obj.get("start_line")?.asInt ?: 1, obj.get("text")?.asString ?: "")
        }
    }

    private fun setStatus(text: String) {
        ApplicationManager.getApplication().invokeLater { statusLabel.text = text }
    }

    override fun dispose() {
        scope.cancel()
    }
}