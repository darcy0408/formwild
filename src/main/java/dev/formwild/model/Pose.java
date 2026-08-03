package dev.formwild.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One frame's worth of body keypoints, in <b>source image pixel coordinates</b>.
 *
 * <p>The estimator has already undone the letterbox transform, so callers never deal with
 * the model's 192×192 normalised space.
 *
 * @param sequence monotonic frame number, for ordering and dropped-frame accounting
 * @param nanos    capture timestamp, used for tempo measurement
 */
public record Pose(long sequence, long nanos, Map<Joint, Keypoint> keypoints) {

    /** Below this, a keypoint is noise and must not feed an angle calculation. */
    public static final double MIN_CONFIDENCE = 0.30;

    public Pose {
        keypoints = Map.copyOf(keypoints);
    }

    public static Pose of(long sequence, long nanos, List<Keypoint> points) {
        var map = new EnumMap<Joint, Keypoint>(Joint.class);
        for (Keypoint point : points) map.put(point.joint(), point);
        return new Pose(sequence, nanos, map);
    }

    public Optional<Keypoint> get(Joint joint) {
        return Optional.ofNullable(keypoints.get(joint));
    }

    /** A keypoint only if it is confident enough to trust. */
    public Optional<Keypoint> reliable(Joint joint) {
        return get(joint).filter(Keypoint::reliable);
    }

    /**
     * Interior angle at {@code vertex}, in degrees, or empty if any of the three joints
     * is missing or unreliable.
     *
     * <p>Empty rather than a sentinel: a knee angle of 0 or -1 would silently become a
     * "very deep squat" downstream.
     */
    public OptionalDouble angle(Joint from, Joint vertex, Joint to) {
        var a = reliable(from);
        var b = reliable(vertex);
        var c = reliable(to);
        if (a.isEmpty() || b.isEmpty() || c.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(angleBetween(a.get(), b.get(), c.get()));
    }

    private static double angleBetween(Keypoint a, Keypoint b, Keypoint c) {
        double abx = a.x() - b.x(), aby = a.y() - b.y();
        double cbx = c.x() - b.x(), cby = c.y() - b.y();
        double dot = abx * cbx + aby * cby;
        double magnitude = Math.hypot(abx, aby) * Math.hypot(cbx, cby);
        if (magnitude == 0) return 0;
        // Clamp: floating point can push the quotient a hair outside [-1,1] and NaN acos.
        double cosine = Math.max(-1, Math.min(1, dot / magnitude));
        return Math.toDegrees(Math.acos(cosine));
    }

    /** How many body joints were seen confidently — a proxy for "is the user in frame". */
    public long confidentBodyJoints() {
        return keypoints.values().stream()
                .filter(point -> point.joint().isBody() && point.reliable())
                .count();
    }

    /** Whether enough of the body is visible to judge form at all. */
    public boolean usable() {
        return confidentBodyJoints() >= 6;
    }
}
