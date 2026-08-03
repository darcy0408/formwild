package dev.formwild.model;

/**
 * One detected body point, in source image pixel coordinates.
 *
 * @param confidence 0-1 from the model; anything low is noise, not a position
 */
public record Keypoint(Joint joint, double x, double y, double confidence) {

    public boolean reliable() {
        return confidence >= Pose.MIN_CONFIDENCE;
    }
}
