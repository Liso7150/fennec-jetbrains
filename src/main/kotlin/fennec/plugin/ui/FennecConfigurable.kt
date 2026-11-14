package fennec.plugin.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import fennec.plugin.FennecSettings
import fennec.plugin.SharedConfig
import javax.swing.JComponent

class FennecConfigurable : Configurable {

    private val settings = FennecSettings.getInstance()
    private var panel: DialogPanel? = null

    override fun getDisplayName(): String = "Fennec"

    override fun createComponent(): JComponent {
        val state = settings.state
        val python = try {
            SharedConfig.pythonForLauncher()
        } catch (e: Exception) {
            "unavailable (${e.message})"
        }
        val created = panel {
            row("Sidecar binary:") {
                textField().bindText(state::sidecarPath)
                    .comment("Leave blank to use the bundled sidecar under ~/.fennec/air. Set a path to override it.")
            }
            row("Search results:") {
                spinner(1..20, 1).bindIntValue(state::resultLimit)
                    .comment("Maximum number of semantic search results the sidecar returns.")
            }
            row("Engine Python:") {
                label(python)
                    .comment("Resolved from the shared Fennec configuration; the sidecar spawns this interpreter.")
            }
        }
        panel = created
        return created
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}