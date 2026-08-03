package dev.formwild.model;

/**
 * A specific, named thing wrong with a rep.
 *
 * <p>Sealed so every consumer switches exhaustively with no {@code default}: adding a
 * fault here is a compile error until the coach knows how to call it out, which is the
 * safety net a growing rule set wants.
 *
 * <p>Each carries the measurement that triggered it. "Go deeper" is a nag; "you hit 112°,
 * aim for 90°" is coaching.
 *
 * <p><b>Why there is no knee-valgus fault here.</b> Knee angle is only measurable from a
 * side-on camera, and knees caving inward is only measurable from a front-on one — the
 * inward travel happens along the axis a side view projects away. One 2D camera cannot
 * supply both, so v1 commits to the side-on view that depth, tempo and torso angle all
 * need, and does not pretend to judge what it cannot see. Left/right asymmetry is absent
 * for the same reason: side-on, the far leg is occluded by the near one.
 */
public sealed interface FormFault {

    /** Worst-first ordering when several fire on one rep. */
    int severity();

    record ShallowDepth(double achievedDeg, double targetDeg) implements FormFault {
        public int severity() { return 90; }
    }

    record TorsoLean(double degreesFromVertical) implements FormFault {
        public int severity() { return 70; }
    }

    record RushedDescent(long millis) implements FormFault {
        public int severity() { return 50; }
    }

    /** The coaching cue spoken or shown for this fault. */
    static String cue(FormFault fault) {
        return switch (fault) {
            case ShallowDepth(double achieved, double target) ->
                    "Go deeper — %.0f°, aim for %.0f°".formatted(achieved, target);
            case TorsoLean(double degrees) ->
                    "Chest up — you're %.0f° forward".formatted(degrees);
            case RushedDescent(long millis) ->
                    "Slow the descent — %d ms, aim for 1500".formatted(millis);
        };
    }

    /** Short tag for the session summary tally. */
    static String label(FormFault fault) {
        return switch (fault) {
            case ShallowDepth _  -> "depth";
            case TorsoLean _     -> "torso";
            case RushedDescent _ -> "tempo";
        };
    }
}
