# Phase 1 go/no-go spike — results

**Verdict: GO.** Both risks cleared. Run on 2026-08-03, Windows 11, Temurin JDK 26.0.1.

The plan (`../hackster-java26/PLAN-IDEA1-FORMCOACH.md`) gated this project on two
unknowns, timeboxed to 90 minutes, with instructions to fall back to a no-ML design if
either failed. Neither did.

## Spike A — webcam capture from Java ✅

| | |
|---|---|
| Library | `org.openpnp:opencv:4.9.0-0` (ships prebuilt natives via Maven — nothing to install) |
| Working backend | **`CAP_DSHOW` (DirectShow)** — the first one tried |
| Frame | 640×480, 3 channels |
| Evidence | wrote `spike-frame.png`; pixel variance 3900 confirms real content, not a blank frame |

**Notes for later:**
- `CAP_DSHOW` worked immediately. `Videoio.CAP_ANY` (the default most tutorials use) was
  never needed, but the spike tries all three backends in order and reports which worked.
- `VideoCapture.get(CAP_PROP_FPS)` reported **0.0** — this backend does not expose frame
  rate. Measure FPS by timing frames instead of trusting that property.
- The first read after opening often returns an empty frame while the camera warms up.
  `SpikeCamera` retries up to 10 times at 150 ms; the real capture loop must do the same.
- The test frame had a **pixel mean of 17.7/255 — very dark**. Pose confidence depends
  heavily on lighting; the calibration guide must say so plainly.

## Spike B — pose estimation ✅

| | |
|---|---|
| Runtime | `com.microsoft.onnxruntime:onnxruntime:1.22.0` |
| Model | MoveNet SinglePose Lightning, from `Xenova/movenet-singlepose-lightning` on Hugging Face — **no account or API key**, ~9 MB |
| Input | `'input'` shape `[1, 192, 192, 3]`, dtype **INT32** |
| Output | `'output_0'` shape `[1, 1, 17, 3]`, dtype FLOAT |

The keypoint order is MoveNet's standard 17, and each triple is **(y, x, score)** —
**y first**, normalised 0–1. Getting that pair backwards renders the skeleton rotated 90°
and is the single most common bug in this pipeline.

### Result on a real captured frame

7 of 17 keypoints above 0.3 confidence. **This is a pass, not a partial failure** — read
the geometry:

```
  left_eye        y=0.638 x=0.533  conf=0.45
  right_eye       y=0.624 x=0.479  conf=0.45
  left_ear        y=0.669 x=0.571  conf=0.36
  right_ear       y=0.667 x=0.437  conf=0.33
  left_shoulder   y=0.793 x=0.645  conf=0.31
  right_shoulder  y=0.804 x=0.364  conf=0.33
  ...
  left_ankle      y=0.974 x=0.524  conf=0.06  (low)
```

The eyes sit symmetrically either side of centre, the ears sit outside them, and the
shoulders sit below and wider. That is a correct upper-body detection. The legs score low
because a laptop webcam at desk height cannot see them — the frame contained a seated
person in a dark room, not a squatting one.

**What this proves:** model loading, dtype handling, preprocessing, inference and output
decoding all work in Java. What it does not yet prove is accuracy on a full-body squat,
which needs a proper camera setup (side-on, ~2 m back, whole body in frame, lit).

## Consequences for the build

1. **Tier-2 fallback is not needed.** The no-ML motion-energy design in the plan's §8 stays
   on the shelf. Build the real thing.
2. **`--enable-native-access=ALL-UNNAMED` is required.** JDK 26 warns that
   `System::load` is a restricted method and says it *"will be blocked in a future
   release"*. Add the flag to every run script now rather than discovering it later.
3. **Preprocessing must letterbox, not stretch.** The spike used a plain resize from
   640×480 to 192×192, which distorts aspect ratio and therefore skews joint angles. Fine
   for proving the pipeline; not acceptable for measuring knee angle. Fix in Phase 3.
4. **Confidence gating is mandatory.** A keypoint at 0.06 is noise; feeding it into an
   angle calculation produces phantom form faults.

## Reproducing

```powershell
.\scripts\fetch-model.ps1
mvn -q compile dependency:build-classpath "-Dmdep.outputFile=cp.txt"
$cp = "target\classes;" + (Get-Content cp.txt -Raw)
java --enable-preview --enable-native-access=ALL-UNNAMED -cp $cp dev.formwild.spike.SpikeCamera
java --enable-preview --enable-native-access=ALL-UNNAMED -cp $cp dev.formwild.spike.SpikePose
```
