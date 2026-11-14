package fennec.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "FennecSettings", storages = [Storage("fennec.xml")])
class FennecSettings : PersistentStateComponent<FennecSettings.State> {

    data class State(
        var sidecarPath: String = "",
        var resultLimit: Int = 8,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): FennecSettings =
            ApplicationManager.getApplication().getService(FennecSettings::class.java)
    }
}