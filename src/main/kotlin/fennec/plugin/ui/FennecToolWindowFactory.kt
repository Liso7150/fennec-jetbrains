package fennec.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class FennecToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FennecContextPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Context", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(content, panel)
    }
}