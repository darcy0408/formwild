package dev.formwild.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AngleSmootherTest {

    @Test
    @DisplayName("removes single-frame spikes")
    void killsSpikes() {
        // A clean ramp with one catastrophic outlier, as a mistracked frame produces.
        var raw = new ArrayList<Double>();
        for (int i = 0; i < 20; i++) raw.add(100.0 + i);
        raw.set(10, 5.0);

        List<Double> smoothed = AngleSmoother.smooth(raw, 5);

        assertTrue(smoothed.stream().allMatch(v -> v >= 100),
                "the 5-degree spike should not survive: " + smoothed);
    }

    @Test
    @DisplayName("does not shift the location of the true minimum")
    void preservesTurningPoint() {
        // A V shape: down to a trough at index 20, then back up. Rep detection is about
        // *when* the angle reversed, so the trough must not move.
        var raw = new ArrayList<Double>();
        for (int i = 0; i <= 20; i++) raw.add(170.0 - i * 4);
        for (int i = 1; i <= 20; i++) raw.add(90.0 + i * 4);

        List<Double> smoothed = AngleSmoother.smooth(raw, 5);

        int rawMin = indexOfMin(raw);
        int smoothedMin = indexOfMin(smoothed) + 2;   // +2 for the window's half-offset

        assertEquals(rawMin, smoothedMin, 2,
                "the turning point moved, which would shift every rep boundary");
    }

    @Test
    @DisplayName("median beats mean on a spike, which is why it is used")
    void medianOutperformsMeanOnOutliers() {
        List<Double> raw = List.of(100.0, 100.0, 5.0, 100.0, 100.0);

        double median = AngleSmoother.smooth(raw, 5).getFirst();
        double mean = raw.stream().mapToDouble(Double::doubleValue).average().orElseThrow();

        assertEquals(100.0, median, 0.001, "median discards the outlier outright");
        assertTrue(mean < 85, "the mean is dragged far off by the same outlier: " + mean);
    }

    @Test
    @DisplayName("live feed reports nothing until the window has filled")
    void liveFeedWaitsForWindow() {
        var smoother = new AngleSmoother(5);
        assertTrue(smoother.accept(100).isEmpty());
        assertTrue(smoother.accept(101).isEmpty());
        assertTrue(smoother.accept(102).isEmpty());
        assertTrue(smoother.accept(103).isEmpty());
        assertTrue(smoother.accept(104).isPresent(), "the fifth sample completes the window");
    }

    @Test
    @DisplayName("rejects an even window, which has no true median sample")
    void rejectsEvenWindow() {
        assertThrows(IllegalArgumentException.class, () -> new AngleSmoother(4));
    }

    @Test
    @DisplayName("a series shorter than the window is returned untouched")
    void shortSeriesPassesThrough() {
        List<Double> raw = List.of(1.0, 2.0);
        assertEquals(raw, AngleSmoother.smooth(raw, 5));
    }

    private static int indexOfMin(List<Double> values) {
        int best = 0;
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) < values.get(best)) best = i;
        }
        return best;
    }
}
