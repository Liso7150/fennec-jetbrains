package fennec.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Timer

internal class FennecComposerBar(private val project: Project) : JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val panelBackground = UIUtil.getPanelBackground()
    private val dark = panelBackground.red * 0.299 + panelBackground.green * 0.587 + panelBackground.blue * 0.114 < 128
    private val colors = ComposerColors(dark)
    private val access = triggerButton("access")
    private val model = triggerButton("model")
    private var snapshot: ControlSnapshot? = null
    private var downloading = false

    init {
        isOpaque = false
        add(access)
        add(model)
        access.addActionListener { showAccessMenu() }
        model.addActionListener { showModelMenu() }
        load()
    }

    private fun load() {
        scope.launch {
            try {
                val loaded = FennecControlState.parse(
                    project.getService(SidecarClient::class.java).request("controls/get", JsonObject()),
                )
                ApplicationManager.getApplication().invokeLater { populate(loaded) }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    access.text = buttonHtml(colors.accent, "Ask every time")
                    model.text = buttonHtml(colors.foreground, "Controls unavailable")
                    Messages.showErrorDialog(project, "Could not load Fennec controls: ${error.message}", "Fennec")
                }
            }
        }
    }

    private fun populate(loaded: ControlSnapshot) {
        snapshot = loaded
        val accessLabel = FennecControlState.label(loaded.access)
        access.text = buttonHtml(accessColor(loaded.access), accessLabel)
        access.accessibleContext.accessibleName = "Fennec access: $accessLabel"
        access.toolTipText = "Fennec tool access: $accessLabel"

        val current = loaded.models.firstOrNull { it.path == loaded.model }
        val modelLabel = when {
            loaded.model.isBlank() -> "Default"
            current?.ready == true -> current.name
            else -> "${loaded.model.substringAfterLast('/').substringAfterLast('\\')} (not ready)"
        }
        model.text = buttonHtml(colors.foreground, modelLabel)
        model.accessibleContext.accessibleName = "Fennec model: $modelLabel"
        model.toolTipText = "Fennec local model: $modelLabel"
    }

    private fun showAccessMenu() {
        val current = snapshot?.access ?: "ask"
        val popup = JPopupMenu().apply {
            background = colors.background
            border = BorderFactory.createLineBorder(colors.border, 1, true)
        }
        val descriptions = mapOf(
            "ask" to "Always ask before commands, external edits, and internet use",
            "auto" to "Only asks when a potential risk is detected.",
            "full" to "Never ask, with full access to your files, terminal, and the internet",
        )
        for (mode in FennecControlState.accessModes) {
            val label = FennecControlState.label(mode)
            val color = accessColor(mode)
            val check = if (mode == current) " ✓" else ""
            val item = JMenuItem(
                "<html><font color='#${hex(color)}'>●</font> <b>$label</b>$check<br>" +
                    "<font size='3' color='#${hex(colors.mutedForeground)}'>${descriptions[mode]}</font></html>",
            ).apply {
                font = Font("Dialog", Font.PLAIN, 12)
                background = colors.background
                foreground = colors.foreground
                isOpaque = true
                border = BorderFactory.createEmptyBorder(7, 10, 7, 10)
                addActionListener { applyAccess(mode) }
            }
            popup.add(item)
        }
        popup.show(access, 0, access.height)
    }

    private fun showModelMenu() {
        val loaded = snapshot ?: return
        val popup = JPopupMenu().apply {
            background = colors.background
            border = BorderFactory.createLineBorder(colors.border, 1, true)
        }
        val default = JMenuItem("Default${if (loaded.model.isBlank()) " ✓" else ""}").apply {
            font = Font("Dialog", Font.PLAIN, 12)
            background = colors.background
            foreground = colors.foreground
            isOpaque = true
            border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            addActionListener { applyModel("") }
        }
        popup.add(default)
        for (choice in loaded.models.filter { it.ready }) {
            popup.add(JMenuItem("${choice.name}${if (choice.path == loaded.model) " ✓" else ""}").apply {
                font = Font("Dialog", Font.PLAIN, 12)
                background = colors.background
                foreground = colors.foreground
                isOpaque = true
                border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                addActionListener { applyModel(choice.path) }
            })
        }
        if (loaded.model.isNotBlank() && loaded.models.none { it.path == loaded.model && it.ready }) {
            popup.addSeparator()
            popup.add(JMenuItem("Current model is not servable yet").apply {
                font = Font("Dialog", Font.PLAIN, 11)
                background = colors.background
                foreground = colors.mutedForeground
                isEnabled = false
            })
        }
        popup.addSeparator()
        popup.add(JMenuItem("⬇  Download new model…").apply {
            font = Font("Dialog", Font.PLAIN, 12)
            background = colors.background
            foreground = colors.mutedForeground
            isOpaque = true
            border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            addActionListener { downloadModel() }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Reasoning").apply {
            font = Font("Dialog", Font.BOLD, 10)
            background = colors.background
            foreground = colors.mutedForeground
            isEnabled = false
            border = BorderFactory.createEmptyBorder(6, 10, 2, 10)
        })
        for (level in FennecControlState.reasoningLevels) {
            val label = FennecControlState.reasoningLabel(level)
            popup.add(JMenuItem("$label${if (level == loaded.reasoningEffort) " ✓" else ""}").apply {
                font = Font("Dialog", Font.PLAIN, 12)
                background = colors.background
                foreground = colors.foreground
                isOpaque = true
                border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
                addActionListener { applyReasoning(level) }
            })
        }
        popup.show(model, 0, model.height)
    }

    private fun downloadModel() {
        val loaded = snapshot ?: return
        if (downloading) return
        if (loaded.python.isBlank() || loaded.suiteRoot.isBlank()) {
            Messages.showErrorDialog(project, "The Fennec Python interpreter or suite path is unavailable.", "Fennec")
            return
        }
        val destination = Path.of(loaded.modelsRoot).resolve("qwen36-35b")
        val answer = Messages.showYesNoDialog(
            project,
            "Download Liso/qwen36-35b-colibri (up to 22.78 GiB) into $destination?\n\n" +
                "Existing files are kept and an interrupted download can resume. " +
                "Hugging Face authentication is read only from HF_TOKEN when it is set.",
            "Download Fennec model",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        downloading = true
        model.isEnabled = false
        model.text = buttonHtml(colors.foreground, "Downloading…")
        ProgressManager.getInstance().runProcessWithProgressAsynchronously(
            object : Task.Backgroundable(project, "Downloading recommended Fennec model", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text2 = "Starting Hugging Face download…"
                    val core = Path.of(loaded.suiteRoot).resolve("fennec-core")
                    val code = "import json,sys; from setup_wizard import download_recommended_model; " +
                        "print(json.dumps({'path': str(download_recommended_model(sys.argv[1], python=sys.argv[2]))}))"
                    val output = StringBuilder()
                    val process = ProcessBuilder(
                        loaded.python,
                        "-c",
                        code,
                        loaded.modelsRoot,
                        loaded.python,
                    ).directory(core.toFile()).redirectErrorStream(true).start()
                    val reader = kotlin.concurrent.thread(isDaemon = true, name = "fennec-model-download") {
                        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            synchronized(output) { output.appendLine(line) }
                            indicator.text2 = line.take(180)
                        }
                    }
                    while (!process.waitFor(1, TimeUnit.SECONDS)) {
                        if (indicator.isCanceled) {
                            process.destroyForcibly()
                            break
                        }
                    }
                    reader.join(5_000)
                    if (indicator.isCanceled) error("Download cancelled")
                    val text = synchronized(output) { output.toString() }
                    if (process.exitValue() != 0) {
                        error(text.trim().takeLast(2_000).ifBlank { "Model download failed" })
                    }
                    val path = text.lineSequence().mapNotNull { line ->
                        runCatching {
                            JsonParser.parseString(line).asJsonObject.get("path")?.takeIf { it.isJsonPrimitive }?.asString
                        }.getOrNull()
                    }.lastOrNull() ?: error("The model downloader returned no model path")
                    val updated = runBlocking {
                        FennecControlState.parse(
                            project.getService(SidecarClient::class.java).request(
                                "controls/set",
                                FennecControlState.params(loaded.access, path, loaded.reasoningEffort),
                            ),
                        )
                    }
                    ApplicationManager.getApplication().invokeLater {
                        downloading = false
                        model.isEnabled = true
                        populate(updated)
                        Messages.showInfoMessage(project, "Recommended Fennec model downloaded and selected.", "Fennec")
                    }
                }

                override fun onThrowable(error: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        downloading = false
                        model.isEnabled = true
                        snapshot?.let(::populate)
                        Messages.showErrorDialog(project, "Could not download the Fennec model: ${error.message}", "Fennec")
                    }
                }
            },
            EmptyProgressIndicator(),
        )
    }

    private fun applyAccess(mode: String) {
        val loaded = snapshot
        update(
            "controls/set",
            FennecControlState.params(mode, loaded?.model.orEmpty(), loaded?.reasoningEffort ?: "auto"),
        )
    }

    private fun applyModel(path: String) {
        val loaded = snapshot
        update(
            "controls/set",
            FennecControlState.params(loaded?.access ?: "ask", path, loaded?.reasoningEffort ?: "auto"),
        )
    }

    private fun applyReasoning(level: String) {
        val loaded = snapshot
        update(
            "controls/set",
            FennecControlState.params(loaded?.access ?: "ask", loaded?.model.orEmpty(), level),
        )
    }

    private fun update(method: String, params: JsonObject) {
        scope.launch {
            try {
                val loaded = FennecControlState.parse(
                    project.getService(SidecarClient::class.java).request(method, params),
                )
                ApplicationManager.getApplication().invokeLater { populate(loaded) }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Could not update Fennec controls: ${error.message}", "Fennec")
                }
            }
        }
    }

    private fun triggerButton(kind: String): JButton = JButton().apply {
        font = Font("Dialog", Font.PLAIN, 12)
        background = colors.muted
        foreground = colors.foreground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(colors.border, 1, true),
            BorderFactory.createEmptyBorder(6, 10, 6, 10),
        )
        isContentAreaFilled = true
        isBorderPainted = true
        isFocusPainted = false
        isRolloverEnabled = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        accessibleContext.accessibleName = "Fennec $kind"
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) {
                background = colors.hover
            }

            override fun mouseExited(event: MouseEvent) {
                background = colors.muted
            }
        })
    }

    private fun buttonHtml(color: Color, label: String): String =
        "<html><font color='#${hex(color)}'>$label</font> <font color='#${hex(colors.mutedForeground)}'>▾</font></html>"

    private fun accessColor(mode: String): Color = when (mode) {
        "auto" -> colors.success
        "full" -> colors.red
        else -> colors.accent
    }

    private fun hex(color: Color): String = "%02X%02X%02X".format(color.red, color.green, color.blue)

    private data class ComposerColors(val dark: Boolean) {
        val background = Color.decode(if (dark) "#141414" else "#FFFFFF")
        val muted = Color.decode(if (dark) "#1F1F1F" else "#E8E8E8")
        val hover = Color.decode(if (dark) "#2A2A2A" else "#F5F5F5")
        val border = Color.decode("#2E2E2E")
        val foreground = Color.decode(if (dark) "#EDEDED" else "#1A1A1A")
        val mutedForeground = Color.decode("#A0A0A0")
        val accent = Color.decode(if (dark) "#E9865D" else "#C2542A")
        val success = Color.decode("#5EC98F")
        val red = Color.decode("#EF615C")
    }
}

