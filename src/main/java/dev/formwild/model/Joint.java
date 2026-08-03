package dev.formwild.model;

import java.util.List;

/**
 * The 17 body keypoints MoveNet reports, <b>in the model's own output order</b>.
 *
 * <p>Index 0-16 map straight onto the model's second-to-last tensor axis, so do not
 * reorder these constants.
 */
public enum Joint {
    NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR,
    LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW,
    LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP,
    LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE;

    /** Pairs to draw as bones in the overlay. */
    public static final List<Joint[]> SKELETON = List.of(
            new Joint[]{LEFT_SHOULDER, RIGHT_SHOULDER},
            new Joint[]{LEFT_SHOULDER, LEFT_ELBOW},
            new Joint[]{LEFT_ELBOW, LEFT_WRIST},
            new Joint[]{RIGHT_SHOULDER, RIGHT_ELBOW},
            new Joint[]{RIGHT_ELBOW, RIGHT_WRIST},
            new Joint[]{LEFT_SHOULDER, LEFT_HIP},
            new Joint[]{RIGHT_SHOULDER, RIGHT_HIP},
            new Joint[]{LEFT_HIP, RIGHT_HIP},
            new Joint[]{LEFT_HIP, LEFT_KNEE},
            new Joint[]{LEFT_KNEE, LEFT_ANKLE},
            new Joint[]{RIGHT_HIP, RIGHT_KNEE},
            new Joint[]{RIGHT_KNEE, RIGHT_ANKLE});

    /** Joints below the neck — the ones that matter for lifting form. */
    public boolean isBody() {
        return ordinal() >= LEFT_SHOULDER.ordinal();
    }
}
