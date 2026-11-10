package colibri.plugin;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers once per IDE process and stops a plugin-owned engine on exit. */
final class ColibriAppLifecycle {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ColibriAppLifecycle() {}

    static void register() {
        Application app = ApplicationManager.getApplication();
        if (app == null || !REGISTERED.compareAndSet(false, true)) return;
        app.getMessageBus().connect(app).subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
            @Override
            public void appClosing() {
                EngineLauncher.stopOwned();
            }
        });
    }
}
