package fennec.plugin.ui

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import fennec.plugin.ControlSnapshot
import fennec.plugin.FennecControlState
import fennec.plugin.ModelChoice
import fennec.plugin.SidecarClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class FennecControlPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val client = project.getService(SidecarClient::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accessCombo = JComboBox(FennecControlState.accessModes.map(FennecControlState::label).toTypedArray())
    private val modelCombo = JComboBox<ModelChoice>().apply { isEditable = true }
    private val modelsRootLabel = JLabel(" ")
    private val statusLabel = JLabel("Loading Fennec controls…")
    private val applyButton = javax.swing.JButton("Apply")
    private var formRow = 0

    init {
        val title = JLabel("Fennec").apply {
            font = font.deriveFont(Font.BOLD, 18f)
        }
        val subtitle = JLabel("Local agent controls").apply {
            foreground = statusLabel.foreground.darker()
        }
        val heading = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(subtitle, BorderLayout.SOUTH)
        }

        val form = JPanel(GridBagLayout()).apply {
            isOpaque = false
            addRow("Access:", accessCombo)
            addRow("Model:", modelCombo)
            addRow("Models folder:", modelsRootLabel)
            addRow("", applyButton)
        }

        val note = JTextArea(
            "Access changes apply to new permission requests immediately. " +
                "Model changes apply the next time the local engine starts.",
        ).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(12f)
            rows = 2
        }

        val content = JPanel(BorderLayout(0, 12)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            add(heading, BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
            add(note, BorderLayout.SOUTH)
        }
        add(content, BorderLayout.NORTH)
        add(statusLabel, BorderLayout.SOUTH)

        applyButton.addActionListener { apply() }
        load()
    }

    private fun JPanel.addRow(label: String, component: JComponent) {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = formRow++
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 8)
        }
        if (label.isNotEmpty()) add(JLabel(label), constraints)
        constraints.gridx = 1
        constraints.fill = GridBagConstraints.HORIZONTAL
        constraints.weightx = 1.0
        add(component, constraints)
    }

    private fun load() {
        scope.launch {
            try {
                val snapshot = FennecControlState.parse(client.request("controls/get", JsonObject()))
                ApplicationManager.getApplication().invokeLater {
                    populate(snapshot)
                    statusLabel.text = "Ready"
                    applyButton.isEnabled = true
                }
            } catch (error: Exception) {
                onEdt { statusLabel.text = "Could not load controls: ${error.message}" }
            }
        }
    }

    private fun populate(snapshot: ControlSnapshot) {
        accessCombo.selectedIndex = FennecControlState.accessModes.indexOf(snapshot.access).coerceAtLeast(0)
        modelsRootLabel.text = snapshot.modelsRoot.ifBlank { "Default model folder" }
        modelCombo.removeAllItems()
        modelCombo.addItem(ModelChoice("Default", ""))
        snapshot.models.forEach(modelCombo::addItem)
        if (snapshot.model.isNotBlank() && snapshot.models.none { it.path == snapshot.model }) {
            modelCombo.addItem(ModelChoice(snapshot.model.substringAfterLast('/').substringAfterLast('\\'), snapshot.model))
        }
        val selected = snapshot.model.takeIf { it.isNotBlank() }
            ?.let { path -> (0 until modelCombo.itemCount).map { modelCombo.getItemAt(it) }.firstOrNull { it.path == path } }
        modelCombo.selectedItem = selected ?: modelCombo.getItemAt(0)
    }

    private fun apply() {
        val access = FennecControlState.accessModes.getOrElse(accessCombo.selectedIndex) { "ask" }
        val model = when (val selected = modelCombo.selectedItem) {
            is ModelChoice -> selected.path
            null -> ""
            else -> selected.toString()
        }
        applyButton.isEnabled = false
        statusLabel.text = "Applying…"
        scope.launch {
            try {
                val result = client.request("controls/set", FennecControlState.params(access, model))
                val snapshot = FennecControlState.parse(result)
                onEdt {
                    populate(snapshot)
                    statusLabel.text = "Applied"
                    applyButton.isEnabled = true
                }
            } catch (error: Exception) {
                onEdt {
                    statusLabel.text = "Apply failed: ${error.message}"
                    applyButton.isEnabled = true
                }
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }

    override fun dispose() {
        scope.cancel()
    }
}
