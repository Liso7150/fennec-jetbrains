package colibri.plugin;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The tool window: a streaming chat with the local Colibri model.
 *
 * A single panel per project is kept in {@link #last} so editor actions can type
 * a prompt into it without the user hunting for the window.
 */
public class ColibriChatPanel extends JPanel implements Disposable {

    private static volatile ColibriChatPanel last;

    private final List<JsonObject> history = new ArrayList<>();
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(3, 40);
    private final JLabel status = new JLabel("checking engine…");
    private final JButton sendButton = new JButton("Send");
    private final JButton stopButton = new JButton("Stop");
    private final JButton startButton = new JButton("Start engine");

    private volatile Thread activeStream;
    private volatile boolean streaming;
    private final StringBuilder currentAnswer = new StringBuilder();

    public ColibriChatPanel() {
        super(new BorderLayout(0, 0));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        last = this;

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        transcript.setBackground(JBColor.background());

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0) {
                    e.consume();
                    send();
                }
            }
        });

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(startButton);
        buttons.add(stopButton);
        buttons.add(sendButton);
        header.add(buttons, BorderLayout.EAST);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(new JBScrollPane(input), BorderLayout.CENTER);
        JLabel hint = new JLabel("Ctrl+Enter to send · answers come from the local Colibri engine");
        hint.setFont(hint.getFont().deriveFont(10f));
        hint.setForeground(JBColor.GRAY);
        bottom.add(hint, BorderLayout.SOUTH);

        add(header, BorderLayout.NORTH);
        add(new JBScrollPane(transcript), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> send());
        stopButton.addActionListener(e -> stop());
        startButton.addActionListener(e -> {
            boolean started = EngineLauncher.start(this::setStatus);
            if (!started) setStatus("engine launch failed — check Colibri settings");
        });
        stopButton.setEnabled(false);
        refreshStatus();
    }

    @Override
    public void dispose() {
        if (last == this) last = null;
    }

    public static ColibriChatPanel getLast() {
        return last;
    }

    /** Entry point for editor actions: show a prompt and its code context, then run it. */
    public void ask(@NotNull String prompt, @NotNull String codeContext) {
        String composed = prompt + (codeContext.isEmpty() ? "" : "\n\n```\n" + codeContext + "\n```");
        input.setText(composed);
        send();
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private void refreshStatus() {
        ColibriSettings.State state = ColibriSettings.getInstance().getState();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean ok = ColibriClient.isHealthy(state.engineUrl);
            SwingUtilities.invokeLater(() -> {
                status.setText(ok ? "engine ready · " + state.engineUrl : "engine offline · " + state.engineUrl);
                status.setForeground(ok ? new JBColor(new Color(0x2f8a52), new Color(0x5fd08b))
                                        : new JBColor(new Color(0xa4462f), new Color(0xe07a63)));
                startButton.setEnabled(!ok);
            });
        });
    }

    private void send() {
        if (streaming) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        input.setText("");
        ColibriSettings.State cfg = ColibriSettings.getInstance().getState();

        append("you\n" + text + "\n\n");
        append("colibri\n");

        List<JsonObject> messages = new ArrayList<>();
        messages.add(ColibriClient.message("system",
                "You are Colibri Code, a coding assistant running on the user's own machine. "
                        + "Answer with precise, complete code. Prefer showing the full replacement for the code you change."));
        messages.addAll(history);
        messages.add(ColibriClient.message("user", text));
        history.add(ColibriClient.message("user", text));

        currentAnswer.setLength(0);
        streaming = true;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("thinking…");

        String modelId = ColibriClient.resolveModelId(cfg.engineUrl, cfg.modelId, cfg.apiKey);

        ColibriClient.streamChat(cfg.engineUrl, modelId, messages, cfg.temperature, cfg.maxTokens, cfg.apiKey,
                new ColibriClient.Callback() {
                    @Override
                    public void onDelta(@NotNull String delta) {
                        currentAnswer.append(delta);
                        SwingUtilities.invokeLater(() -> transcript.append(delta));
                    }

                    @Override
                    public void onDone() {
                        finish(null);
                    }

                    @Override
                    public void onError(@NotNull String message) {
                        finish(message);
                    }
                });
    }

    private void finish(String error) {
        SwingUtilities.invokeLater(() -> {
            streaming = false;
            sendButton.setEnabled(true);
            stopButton.setEnabled(false);
            if (error != null) {
                transcript.append("\n[error] " + error + "\n");
            } else if (currentAnswer.length() > 0) {
                history.add(ColibriClient.message("assistant", currentAnswer.toString()));
            }
            transcript.append("\n\n");
            transcript.setCaretPosition(transcript.getDocument().getLength());
            refreshStatus();
        });
    }

    private void stop() {
        Thread t = activeStream;
        if (t != null) t.interrupt();
        finish("stopped by user");
    }

    private void append(String text) {
        SwingUtilities.invokeLater(() -> {
            transcript.append(text);
            transcript.setCaretPosition(transcript.getDocument().getLength());
        });
    }
}
