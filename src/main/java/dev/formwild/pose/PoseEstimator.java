package dev.formwild.pose;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import dev.formwild.model.Joint;
import dev.formwild.model.Keypoint;
import dev.formwild.model.Pose;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;

/**
 * Runs MoveNet SinglePose on a frame and returns keypoints in source-image coordinates.
 *
 * <p>Everything happens locally. No frame is written to disk and nothing leaves the
 * machine — which for a camera pointed at someone's living room is the whole point.
 *
 * <p>Not thread-safe: {@link OrtSession#run} is called on a single inference thread.
 */
public final class PoseEstimator implements AutoCloseable {

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String inputName;
    private final OnnxJavaType inputType;
    private final int side;

    /** Letterbox padding colour; grey is neutral for a model trained on natural images. */
    private static final Scalar PAD = new Scalar(114, 114, 114);

    public PoseEstimator(Path modelPath) throws Exception {
        byte[] model = readModel(modelPath);
        try (var options = new OrtSession.SessionOptions()) {
            // One inference thread: the pipeline already runs capture, inference and UI
            // concurrently, and letting ORT spawn its own pool underneath just causes
            // them to fight for cores on a laptop.
            options.setIntraOpNumThreads(1);
            this.session = environment.createSession(model, options);
        }

        this.inputName = session.getInputNames().iterator().next();
        var info = (TensorInfo) session.getInputInfo().get(inputName).getInfo();
        this.inputType = info.type;
        long[] shape = info.getShape();
        // Square input; fall back to MoveNet Lightning's 192 if the axis is dynamic.
        this.side = (int) (shape.length >= 3 && shape[1] > 0 ? shape[1] : 192);
    }

    /**
     * Reads the model through a memory-mapped segment in a confined {@link Arena}.
     *
     * <p>The FFM API gives a single mapping the OS can page in lazily and unmap
     * deterministically when the arena closes, rather than a 9 MB heap copy that lingers
     * until the next GC. The bytes are handed to ONNX Runtime and the mapping is released
     * immediately afterwards.
     */
    private static byte[] readModel(Path modelPath) throws IOException {
        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(modelPath, StandardOpenOption.READ)) {
            MemorySegment mapped =
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            return mapped.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /** The model's square input size, exposed for diagnostics. */
    public int inputSide() {
        return side;
    }

    public Pose estimate(Mat bgrFrame, long sequence, long nanos) throws Exception {
        int sourceWidth = bgrFrame.width();
        int sourceHeight = bgrFrame.height();

        // --- letterbox ------------------------------------------------------
        // A plain resize to a square stretches a 4:3 frame, and a stretched body has
        // wrong joint angles: a 90-degree knee bend can read as 100. Scale uniformly and
        // pad instead, then undo the transform on the way out.
        double scale = Math.min((double) side / sourceWidth, (double) side / sourceHeight);
        int scaledWidth = (int) Math.round(sourceWidth * scale);
        int scaledHeight = (int) Math.round(sourceHeight * scale);
        int padX = (side - scaledWidth) / 2;
        int padY = (side - scaledHeight) / 2;

        Mat rgb = new Mat();
        Imgproc.cvtColor(bgrFrame, rgb, Imgproc.COLOR_BGR2RGB);

        Mat scaled = new Mat();
        Imgproc.resize(rgb, scaled, new Size(scaledWidth, scaledHeight));

        Mat canvas = new Mat(side, side, rgb.type(), PAD);
        scaled.copyTo(canvas.submat(padY, padY + scaledHeight, padX, padX + scaledWidth));

        byte[] pixels = new byte[(int) (canvas.total() * canvas.channels())];
        canvas.get(0, 0, pixels);

        rgb.release();
        scaled.release();
        canvas.release();

        // --- inference ------------------------------------------------------
        try (OnnxTensor tensor = buildTensor(pixels, new long[]{1, side, side, 3});
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {

            float[][][][] output = (float[][][][]) result.get(0).getValue();
            var points = new ArrayList<Keypoint>(Joint.values().length);

            for (Joint joint : Joint.values()) {
                float[] triple = output[0][0][joint.ordinal()];
                // MoveNet emits (y, x, score) — y first. Swapping these rotates the
                // whole skeleton 90 degrees, which is the classic bug here.
                double normalisedY = triple[0];
                double normalisedX = triple[1];
                double confidence = triple[2];

                // Undo the letterbox: model space -> padded canvas -> source pixels.
                double x = (normalisedX * side - padX) / scale;
                double y = (normalisedY * side - padY) / scale;

                points.add(new Keypoint(joint, x, y, confidence));
            }
            return Pose.of(sequence, nanos, points);
        }
    }

    /** Builds the input tensor in whatever dtype this particular export declared. */
    private OnnxTensor buildTensor(byte[] pixels, long[] shape) throws Exception {
        return switch (inputType) {
            case INT32 -> {
                IntBuffer buffer = IntBuffer.allocate(pixels.length);
                for (byte pixel : pixels) buffer.put(pixel & 0xFF);
                buffer.rewind();
                yield OnnxTensor.createTensor(environment, buffer, shape);
            }
            case UINT8 -> OnnxTensor.createTensor(
                    environment, ByteBuffer.wrap(pixels), shape, OnnxJavaType.UINT8);
            case FLOAT -> {
                FloatBuffer buffer = FloatBuffer.allocate(pixels.length);
                for (byte pixel : pixels) buffer.put((pixel & 0xFF) / 255.0f);
                buffer.rewind();
                yield OnnxTensor.createTensor(environment, buffer, shape);
            }
            default -> throw new IllegalStateException("Unsupported model input type: " + inputType);
        };
    }

    @Override
    public void close() throws Exception {
        session.close();
    }

    /** Loads the OpenCV natives once, with a clear message if it fails. */
    public static void loadNatives() {
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Could not load the OpenCV native library: " + t.getMessage(), t);
        }
    }

    public static String openCvVersion() {
        return Core.VERSION;
    }
}
