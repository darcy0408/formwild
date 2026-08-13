# Downloads the pose model. Run once before first use.
#
# MoveNet SinglePose Lightning: 17 body keypoints, ~9 MB, fast enough for real time on
# a CPU. Weights are not committed to git - they are large, and they are not ours.
#
# No account or API key required.

$ErrorActionPreference = 'Stop'

$url   = 'https://huggingface.co/Xenova/movenet-singlepose-lightning/resolve/main/onnx/model.onnx'
$dir   = Join-Path $PSScriptRoot '..\models'
$out   = Join-Path $dir 'movenet-lightning.onnx'

if (-not (Test-Path $dir)) { New-Item -ItemType Directory $dir | Out-Null }

if (Test-Path $out) {
    Write-Output "Model already present: $out"
    exit 0
}

Write-Output "Downloading MoveNet SinglePose Lightning (~9 MB)..."
Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing

$sizeMb = (Get-Item $out).Length / 1MB
Write-Output ("Saved {0} ({1:N1} MB)" -f $out, $sizeMb)
Write-Output ""
Write-Output "Verify with:  mvn clean package; java --enable-native-access=ALL-UNNAMED -jar target\formwild.jar --diagnose 5"
