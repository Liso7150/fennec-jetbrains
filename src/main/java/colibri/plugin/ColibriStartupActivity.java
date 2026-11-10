package colibri.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Optionally brings the engine up when a project opens, so the first ask is instant. */
public class ColibriStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        ColibriAppLifecycle.register();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!project.isDisposed() && ColibriSettings.getInstance().getState().autoStart) {
                EngineLauncher.ensureRunning(null);
            }
        });
        return Unit.INSTANCE;
    }
}
