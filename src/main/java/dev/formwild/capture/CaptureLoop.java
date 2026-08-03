package dev.formwild.capture;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pulls frames from the webcam on a virtual thread and publishes the newest one.
 *
 * <p><b>Backpressure is the whole design.</b> The camera produces ~30 frames a second and
 * pose inference consumes maybe 12, so an unbounded queue would grow without limit and
 * the overlay would drift further behind the user with every rep — the app would look
 * broken precisely when someone is filming it. Instead the queue holds exactly one frame
 * and the producer <em>discards the older one</em>: latency stays flat and inference
 * always works on the most recent reality, at the cost of frames nobody would have seen
 * anyway.
 *
 * <p>Dropped frames are counted rather than ignored, so the UI can be honest about the
 * real inference rate.
 */
public final class CaptureLoop implements AutoCloseable {

    /** Backends to try, in the order that actually works on Windows. */
    private static final int[] BACKENDS = {Videoio.CAP_DSHOW, Videoio.CAP_MSMF, Videoio.CAP_ANY};

    private final BlockingQueue<Frame> latest = new ArrayBlockingQueue<>(1);
    private final VideoCapture capture;
    private final String backendName;

    private volatile boolean running = true;
    private volatile long captured;
    private volatile long dropped;
    private Thread thread;

    public CaptureLoop(int deviceIndex) {
        VideoCapture opened = null;
        String name = null;

        for (int backend : BACKENDS) {
            VideoCapture candidate = new VideoCapture(deviceIndex, backend);
            if (candidate.isOpened() && warmUp(candidate)) {
                opened = candidate;
                name = switch (backend) {
                    case Videoio.CAP_DSHOW -> "DirectShow";
                    case Videoio.CAP_MSMF -> "Media Foundation";
                    default -> "auto";
                };
                break;
            }
            candidate.release();
        }

        if (opened == null) {
            throw new IllegalStateException("""
                    No working camera found on device %d.
                      - Is another application using it?
                      - Settings > Privacy & security > Camera > allow desktop apps
                    """.formatted(deviceIndex));
        }

        this.capture = opened;
        this.backendName = name;
    }

    /**
     * The first reads after opening routinely come back empty while the sensor spins up,
     * so a single failed read must not condemn the backend.
     */
    private static boolean warmUp(VideoCapture candidate) {
        var probe = new Mat();
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (candidate.read(probe) && !probe.empty()) return true;
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } finally {
            probe.release();
        }
    }

    public void start() {
        thread = Thread.ofVirtual().name("capture").start(this::run);
    }

    private void run() {
        while (running) {
            var image = new Mat();
            if (!capture.read(image) || image.empty()) {
                image.release();
                continue;
            }
            captured++;
            var frame = new Frame(captured, System.nanoTime(), image);

            // Drop-oldest: replace whatever is waiting rather than blocking or growing.
            Frame stale = latest.poll();
            if (stale != null) {
                dropped++;
                stale.release();
            }
            if (!latest.offer(frame)) {
                dropped++;
                frame.release();
            }
        }
    }

    /**
     * The most recent frame, waiting briefly if none has arrived yet.
     *
     * <p>The caller owns the returned frame and must {@link Frame#release()} it.
     */
    public Frame take(long timeoutMillis) throws InterruptedException {
        return latest.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public long capturedCount() {
        return captured;
    }

    public long droppedCount() {
        return dropped;
    }

    public String backendName() {
        return backendName;
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Frame remaining = latest.poll();
        if (remaining != null) remaining.release();
        // Releasing the device matters: a camera left open stays locked against the next
        // run, which is a miserable thing to discover mid-recording.
        capture.release();
    }
}
