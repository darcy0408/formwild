package dev.formwild.ui;

import dev.formwild.analysis.SquatAnalyzer;
import dev.formwild.model.Pose;
import dev.formwild.model.Rep;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

/**
 * An immutable snapshot of everything the window draws.
 *
 * <p>The pipeline thread builds one of these and publishes it; the event dispatch thread
 * reads it while painting. Handing over a whole immutable value rather than letting the
 * painter reach into live analyser state means the two threads never need a lock and a
 * frame can never be drawn half-updated.
 *
 * @param kneeAngle the smoothed knee angle, or NaN when no reliable reading exists
 */
public record RenderState(
        BufferedImage image,
        Optional<Pose> pose,
        SquatAnalyzer.State state,
        int repCount,
        double kneeAngle,
        List<Rep> reps,
        Optional<Rep> lastRep,
        double fps,
        String guidance) {

    public static RenderState empty(String guidance) {
        return new RenderState(null, Optional.empty(), SquatAnalyzer.State.STANDING,
                0, Double.NaN, List.of(), Optional.empty(), 0, guidance);
    }

    /** Mean depth across the set, for the summary panel. */
    public double meanDepth() {
        return reps.stream().mapToDouble(Rep::depthDeg).average().orElse(Double.NaN);
    }

    /** Mean descent time in milliseconds, the other half of "are you rushing". */
    public double meanDescentMs() {
        return reps.stream().mapToLong(Rep::descentMs).average().orElse(Double.NaN);
    }

    public long cleanReps() {
        return reps.stream().filter(Rep::clean).count();
    }
}
