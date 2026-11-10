package fennec.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Registers Fennec with Air when a project opens, so it appears in the agent menu. */
public class FennecStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(AirAgentRegistrar::registerOnce);
        return Unit.INSTANCE;
    }
}
