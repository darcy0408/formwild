package dev.formwild.analysis;

import dev.formwild.model.FormFault;
import dev.formwild.model.Joint;
import dev.formwild.model.Keypoint;
import dev.formwild.model.Pose;
import dev.formwild.model.Rep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Counts squats and names what was wrong with each one.
 *
 * <p><b>Assumes a side-on camera.</b> Knee angle, depth, tempo and torso angle are all
 * measurable in profile; knees caving inward is not, because that motion happens along
 * the axis a side view projects away. See {@link dev.formwild.model.FormFault} for why
 * this project would rather measure three things properly than five things badly.
 *
 * <p>Drives a four-state machine off the smoothed knee angle:
 *
 * <pre>
 *   STANDING --(angle &lt; 150°)--&gt; DESCENDING --(angle rises again)--&gt; ASCENDING
 *      ^                                                                  |
 *      +-------------------(angle &gt; 165°, rep counted)--------------------+
 * </pre>
 *
 * <p><b>The two thresholds are deliberately different.</b> With a single boundary, a
 * lifter pausing near it produces a burst of phantom reps as noise pushes the angle back
 * and forth across the line. Requiring the angle to travel from below 150° to above 165°
 * means a rep needs real movement, not jitter.
 */
public final class SquatAnalyzer {

    /** Knee angle below which we consider the descent genuinely started. */
    private static final double DESCENT_THRESHOLD = 150;
    /** Knee angle above which we consider the lifter standing again. Hysteresis gap: 15°. */
    private static final double STANDING_THRESHOLD = 165;
    /** Depth target: parallel is roughly 90°, so 100° allows a little grace. */
    private static final double DEPTH_TARGET = 100;
    /** Faster than this and the lifter is dropping, not lowering. */
    private static final long RUSHED_DESCENT_MS = 800;
    /** A rep must get at least this deep before its tempo is worth judging. */
    private static final double MEANINGFUL_DESCENT_DEG = 130;
    /** Torso further from vertical than this at the bottom is a forward fold. */
    private static final double TORSO_LEAN_DEG = 55;

    public enum State { STANDING, DESCENDING, ASCENDING }

    private final AngleSmoother smoother = new AngleSmoother(5);
    private final List<Rep> reps = new ArrayList<>();

    private State state = State.STANDING;
    private double minAngleThisRep = Double.MAX_VALUE;
    private double previousAngle = Double.NaN;
    private long descentStartNanos;
    private long repStartNanos;

    /** Worst torso lean observed during the current descent. */
    private double worstTorsoLean;

    /** Feeds one pose. Returns a completed rep on the frame the lifter stands back up. */
    public Optional<Rep> accept(Pose pose) {
        OptionalDouble rawAngle = kneeAngle(pose);
        if (rawAngle.isEmpty()) return Optional.empty();

        OptionalDouble smoothed = smoother.accept(rawAngle.getAsDouble());
        if (smoothed.isEmpty()) return Optional.empty();      // window still filling

        double angle = smoothed.getAsDouble();
        Optional<Rep> completed = Optional.empty();

        switch (state) {
            case STANDING -> {
                if (angle < DESCENT_THRESHOLD) {
                    state = State.DESCENDING;
                    descentStartNanos = pose.nanos();
                    repStartNanos = pose.nanos();
                    minAngleThisRep = angle;
                    worstTorsoLean = 0;
                }
            }
            case DESCENDING -> {
                minAngleThisRep = Math.min(minAngleThisRep, angle);
                sampleFaults(pose);
                // The bottom is where the angle stops decreasing. A small tolerance stops
                // one noisy frame from declaring the turn early.
                if (angle > previousAngle + 1.5) {
                    state = State.ASCENDING;
                }
            }
            case ASCENDING -> {
                minAngleThisRep = Math.min(minAngleThisRep, angle);
                if (angle > STANDING_THRESHOLD) {
                    completed = Optional.of(finishRep(pose.nanos()));
                    state = State.STANDING;
                }
            }
        }

        previousAngle = angle;
        return completed;
    }

