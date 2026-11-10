package colibri.plugin;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
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
 * Editor actions look up the panel on their own project, never a global last window.
 */
public class ColibriChatPanel extends JPanel implements Disposable {

    private static final Key<ColibriChatPanel> PANEL_KEY = Key.create("colibri.chat.panel");
    private final Project project;

    private final List<JsonObject> history = new ArrayList<>();
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(3, 40);
    private final JLabel status = new JLabel("checking engine…");
    private final JButton sendButton = new JButton("Send");
    private final JButton stopButton = new JButton("Stop");
    private final JButton startButton = new JButton("Start engine");

    // All request/UI state, including currentAnswer, is confined to the EDT.
    private ColibriClient.StreamHandle activeStream;
    private boolean streaming;
    private boolean disposed;
    private long generation;
    private long statusGeneration;
    private final StringBuilder currentAnswer = new StringBuilder();
    private boolean reasoningShown;

    public ColibriChatPanel(Project project) {
        super(new BorderLayout(0, 0));
        this.project = project;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        project.putUserData(PANEL_KEY, this);

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
            long ticket = ++statusGeneration;
            startButton.setEnabled(false);
            status.setText("checking engine…");
            status.setForeground(JBColor.foreground());
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                boolean ready = EngineLauncher.ensureRunning(text -> SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed() || ticket != statusGeneration) return;
                    status.setText(text);
                }));
                SwingUtilities.invokeLater(() -> {
                    if (disposed || project.isDisposed() || ticket != statusGeneration) return;
                    if (!ready) {
                        status.setText("engine did not start — check Settings → Colibri Code");
                        status.setForeground(new JBColor(new Color(0xa4462f), new Color(0xe07a63)));
                        startButton.setEnabled(true);
                        return;
                    }
                    refreshStatus();
                });
            });
        });
        stopButton.setEnabled(false);
        refreshStatus();
    }

    @Override
    public void dispose() {
        // Content disposal is normally on the EDT; preserve confinement for other callers.
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                SwingUtilities.invokeAndWait(this::dispose);
            } catch (Exception e) {
                throw new IllegalStateException("Could not dispose Colibri panel", e);
            }
            return;
        }
        disposed = true;
        ++generation;
        ++statusGeneration;
        if (activeStream != null) activeStream.cancel();
        activeStream = null;
        streaming = false;
        if (project.getUserData(PANEL_KEY) == this) project.putUserData(PANEL_KEY, null);
    }

    public static ColibriChatPanel getInstance(Project project) {
        return project.getUserData(PANEL_KEY);
    }

    /** Entry point for editor actions: show a prompt and its code context, then run it. */
    public void ask(@NotNull String prompt, @NotNull String codeContext) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> ask(prompt, codeContext));
            return;
        }
        if (disposed || project.isDisposed()) return;
        if (streaming) stop();
        String composed = prompt + (codeContext.isEmpty() ? "" : "\n\n```\n" + codeContext + "\n```");
        input.setText(composed);
        send();
    }

    private void refreshStatus() {
        long ticket = ++statusGeneration;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ColibriSettings.State state = ColibriSettings.getInstance().effectiveState();
            boolean ok = ColibriClient.isHealthy(state.engineUrl, state.apiKey);
            SwingUtilities.invokeLater(() -> {
                if (disposed || project.isDisposed() || ticket != statusGeneration) return;
                status.setText(ok ? "engine ready · " + state.engineUrl : "engine offline · " + state.engineUrl);
                status.setForeground(ok ? new JBColor(new Color(0x2f8a52), new Color(0x5fd08b))
                                        : new JBColor(new Color(0xa4462f), new Color(0xe07a63)));
                startButton.setEnabled(!ok);
            });
        });
    }

    private void send() {
        if (disposed || project.isDisposed() || streaming) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        input.setText("");
        ColibriSettings.State cfg = ColibriSettings.getInstance().effectiveState();

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
        reasoningShown = false;
        streaming = true;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        ++statusGeneration;
        status.setText("starting engine…");
        status.setForeground(JBColor.foreground());
        long request = ++generation;

        // On-demand engine: a plain message brings the local server up first.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean ready = EngineLauncher.ensureRunning(message -> SwingUtilities.invokeLater(() -> {
                if (disposed || project.isDisposed() || !streaming || request != generation) return;
                status.setText(message);
            }));
            SwingUtilities.invokeLater(() -> {
                if (disposed || project.isDisposed() || !streaming || request != generation) return;
                if (!ready) {
                    finish("engine is not available — check Settings → Colibri Code");
                    return;
                }
                status.setText("thinking…");
                startStream(cfg, messages, request);
            });
        });
    }

    private void startStream(ColibriSettings.State cfg, List<JsonObject> messages, long request) {
        activeStream = ColibriClient.streamChat(cfg.engineUrl, cfg.modelId, messages,
                cfg.temperature, cfg.maxTokens, cfg.apiKey, new ColibriClient.Callback() {
                    @Override
                    public void onReasoning(@NotNull String delta) {
                        onRequest(request, () -> {
                            if (!reasoningShown) {
                                append("thinking\n");
                                reasoningShown = true;
                            }
                            append(delta);
                        });
                    }

                    @Override
                    public void onDelta(@NotNull String delta) {
                        onRequest(request, () -> {
                            if (reasoningShown && currentAnswer.length() == 0) append("\n\nanswer\n");
                            currentAnswer.append(delta);
                            append(delta);
                        });
                    }

                    @Override
                    public void onDone() {
                        onRequest(request, () -> finish(null));
                    }

                    @Override
                    public void onError(@NotNull String message) {
                        onRequest(request, () -> finish(message));
                    }
                });
    }

    private void onRequest(long request, Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (!disposed && !project.isDisposed() && streaming && request == generation) update.run();
        });
    }

    private void finish(String error) {
        streaming = false;
        activeStream = null;
        sendButton.setEnabled(true);
        stopButton.setEnabled(false);
        if (error != null) {
            append("\n[error] " + error + "\n");
            // Do not send an unanswered/cancelled turn as history on the next request.
            if (!history.isEmpty()) history.remove(history.size() - 1);
        } else if (currentAnswer.length() > 0) {
            history.add(ColibriClient.message("assistant", currentAnswer.toString()));
        }
        currentAnswer.setLength(0);
        append("\n\n");
        refreshStatus();
    }

    private void stop() {
        if (!streaming) return;
        ++generation; // Invalidate already queued EDT callbacks before cancelling I/O.
        if (activeStream != null) activeStream.cancel();
        finish("stopped by user");
    }

    private void append(String text) {
        transcript.append(text);
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }
}
