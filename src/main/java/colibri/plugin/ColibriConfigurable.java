package colibri.plugin;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/** Settings page: where the engine lives, which model, and whether to start it. */
public class ColibriConfigurable implements Configurable {

    private JBTextField engineUrl;
    private JBTextField modelId;
    private JBTextField apiKey;
    private JBTextField modelDir;
    private JBTextField python;
    private JBTextField runtime;
    private JCheckBox autoStart;
    private JSpinner maxTokens;
    private JSpinner temperature;

    @Override
    public @Nls String getDisplayName() {
        return "Colibri Code";
    }

    @Override
    public @Nullable JComponent createComponent() {
        engineUrl = new JBTextField(36);
        modelId = new JBTextField(24);
        apiKey = new JBTextField(24);
        modelDir = new JBTextField(48);
        python = new JBTextField(48);
        runtime = new JBTextField(48);
        autoStart = new JCheckBox("Start the engine when the IDE starts");
        maxTokens = new JSpinner(new SpinnerNumberModel(2048, 64, 32768, 64));
        temperature = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.05));

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Engine URL", engineUrl)
                .addLabeledComponent("Model id", modelId)
                .addLabeledComponent("API key (COLI_API_KEY)", apiKey)
                .addLabeledComponent("Model directory", modelDir)
                .addLabeledComponent("Python", python)
                .addLabeledComponent("Colibri runtime", runtime)
                .addComponent(autoStart)
                .addLabeledComponent("Max tokens", maxTokens)
                .addLabeledComponent("Temperature", temperature)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    @Override
    public boolean isModified() {
        ColibriSettings.State s = ColibriSettings.getInstance().getState();
        return !engineUrl.getText().equals(s.engineUrl)
                || !modelId.getText().equals(s.modelId)
                || !apiKey.getText().equals(s.apiKey)
                || !modelDir.getText().equals(s.modelDir)
                || !python.getText().equals(s.python)
                || !runtime.getText().equals(s.runtime)
                || autoStart.isSelected() != s.autoStart
                || ((Number) maxTokens.getValue()).intValue() != s.maxTokens
                || ((Number) temperature.getValue()).doubleValue() != s.temperature;
    }

    @Override
    public void apply() throws ConfigurationException {
        ColibriSettings.State s = ColibriSettings.getInstance().getState();
        s.engineUrl = engineUrl.getText().replaceAll("/+$", "");
        s.modelId = modelId.getText();
        s.apiKey = apiKey.getText();
        s.modelDir = modelDir.getText();
        s.python = python.getText();
        s.runtime = runtime.getText();
        s.autoStart = autoStart.isSelected();
        s.maxTokens = ((Number) maxTokens.getValue()).intValue();
        s.temperature = ((Number) temperature.getValue()).doubleValue();
    }

    @Override
    public void reset() {
        ColibriSettings.State s = ColibriSettings.getInstance().getState();
        engineUrl.setText(s.engineUrl);
        modelId.setText(s.modelId);
        apiKey.setText(s.apiKey);
        modelDir.setText(s.modelDir);
        python.setText(s.python);
        runtime.setText(s.runtime);
        autoStart.setSelected(s.autoStart);
        maxTokens.setValue(s.maxTokens);
        temperature.setValue(s.temperature);
    }

    @Override
    public void disposeUIResources() {
        engineUrl = null;
        modelId = null;
        apiKey = null;
        modelDir = null;
        python = null;
        runtime = null;
        autoStart = null;
        maxTokens = null;
        temperature = null;
    }
}
