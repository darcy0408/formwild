package dev.formwild.spike;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;

/**
 * Spike B — can Java run pose estimation on a real frame?
 *
 * <p>The second half of the go/no-go gate. It deliberately <em>prints the model's own
 * input signature before building any tensor</em>: MoveNet ONNX exports differ in both
 * shape (192² for Lightning, 256² for Thunder) and dtype (int32, uint8 or float32
 * depending on who exported it), and guessing from a tutorial is the fastest way to waste
 * an afternoon on a shape mismatch.
 */
public final class SpikePose {

    /** MoveNet's output order. Index 0-16 map straight onto these. */
    private static final String[] JOINTS = {
            "nose", "left_eye", "right_eye", "left_ear", "right_ear",
            "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
            "left_wrist", "right_wrist", "left_hip", "right_hip",
            "left_knee", "right_knee", "left_ankle", "right_ankle"
    };

    public static void main(String[] args) throws Exception {
        nu.pattern.OpenCV.loadLocally();

        String modelPath = args.length > 0 ? args[0] : "models/movenet-lightning.onnx";
        String imagePath = args.length > 1 ? args[1] : "spike-frame.png";

        var env = OrtEnvironment.getEnvironment();
        try (var session = env.createSession(modelPath, new OrtSession.SessionOptions())) {

            System.out.println("=== model signature (read, not assumed) ===");
            String inputName = session.getInputNames().iterator().next();
            var inputInfo = (TensorInfo) session.getInputInfo().get(inputName).getInfo();
            long[] inputShape = inputInfo.getShape();

            System.out.printf("input  '%s'  shape=%s  type=%s%n",
                    inputName, java.util.Arrays.toString(inputShape), inputInfo.type);

            String outputName = session.getOutputNames().iterator().next();
            var outputInfo = (TensorInfo) session.getOutputInfo().get(outputName).getInfo();
            System.out.printf("output '%s'  shape=%s  type=%s%n",
                    outputName, java.util.Arrays.toString(outputInfo.getShape()), outputInfo.type);

            // Square side the model wants; fall back to 192 if the axis is dynamic (-1).
            int side = (int) (inputShape.length >= 3 && inputShape[1] > 0 ? inputShape[1] : 192);
            System.out.println("\nresizing input frame to " + side + "x" + side);

            Mat bgr = Imgcodecs.imread(imagePath);
            if (bgr.empty()) {
                System.out.println("could not read " + imagePath + " - run SpikeCamera first");
                return;
            }
            System.out.printf("source frame: %dx%d%n", bgr.width(), bgr.height());

            Mat rgb = new Mat();
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            Mat resized = new Mat();
            Imgproc.resize(rgb, resized, new Size(side, side));

            byte[] pixels = new byte[(int) (resized.total() * resized.channels())];
            resized.get(0, 0, pixels);

            long[] tensorShape = {1, side, side, 3};
            try (OnnxTensor tensor = buildTensor(env, inputInfo.type, pixels, tensorShape);
                 var result = session.run(Map.of(inputName, tensor))) {

                float[][][][] out = (float[][][][]) result.get(0).getValue();
                System.out.println("\n=== 17 keypoints (y, x normalised 0-1; confidence) ===");
                int confident = 0;
                for (int i = 0; i < JOINTS.length; i++) {
                    float y = out[0][0][i][0], x = out[0][0][i][1], score = out[0][0][i][2];
                    if (score >= 0.3f) confident++;
                    System.out.printf("  %-16s y=%.3f x=%.3f  conf=%.2f %s%n",
                            JOINTS[i], y, x, score, score >= 0.3f ? "" : "(low)");
                }
                System.out.printf("%n%d of 17 keypoints above 0.3 confidence%n", confident);
                System.out.println(confident >= 8
                        ? "\n=> Spike B PASSED. Pose estimation works end to end in Java."
                        : """

                          => Ran, but few confident keypoints. That usually means the frame
                             did not contain a clearly visible person, not that the pipeline
                             is broken. Re-run SpikeCamera standing 2m back, full body in
                             frame, with the room well lit.""");
            }
        }
    }

    /**
     * Builds the input tensor in whatever dtype the model actually declared.
     *
     * <p>MoveNet exports disagree here — the TensorFlow Hub lineage wants int32, quantised
     * exports want uint8, and some conversions want normalised float32 — so this branches
     * on the signature rather than hard-coding one and hoping.
     */
    private static OnnxTensor buildTensor(OrtEnvironment env, ai.onnxruntime.OnnxJavaType type,
                                          byte[] pixels, long[] shape) throws Exception {
        return switch (type) {
            case INT32 -> {
                IntBuffer buffer = IntBuffer.allocate(pixels.length);
                for (byte pixel : pixels) buffer.put(pixel & 0xFF);
                buffer.rewind();
                yield OnnxTensor.createTensor(env, buffer, shape);
            }
            case UINT8 -> OnnxTensor.createTensor(
                    env, ByteBuffer.wrap(pixels), shape, ai.onnxruntime.OnnxJavaType.UINT8);
            case FLOAT -> {
                FloatBuffer buffer = FloatBuffer.allocate(pixels.length);
                for (byte pixel : pixels) buffer.put((pixel & 0xFF) / 255.0f);
                buffer.rewind();
                yield OnnxTensor.createTensor(env, buffer, shape);
            }
            default -> throw new IllegalStateException(
                    "Unsupported model input type: " + type + " — inspect the export.");
        };
    }
}
