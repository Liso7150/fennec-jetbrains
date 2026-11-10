package colibri.plugin;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Shared plumbing for the editor context-menu actions: pull the selection (or the
 * whole file when nothing is selected), open the Colibri tool window and run the
 * subclass's prompt.
 */
public abstract class ColibriEditorAction extends AnAction {

    protected abstract String prompt();

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(editor != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor == null || project == null) return;

        String code = editor.getSelectionModel().hasSelection()
                ? editor.getSelectionModel().getSelectedText()
                : editor.getDocument().getText();

        String language = "";
        VirtualFile file = editor.getVirtualFile();
        if (file != null) {
            language = file.getExtension() == null ? "" : file.getExtension();
        }
        String context = (language.isEmpty() ? "" : language + " file") + (file == null ? "" : " " + file.getName());

        launch(project, prompt() + "\n\nContext: " + context, code);
    }

    private void launch(@NotNull Project project, @NotNull String prompt, @NotNull String code) {
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Colibri");
        if (window == null || project.isDisposed()) return;
        // show's callback runs after lazy tool-window content creation, in this project.
        window.show(() -> {
            if (project.isDisposed()) return;
            ColibriChatPanel panel = ColibriChatPanel.getInstance(project);
            if (panel != null) panel.ask(prompt, code);
        });
    }

    /** Kept for subclasses that want the file path rather than its contents. */
    protected static String pathOf(Editor editor) {
        VirtualFile file = editor.getVirtualFile();
        if (file == null) return "";
        VirtualFile doc = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return doc != null ? doc.getPath() : file.getPath();
    }
}
