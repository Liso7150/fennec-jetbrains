package fennec.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Registers Fennec with Air when a project opens, so it appears in the agent menu. */
class FennecStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            AirAgentRegistrar.registerOnce()
        }
    }
}