package dev.formwild.model;

import java.util.Comparator;
import java.util.List;

/**
 * One completed repetition.
 *
 * @param number       1-based index within the set
 * @param depthDeg     the minimum knee angle reached — smaller is deeper
 * @param descentMs    time from the start of the descent to the bottom
 * @param totalMs      whole rep, bottom included
 * @param faults       what was wrong with it, worst first
 */
public record Rep(int number, double depthDeg, long descentMs, long totalMs, List<FormFault> faults) {

    public Rep {
        faults = List.copyOf(faults).stream()
                .sorted(Comparator.comparingInt(FormFault::severity).reversed())
                .toList();
    }

    public boolean clean() {
        return faults.isEmpty();
    }

    /** The single thing to say about this rep. */
    public String verdict() {
        return clean() ? "Good rep" : FormFault.cue(faults.getFirst());
    }
}
