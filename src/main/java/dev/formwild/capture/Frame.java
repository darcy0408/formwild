package dev.formwild.capture;

import org.opencv.core.Mat;

/**
 * A captured frame and when it was captured.
 *
 * <p>Holds a native OpenCV {@link Mat}, so ownership matters: whoever takes a frame must
 * {@link #release()} it. Native buffers are not reclaimed by the garbage collector, and
 * a 30 fps leak becomes visible in seconds.
 *
 * @param sequence monotonic frame number
 * @param nanos    {@code System.nanoTime()} at capture, used for tempo measurement
 */
public record Frame(long sequence, long nanos, Mat image) {

    public void release() {
        image.release();
    }

    public int width() {
        return image.width();
    }

    public int height() {
        return image.height();
    }
}
