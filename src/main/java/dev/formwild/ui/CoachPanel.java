package dev.formwild.ui;

import dev.formwild.model.FormFault;
import dev.formwild.model.Joint;
import dev.formwild.model.Keypoint;
import dev.formwild.model.Pose;
import dev.formwild.model.Rep;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Draws the camera feed, the skeleton, and the coaching HUD.
 *
 * <p>Sizes and contrasts are chosen for a screen recording: the rep counter has to stay
 * readable when a 1080p capture is scaled down into a demo video, and every colour-coded
 * element is also labelled in words so the display works in greyscale and for anyone who
 * cannot separate the colours.
 */
public final class CoachPanel extends JPanel {

    private static final Color INK = new Color(250, 250, 250);
    private static final Color SHADOW = new Color(0, 0, 0, 165);
    private static final Color GOOD = new Color(80, 220, 130);
    private static final Color WARN = new Color(255, 190, 70);
    private static final Color BAD = new Color(255, 105, 97);
    private static final Color BONE = new Color(120, 210, 255);

    /** Knee angle treated as fully extended / fully deep for the gauge. */
    private static final double GAUGE_TOP = 180;
    private static final double GAUGE_BOTTOM = 80;
    private static final double DEPTH_TARGET = 100;

    private final AtomicReference<RenderState> state =
            new AtomicReference<>(RenderState.empty("Starting camera…"));

    /** Called from the pipeline thread; painting picks it up on the next repaint. */
    public void publish(RenderState next) {
        state.set(next);
    }

    public RenderState current() {
        return state.get();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(18, 20, 24));
        g.fillRect(0, 0, getWidth(), getHeight());

        RenderState snapshot = state.get();
        BufferedImage image = snapshot.image();

        if (image == null) {
            drawCentredMessage(g, snapshot.guidance());
            g.dispose();
            return;
        }

        // Letterbox the video into the panel, preserving aspect ratio.
        double scale = Math.min((double) getWidth() / image.getWidth(),
                (double) getHeight() / image.getHeight());
        int drawWidth = (int) (image.getWidth() * scale);
        int drawHeight = (int) (image.getHeight() * scale);
        int offsetX = (getWidth() - drawWidth) / 2;
        int offsetY = (getHeight() - drawHeight) / 2;

        g.drawImage(image, offsetX, offsetY, drawWidth, drawHeight, null);

        snapshot.pose().ifPresent(pose -> drawSkeleton(g, pose, offsetX, offsetY, scale));

        drawRepCounter(g, snapshot);
        drawDepthGauge(g, snapshot);
        drawCue(g, snapshot);
        drawSummary(g, snapshot);

