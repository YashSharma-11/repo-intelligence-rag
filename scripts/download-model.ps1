# Downloads the local ONNX embedding model (all-MiniLM-L6-v2, ~90 MB) on Windows.
$ErrorActionPreference = 'Stop'
$destDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'src\main\resources\onnx'
$modelUrl = 'https://media.githubusercontent.com/media/spring-projects/spring-ai/main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx'
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
Write-Host "Downloading embedding model to $destDir\model.onnx ..."
$ProgressPreference = 'SilentlyContinue'
Invoke-WebRequest -Uri $modelUrl -OutFile (Join-Path $destDir 'model.onnx') -TimeoutSec 600
$size = (Get-Item (Join-Path $destDir 'model.onnx')).Length
if ($size -lt 1000000) { throw "Downloaded file is only $size bytes (expected ~90 MB)." }
Write-Host "Done. model.onnx is $size bytes."
