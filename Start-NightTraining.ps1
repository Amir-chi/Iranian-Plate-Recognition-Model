param(
    [int]$Epochs = 60,
    [int]$Batch = 8,
    [int]$Workers = 0
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$pythonPath = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$dataPath = Join-Path $PSScriptRoot 'runs\datasets\ir-lpr\ready\data.yaml'
$realPath = Join-Path $PSScriptRoot 'runs\datasets\ir-lpr\ready\real.yaml'
$runPath = Join-Path $PSScriptRoot ('runs\night\' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
if (-not (Test-Path -LiteralPath $dataPath)) {
    throw 'Prepare the dataset first: .\.venv\Scripts\python.exe prepare_ir_lpr.py --download'
}

& $pythonPath train.py --data $dataPath --real-data $realPath --epochs $Epochs --batch $Batch --workers $Workers --project $runPath --name chars37
if ($LASTEXITCODE -ne 0) {
    throw "Training exited with code $LASTEXITCODE. Check the output above and resume from weights\last.pt if available."
}
Write-Host "Finished. Before/after comparison: $runPath\comparison.json"
