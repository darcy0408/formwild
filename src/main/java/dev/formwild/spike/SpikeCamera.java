package dev.formwild.spike;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.nio.file.Path;

/**
 * Spike A — can Java see the webcam at all?
 *
 * <p>This is the first half of the project's go/no-go gate. OpenCV's default capture
 * backend fails often on Windows, so this tries each backend in turn and reports which
 * one works rather than failing with a bare "false".
 *
 * <p>Throwaway diagnostic: kept in the repo because "how do I check my camera works"
 * is the first question anyone reproducing this project will have.
 */
public final class SpikeCamera {

    private record Backend(String name, int id) {}

    public static void main(String[] args) {
        System.out.println("Loading OpenCV native library...");
        try {
            nu.pattern.OpenCV.loadLocally();
            System.out.println("  OpenCV " + Core.VERSION + " loaded OK");
        } catch (Throwable t) {
            System.out.println("  FAILED to load OpenCV natives: " + t);
            System.out.println("  => Spike A blocked. Fall back to an ffmpeg subprocess.");
            return;
        }

        var backends = new Backend[]{
                new Backend("CAP_DSHOW (DirectShow)", Videoio.CAP_DSHOW),
                new Backend("CAP_MSMF (Media Foundation)", Videoio.CAP_MSMF),
                new Backend("CAP_ANY (auto)", Videoio.CAP_ANY),
        };

        for (Backend backend : backends) {
            System.out.println("\nTrying " + backend.name() + " on device 0...");
            VideoCapture capture = null;
            try {
                capture = new VideoCapture(0, backend.id());
                if (!capture.isOpened()) {
                    System.out.println("  not opened");
                    continue;
                }

                var frame = new Mat();
                // The first read after opening often returns an empty frame while the
                // camera warms up, so give it a few attempts before calling it a failure.
                boolean grabbed = false;
                for (int attempt = 0; attempt < 10 && !grabbed; attempt++) {
                    grabbed = capture.read(frame) && !frame.empty();
                    if (!grabbed) Thread.sleep(150);
                }

                if (!grabbed) {
                    System.out.println("  opened, but no frame arrived");
                    continue;
                }

                Path out = Path.of("spike-frame.png").toAbsolutePath();
                Imgcodecs.imwrite(out.toString(), frame);

                System.out.printf("  SUCCESS  %dx%d, %d channels%n",
                        frame.width(), frame.height(), frame.channels());
                System.out.printf("  fps reported: %.1f%n", capture.get(Videoio.CAP_PROP_FPS));
                System.out.println("  wrote " + out);
                System.out.println("\n=> Spike A PASSED using " + backend.name());
                return;

            } catch (Throwable t) {
                System.out.println("  error: " + t);
            } finally {
                if (capture != null) capture.release();
            }
        }

        System.out.println("""

                => Spike A FAILED on every backend.
                   Possible causes: no camera attached, another application is holding it,
                   or Windows camera privacy settings block desktop apps.
                   Check: Settings > Privacy & security > Camera > "Let desktop apps access your camera".
                   Fallbacks: an ffmpeg subprocess, or use a phone as a webcam
                   (which also strengthens the bring-your-own-device angle).""");
    }
}
