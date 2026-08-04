package dev.formwild;

import dev.formwild.analysis.SquatAnalyzer;
import dev.formwild.capture.CaptureLoop;
import dev.formwild.capture.Frame;
import dev.formwild.model.FormFault;
import dev.formwild.model.Pose;
import dev.formwild.model.Rep;
import dev.formwild.pose.PoseEstimator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FormWild entry point.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code --coach} open the coach window (default)</li>
 *   <li>{@code --diagnose [seconds]} run the real pipeline headless and report throughput</li>
 *   <li>{@code --version} print the running JDK (contest verification)</li>
 * </ul>
 */
public final class Main {

    private static final Path DEFAULT_MODEL = Path.of("models", "movenet-lightning.onnx");

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--coach";

        switch (mode) {
            case "--version" -> printVersion();
            case "--diagnose" -> diagnose(args.length > 1 ? Integer.parseInt(args[1]) : 10);
            case "--coach" -> coach();
            case "--help", "-h" -> printHelp();
            default -> {
                System.err.println("Unknown mode: " + mode);
                printHelp();
                System.exit(2);
            }
        }
    }

    /**
     * Runs capture → pose → rep detection for a fixed period with no window, printing
     * what the pipeline actually achieved.
     *
     * <p>Exists because a GUI cannot be verified in a terminal, on a build server, or by
     * anyone reproducing this project over SSH. If this prints a healthy frame rate and
     * sensible joint counts, the hard parts work and only the drawing is left.
     */
    private static void diagnose(int seconds) throws Exception {
        if (!Files.exists(DEFAULT_MODEL)) {
            System.err.println("Model not found at " + DEFAULT_MODEL.toAbsolutePath());
            System.err.println("Run scripts\\fetch-model.ps1 first.");
            System.exit(1);
        }

        PoseEstimator.loadNatives();
        System.out.println("OpenCV " + PoseEstimator.openCvVersion());

        try (var estimator = new PoseEstimator(DEFAULT_MODEL);
             var capture = new CaptureLoop(0)) {

            System.out.println("camera backend: " + capture.backendName());
            System.out.println("model input: " + estimator.inputSide() + "x" + estimator.inputSide());
            System.out.printf("running the pipeline for %d seconds...%n%n", seconds);

            capture.start();
            var analyzer = new SquatAnalyzer();

            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            long inferences = 0;
            long usablePoses = 0;
            long totalConfidentJoints = 0;
            long inferenceNanos = 0;

            while (System.nanoTime() < deadline) {
                Frame frame = capture.take(500);
                if (frame == null) continue;
                try {
                    long start = System.nanoTime();
                    Pose pose = estimator.estimate(frame.image(), frame.sequence(), frame.nanos());
                    inferenceNanos += System.nanoTime() - start;
                    inferences++;

                    totalConfidentJoints += pose.confidentBodyJoints();
                    if (pose.usable()) usablePoses++;

                    analyzer.accept(pose).ifPresent(Main::announce);
                } finally {
                    frame.release();
                }
            }

            double elapsed = seconds;
            System.out.printf("""
                    inference rate     : %.1f fps
                    mean inference time: %.0f ms
                    frames captured    : %d
                    frames dropped     : %d  (%.0f%% - dropped deliberately to keep latency flat)
                    usable poses       : %d of %d
                    mean body joints   : %.1f of 12
                    reps counted       : %d
                    %n""",
                    inferences / elapsed,
                    inferences == 0 ? 0 : inferenceNanos / 1_000_000.0 / inferences,
                    capture.capturedCount(),
                    capture.droppedCount(),
                    capture.capturedCount() == 0 ? 0
                            : 100.0 * capture.droppedCount() / capture.capturedCount(),
                    usablePoses, inferences,
                    inferences == 0 ? 0 : (double) totalConfidentJoints / inferences,
                    analyzer.repCount());

            if (usablePoses == 0) {
                System.out.println("""
                        No usable pose was seen. That is a camera framing problem, not a
                        code problem: stand side-on, about 2 m back, with your whole body
                        in frame and the room well lit.""");
            }
        }
    }

    private static void announce(Rep rep) {
        System.out.printf("rep %-2d  depth %.0f°  descent %d ms  %s%n",
                rep.number(), rep.depthDeg(), rep.descentMs(),
                rep.clean() ? "clean" : rep.faults().stream()
                        .map(FormFault::cue).reduce((a, b) -> a + "; " + b).orElse(""));
    }

    /** Opens the coach window. */
    private static void coach() {
        if (!Files.exists(DEFAULT_MODEL)) {
            System.err.println("Model not found at " + DEFAULT_MODEL.toAbsolutePath());
            System.err.println("Run scripts\fetch-model.ps1 first.");
            System.exit(1);
        }
        PoseEstimator.loadNatives();
        javax.swing.SwingUtilities.invokeLater(
                () -> new dev.formwild.ui.CoachWindow(DEFAULT_MODEL).start());
    }

    private static void printHelp() {
        System.out.println("""
                FormWild — a webcam form coach for squats

                  --coach               open the coach window (default)
                  --diagnose [seconds]  run the pipeline headless and report throughput
                  --version             runtime and Java version

                Camera: stand side-on, about 2 m back, whole body in frame, room lit.
                Run scripts\\fetch-model.ps1 once before first use.
                """);
    }

    private static void printVersion() {
        var runtime = Runtime.version();
        System.out.printf("""
                FormWild 1.0.0
                Java runtime : %s
                Java vendor  : %s
                Feature ver  : %d
                Preview      : %s
                """,
                runtime, System.getProperty("java.vendor"), runtime.feature(),
                previewEnabled() ? "enabled" : "disabled");
    }

    private static boolean previewEnabled() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().contains("--enable-preview");
    }
}
