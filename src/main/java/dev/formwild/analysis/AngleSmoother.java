package dev.formwild.analysis;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Gatherers;

/**
 * Median-smooths a joint-angle series.
 *
 * <p>Raw keypoints jitter by several degrees frame to frame even when the body is still.
 * Thresholding on the raw signal double-counts reps, because the angle crosses the
 * boundary repeatedly within one movement.
 *
 * <p><b>Median, not mean.</b> A mean drags a single bad frame into its neighbours and
 * shifts the detected rep boundary in time; a median discards the outlier outright and
 * leaves the true turning point where it was. Since rep detection is fundamentally about
 * <em>when</em> the angle reversed, not shifting that instant matters more than a smooth
 * curve.
 *
 * <p>Sliding a window across a series is exactly {@link Gatherers#windowSliding(int)}, so
 * the batch path is one stream operation rather than a hand-rolled index loop.
 */
public final class AngleSmoother {

    private final int window;
    private final Deque<Double> recent;

    public AngleSmoother(int window) {
        if (window < 1 || window % 2 == 0) {
            throw new IllegalArgumentException(
                    "window must be odd and >= 1 so the median is a real sample, got " + window);
        }
        this.window = window;
        this.recent = new ArrayDeque<>(window);
    }

    /**
     * Feeds one live sample and returns the smoothed value, or empty until the window has
     * filled. Callers must treat "not yet" as "no reading", never as zero.
     */
    public OptionalDouble accept(double angle) {
        recent.addLast(angle);
        if (recent.size() > window) recent.removeFirst();
        if (recent.size() < window) return OptionalDouble.empty();
        return OptionalDouble.of(median(List.copyOf(recent)));
    }

    /** Resets between sets, so the last rep's tail cannot bleed into the next one. */
    public void reset() {
        recent.clear();
    }

    /**
     * Smooths a whole series at once — used by the tests and by replaying a recorded
     * session, where the entire signal is available up front.
     */
    public static List<Double> smooth(List<Double> raw, int window) {
        if (raw.size() < window) return List.copyOf(raw);
        return raw.stream()
                .gather(Gatherers.windowSliding(window))
                .map(AngleSmoother::median)
                .toList();
    }

    private static double median(List<Double> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }
}
