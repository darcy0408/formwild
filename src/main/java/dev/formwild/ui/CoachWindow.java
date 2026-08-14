package dev.formwild.ui;

import dev.formwild.analysis.SquatAnalyzer;
import dev.formwild.capture.CaptureLoop;
import dev.formwild.capture.Frame;
import dev.formwild.model.Pose;
import dev.formwild.model.Rep;
import dev.formwild.pose.PoseEstimator;
import dev.formwild.session.SessionLog;
import org.opencv.core.Mat;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * The coach window: camera, skeleton, HUD, and the controls around them.
 *
 * <p>Threading is deliberately simple. One virtual thread runs capture → inference →
 * analysis and publishes an immutable {@link RenderState}; a Swing timer repaints from it
 * at 30 Hz on the event dispatch thread. Nothing is shared mutably across the boundary,
 * so there are no locks and no chance of painting a half-updated frame.
 */
public final class CoachWindow extends JFrame {

    private static final int REPAINT_HZ = 30;

    private final CoachPanel panel = new CoachPanel();
    private final JLabel status = new JLabel("Starting…");
    private final Path modelPath;
    private final SessionLog sessionLog = new SessionLog();

    private volatile boolean running = true;
    private volatile SquatAnalyzer analyzer = new SquatAnalyzer();
    private Thread pipeline;
    private Timer repaint;

    public CoachWindow(Path modelPath) {
        super("FormWild — squat form coach");
        this.modelPath = modelPath;

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        panel.setPreferredSize(new Dimension(960, 720));
        panel.setFocusable(true);
        add(panel, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        // Releasing the camera on close matters: a device left open stays locked against
        // the next run, which is a miserable thing to hit halfway through recording.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                shutdown();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildControls() {
        var bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        bar.setBackground(new Color(28, 31, 36));

        status.setForeground(new Color(210, 216, 226));

        JButton reset = button("New set", KeyEvent.VK_N,
                "Clear the rep count and start a new set");
        reset.addActionListener(event -> {
            analyzer.resetSet();
            panel.publish(RenderState.empty("New set — ready when you are"));
        });

        JButton export = button("Export CSV", KeyEvent.VK_E,
                "Save this set to formwild-sessions.csv");
        export.addActionListener(event -> exportSession());

        bar.add(status);
        bar.add(Box.createHorizontalGlue());
        bar.add(reset);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(export);
        return bar;
    }

    /** Buttons carry a mnemonic and an accessible description, not just a label. */
    private static JButton button(String text, int mnemonic, String description) {
        var button = new JButton(text);
        button.setMnemonic(mnemonic);
        button.setToolTipText(description);
        button.getAccessibleContext().setAccessibleDescription(description);
        return button;
    }

    private void exportSession() {
        var reps = analyzer.reps();
        if (reps.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No reps to export yet.",
                    "FormWild", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            Path written = sessionLog.append(reps);
            JOptionPane.showMessageDialog(this,
                    "Saved %d reps to %s".formatted(reps.size(), written.toAbsolutePath()),
                    "FormWild", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not write the session file: " + e.getMessage(),
                    "FormWild", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void start() {
        setVisible(true);
        repaint = new Timer(1000 / REPAINT_HZ, event -> panel.repaint());
        repaint.start();
        pipeline = Thread.ofVirtual().name("pipeline").start(this::run);
    }

    // ------------------------------------------------------------------

    private void run() {
        try (var estimator = new PoseEstimator(modelPath);
             var capture = new CaptureLoop(0)) {

            capture.start();
            updateStatus("Camera: %s · model %dx%d · stand side-on, whole body in frame"
                    .formatted(capture.backendName(), estimator.inputSide(), estimator.inputSide()));

            Deque<Long> frameTimes = new ArrayDeque<>();
            Optional<Rep> lastRep = Optional.empty();

            while (running) {
                Frame frame = capture.take(500);
                if (frame == null) continue;

                try {
                    Pose pose = estimator.estimate(frame.image(), frame.sequence(), frame.nanos());
                    Optional<Rep> completed = analyzer.accept(pose);
                    if (completed.isPresent()) {
                        lastRep = completed;
                        // A short beep is the only feedback that works when the lifter is
                        // mid-rep and not looking at the screen.
                        Toolkit.getDefaultToolkit().beep();
                    }

                    frameTimes.addLast(System.nanoTime());
                    while (frameTimes.size() > 30) frameTimes.removeFirst();

                    panel.publish(new RenderState(
                            toImage(frame.image()),
                            pose.usable() ? Optional.of(pose) : Optional.empty(),
                            analyzer.state(),
                            analyzer.repCount(),
                            SquatAnalyzer.kneeAngle(pose).orElse(Double.NaN),
                            analyzer.reps(),
                            lastRep,
                            fps(frameTimes),
                            pose.usable() ? "" : "Step back — I need your whole body in frame"));
                } finally {
                    frame.release();
                }
            }
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                panel.publish(RenderState.empty("Camera or model unavailable: " + e.getMessage()));
                updateStatus("Stopped: " + e.getMessage());
            });
        }
    }

    private static double fps(Deque<Long> frameTimes) {
        if (frameTimes.size() < 2) return 0;
        double spanSeconds = (frameTimes.getLast() - frameTimes.getFirst()) / 1e9;
        return spanSeconds <= 0 ? 0 : (frameTimes.size() - 1) / spanSeconds;
    }

    private void updateStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    /**
     * OpenCV {@link Mat} to a Swing image.
     *
     * <p>{@code TYPE_3BYTE_BGR} matches OpenCV's native channel order exactly, so this is
     * a straight byte copy with no per-pixel conversion — which matters at 30 fps.
     */
    static BufferedImage toImage(Mat bgr) {
        int width = bgr.width();
        int height = bgr.height();
        var image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = new byte[(int) (bgr.total() * bgr.channels())];
        bgr.get(0, 0, data);
        image.getRaster().setDataElements(0, 0, width, height, data);
        return image;
    }

    private void shutdown() {
        running = false;
        if (pipeline != null) {
            try {
                pipeline.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // A running Swing timer keeps the event thread alive even after the last window
        // is disposed, so without this stop the process outlives its window.
        if (repaint != null) repaint.stop();
        dispose();
    }
}
