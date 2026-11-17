package fennec.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FennecStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            AirAgentRegistrar.registerOnce()
        }
        Updater.startBackgroundCheck()
    }
}