        g.dispose();
    }

    // ------------------------------------------------------------------

    private void drawSkeleton(Graphics2D g, Pose pose, int offsetX, int offsetY, double scale) {
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (Joint[] bone : Joint.SKELETON) {
            Optional<Keypoint> from = pose.reliable(bone[0]);
            Optional<Keypoint> to = pose.reliable(bone[1]);
            if (from.isEmpty() || to.isEmpty()) continue;   // never draw a guessed bone

            g.setColor(BONE);
            g.draw(new Line2D.Double(
                    offsetX + from.get().x() * scale, offsetY + from.get().y() * scale,
                    offsetX + to.get().x() * scale, offsetY + to.get().y() * scale));
        }

        for (Keypoint point : pose.keypoints().values()) {
            if (!point.reliable() || !point.joint().isBody()) continue;
            // Opacity carries confidence, so a marginal joint looks marginal.
            int alpha = (int) Math.min(255, 120 + point.confidence() * 135);
            g.setColor(new Color(255, 255, 255, alpha));
            double x = offsetX + point.x() * scale;
            double y = offsetY + point.y() * scale;
            g.fill(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
        }
    }

    private void drawRepCounter(Graphics2D g, RenderState snapshot) {
        String count = String.valueOf(snapshot.repCount());
        g.setFont(getFont().deriveFont(Font.BOLD, 96f));
        shadowText(g, count, 28, 104, INK);

        g.setFont(getFont().deriveFont(Font.BOLD, 20f));
        shadowText(g, snapshot.repCount() == 1 ? "REP" : "REPS", 32, 132, INK);

        // The state is spelled out rather than implied by a colour change.
        g.setFont(getFont().deriveFont(Font.PLAIN, 16f));
        shadowText(g, snapshot.state().name().toLowerCase(), 32, 158, new Color(190, 200, 215));
    }

    /**
     * A vertical depth gauge with the target marked.
     *
     * <p>Shows how deep this rep is going <em>while it is happening</em>, which is the
     * only moment the information can still change what the lifter does.
     */
    private void drawDepthGauge(Graphics2D g, RenderState snapshot) {
        int x = getWidth() - 92;
        int y = 40;
        int width = 34;
        int height = Math.max(140, getHeight() / 3);

        g.setColor(SHADOW);
        g.fillRoundRect(x - 8, y - 26, width + 60, height + 56, 12, 12);

        g.setColor(new Color(255, 255, 255, 45));
        g.fillRoundRect(x, y, width, height, 8, 8);

        double angle = snapshot.kneeAngle();
        if (!Double.isNaN(angle)) {
            double fraction = (GAUGE_TOP - angle) / (GAUGE_TOP - GAUGE_BOTTOM);
            fraction = Math.max(0, Math.min(1, fraction));
            int filled = (int) (height * fraction);
            g.setColor(angle <= DEPTH_TARGET ? GOOD : WARN);
            g.fillRoundRect(x, y + height - filled, width, filled, 8, 8);
        }

        // Target line, labelled in words and degrees, not just drawn.
        double targetFraction = (GAUGE_TOP - DEPTH_TARGET) / (GAUGE_TOP - GAUGE_BOTTOM);
        int targetY = (int) (y + height - height * targetFraction);
        g.setColor(INK);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x - 6, targetY, x + width + 6, targetY);

        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        shadowText(g, "TARGET", x + width + 10, targetY - 4, INK);
        g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        shadowText(g, "depth", x, y - 10, INK);
        shadowText(g, Double.isNaN(angle) ? "--" : "%.0f°".formatted(angle),
                x, y + height + 20, INK);
    }

    private void drawCue(Graphics2D g, RenderState snapshot) {
        Optional<Rep> last = snapshot.lastRep();
        String message;
        Color tint;

        if (snapshot.pose().isEmpty()) {
            message = snapshot.guidance();
            tint = WARN;
        } else if (last.isEmpty()) {
            message = "Ready — start when you are";
            tint = INK;
        } else if (last.get().clean()) {
            message = "Rep %d — good rep".formatted(last.get().number());
            tint = GOOD;
        } else {
            message = "Rep %d — %s".formatted(
                    last.get().number(), FormFault.cue(last.get().faults().getFirst()));
            tint = BAD;
        }

        g.setFont(getFont().deriveFont(Font.BOLD, 26f));
        int textWidth = g.getFontMetrics().stringWidth(message);
        int boxWidth = Math.min(getWidth() - 40, textWidth + 44);
        int boxX = (getWidth() - boxWidth) / 2;
        int boxY = getHeight() - 96;

        g.setColor(SHADOW);
        g.fillRoundRect(boxX, boxY, boxWidth, 56, 14, 14);
        // A coloured edge AND the words themselves: colour is never the only signal.
        g.setColor(tint);
        g.fillRoundRect(boxX, boxY, 7, 56, 6, 6);

        shadowText(g, message, boxX + 22, boxY + 37, INK);
    }

    private void drawSummary(Graphics2D g, RenderState snapshot) {
        g.setFont(getFont().deriveFont(Font.PLAIN, 14f));
        int y = getHeight() - 22;

        String summary = snapshot.reps().isEmpty()
                ? "no reps yet"
                : "%d clean of %d · mean depth %.0f° · mean descent %.0f ms".formatted(
                        snapshot.cleanReps(), snapshot.repCount(),
                        snapshot.meanDepth(), snapshot.meanDescentMs());

        shadowText(g, summary, 24, y, new Color(205, 212, 224));
        String fps = "%.0f fps".formatted(snapshot.fps());
        shadowText(g, fps, getWidth() - g.getFontMetrics().stringWidth(fps) - 24, y,
                new Color(150, 160, 175));
    }

    private void drawCentredMessage(Graphics2D g, String message) {
        g.setFont(getFont().deriveFont(Font.PLAIN, 20f));
        int width = g.getFontMetrics().stringWidth(message);
        g.setColor(INK);
        g.drawString(message, (getWidth() - width) / 2, getHeight() / 2);
    }

    /** Text with a dark halo, so the HUD stays legible over any camera image. */
    private static void shadowText(Graphics2D g, String text, int x, int y, Color colour) {
        g.setColor(new Color(0, 0, 0, 190));
        g.drawString(text, x + 2, y + 2);
        g.setColor(colour);
        g.drawString(text, x, y);
    }
}