internal object FennecComposerBarInstaller {
    private const val INSTALLED = "fennec.composerBar.installed"
    private val timers = java.util.WeakHashMap<Project, Timer>()

    fun start(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (timers.containsKey(project)) return@invokeLater
            val timer = Timer(1000) {
                if (project.isDisposed) {
                    (it.source as Timer).stop()
                    timers.remove(project)
                } else {
                    install(project)
                }
            }
            timers[project] = timer
            timer.start()
        }
    }

    private fun install(project: Project) {
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        val prompt = find(frame, ::isAirPrompt) as? EditorTextField ?: return
        val root = generateSequence(prompt.parent as? Component) { it.parent }
            .filterIsInstance<JPanel>()
            .firstOrNull { it.javaClass == JPanel::class.java && it.layout is BorderLayout } ?: return
        val outer = (root.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as? JComponent ?: return
        if (outer.getClientProperty(INSTALLED) == true) return
        val bar = FennecComposerBar(project)
        var mounted: Component = bar
        val layout = outer.layout as? BorderLayout
        val trailing = layout?.getLayoutComponent(BorderLayout.EAST)
        if (layout != null) {
            if (trailing == null) {
                outer.add(bar, BorderLayout.EAST)
            } else {
                val wrapper = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                    isOpaque = false
                    add(trailing)
                    add(bar)
                }
                outer.add(wrapper, BorderLayout.EAST)
                mounted = wrapper
            }
        } else {
            outer.add(bar)
        }
        outer.setComponentZOrder(mounted, 0)
        outer.putClientProperty(INSTALLED, true)
        outer.revalidate()
        outer.repaint()
    }

    private fun isAirPrompt(component: Component): Boolean {
        if (component !is EditorTextField || !component.isShowing) return false
        val hint = runCatching {
            val field = EditorTextField::class.java.getDeclaredField("myHintText")
            field.isAccessible = true
            field.get(component)?.toString()
        }.getOrNull()
        return hint?.contains("Describe the task for the agent") == true
    }

    private fun find(component: Component?, predicate: (Component) -> Boolean): Component? {
        if (component == null) return null
        if (predicate(component)) return component
        if (component is Container) {
            for (child in component.components) {
                find(child, predicate)?.let { return it }
            }
        }
        return null
    }
}
