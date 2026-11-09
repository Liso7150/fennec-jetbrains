package colibri.plugin;

public class ColibriFixAction extends ColibriEditorAction {
    @Override
    protected String prompt() {
        return "Rewrite this code to fix bugs and improve clarity. Return the complete replacement in one code block, "
                + "then one short sentence on what changed.";
    }
}