    private Rep finishRep(long nowNanos) {
        long descentMs = (nowNanos - descentStartNanos) / 1_000_000;
        long totalMs = (nowNanos - repStartNanos) / 1_000_000;

        var faults = new ArrayList<FormFault>();
        if (minAngleThisRep > DEPTH_TARGET) {
            faults.add(new FormFault.ShallowDepth(minAngleThisRep, DEPTH_TARGET));
        }
        if (worstTorsoLean > TORSO_LEAN_DEG) {
            faults.add(new FormFault.TorsoLean(worstTorsoLean));
        }
        // Tempo only means something once the lifter actually travelled. A shallow dip
        // is already reported as shallow; calling it "rushed" as well is noise.
        if (descentMs < RUSHED_DESCENT_MS && minAngleThisRep < MEANINGFUL_DESCENT_DEG) {
            faults.add(new FormFault.RushedDescent(descentMs));
        }

        var rep = new Rep(reps.size() + 1, minAngleThisRep, descentMs, totalMs, faults);
        reps.add(rep);
        minAngleThisRep = Double.MAX_VALUE;
        return rep;
    }

    /** Records the worst form reading seen on the way down. */
    private void sampleFaults(Pose pose) {
        torsoLean(pose).ifPresent(value -> worstTorsoLean = Math.max(worstTorsoLean, value));
    }

    /**
     * Knee angle, averaged across both legs when both are visible.
     *
     * <p>Averaging rather than picking one leg means a single badly-tracked limb degrades
     * the reading instead of destroying it.
     */
    public static OptionalDouble kneeAngle(Pose pose) {
        OptionalDouble left = pose.angle(Joint.LEFT_HIP, Joint.LEFT_KNEE, Joint.LEFT_ANKLE);
        OptionalDouble right = pose.angle(Joint.RIGHT_HIP, Joint.RIGHT_KNEE, Joint.RIGHT_ANKLE);
        if (left.isPresent() && right.isPresent()) {
            return OptionalDouble.of((left.getAsDouble() + right.getAsDouble()) / 2);
        }
        return left.isPresent() ? left : right;
    }

    /** Angle of the shoulder-to-hip line away from vertical, in degrees. */
    static OptionalDouble torsoLean(Pose pose) {
        Optional<Keypoint> shoulder = midpoint(pose, Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER);
        Optional<Keypoint> hip = midpoint(pose, Joint.LEFT_HIP, Joint.RIGHT_HIP);
        if (shoulder.isEmpty() || hip.isEmpty()) return OptionalDouble.empty();

        double dx = shoulder.get().x() - hip.get().x();
        double dy = shoulder.get().y() - hip.get().y();
        if (dx == 0 && dy == 0) return OptionalDouble.empty();
        return OptionalDouble.of(Math.toDegrees(Math.atan2(Math.abs(dx), Math.abs(dy))));
    }

    private static Optional<Keypoint> midpoint(Pose pose, Joint a, Joint b) {
        var first = pose.reliable(a);
        var second = pose.reliable(b);
        if (first.isEmpty() || second.isEmpty()) return Optional.empty();
        return Optional.of(new Keypoint(a,
                (first.get().x() + second.get().x()) / 2,
                (first.get().y() + second.get().y()) / 2,
                Math.min(first.get().confidence(), second.get().confidence())));
    }

    public List<Rep> reps() {
        return List.copyOf(reps);
    }

    public State state() {
        return state;
    }

    public int repCount() {
        return reps.size();
    }

    /** Live depth readout for the UI gauge, before any rep completes. */
    public double currentMinAngle() {
        return minAngleThisRep == Double.MAX_VALUE ? Double.NaN : minAngleThisRep;
    }

    public void resetSet() {
        reps.clear();
        smoother.reset();
        state = State.STANDING;
        minAngleThisRep = Double.MAX_VALUE;
        previousAngle = Double.NaN;
    }
}
