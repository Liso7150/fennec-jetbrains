package colibri.plugin;

public class ColibriSelectionAction extends ColibriEditorAction {
    @Override
    protected String prompt() {
        return "Explain this code: what it does, its edge cases, and anything that looks wrong. Be concise.";
    }
}
