package colibri.plugin;

public class ColibriTestAction extends ColibriEditorAction {
    @Override
    protected String prompt() {
        return "Write tests for this code using the test framework the project already uses. "
                + "Cover the main path and the edge cases. Return only runnable test code.";
    }
}
