param(
    [Parameter(Mandatory = $true)][string]$RunPath,
    [ValidateSet('training', 'telegram')][string]$Mode = 'training',
    [switch]$Resume,
    [int]$TrainingPid = 0,
    [ValidateRange(1, 1024)][int]$Batch = 8,
    [ValidateRange(0, 64)][int]$Workers = 0
)

$ErrorActionPreference = 'Stop'
$workspacePath = $PSScriptRoot
$RunPath = [System.IO.Path]::GetFullPath($RunPath)
if ($Mode -eq 'training') {
    $existing = Get-CimInstance Win32_Process -Filter "name='python.exe'" | Where-Object {
        $_.CommandLine -like '*background_run.py*--mode training*' -or
        $_.CommandLine -match 'Iranian-Plate-Processing-Model[\\/]train\.py'
    }
    if ($existing) { throw 'A training worker is already running.' }
    if ($Resume -and -not (Test-Path -LiteralPath (Join-Path $RunPath 'chars37\weights\last.pt'))) {
        throw 'Cannot resume: last.pt was not found.'
    }
} elseif ($TrainingPid -le 0 -or -not $env:TELEGRAM_BOT_TOKEN -or -not $env:TELEGRAM_CHAT_ID) {
    throw 'Telegram mode requires the training PID and Telegram credentials in the environment.'
}
New-Item -ItemType Directory -Path $RunPath -Force | Out-Null

$environmentKeys = @('SystemRoot','WINDIR','PATH','PATHEXT','TEMP','TMP','USERPROFILE',
    'APPDATA','LOCALAPPDATA','HOMEDRIVE','HOMEPATH','USERNAME','USERDOMAIN','COMSPEC',
    'ProgramData','ProgramFiles','ProgramFiles(x86)')
$workerEnvironment = @($environmentKeys | ForEach-Object {
    $value = [Environment]::GetEnvironmentVariable($_)
    if ($null -ne $value) { $_ + '=' + $value }
}) + @('PYTHONUNBUFFERED=1','PYTHONIOENCODING=utf-8')
if ($Mode -eq 'telegram') {
    $workerEnvironment += @(('TELEGRAM_BOT_TOKEN=' + $env:TELEGRAM_BOT_TOKEN),
                            ('TELEGRAM_CHAT_ID=' + $env:TELEGRAM_CHAT_ID))
}
$pythonPath = Join-Path $workspacePath '.venv\Scripts\python.exe'
$workerPath = Join-Path $workspacePath 'background_run.py'
$commandLine = '"' + $pythonPath + '" -u "' + $workerPath + '" --mode ' + $Mode + ' --run "' + $RunPath + '"'
if ($Resume) { $commandLine += ' --resume' }
if ($Mode -eq 'training') { $commandLine += ' --batch ' + $Batch + ' --workers ' + $Workers }
if ($Mode -eq 'telegram') { $commandLine += ' --pid ' + $TrainingPid }

# Independent of the calling app's process lifetime; no console window.
$startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{
    ShowWindow = [uint16]0
    CreateFlags = [uint32]0x09000000
    EnvironmentVariables = [string[]]$workerEnvironment
}
$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = $commandLine
    CurrentDirectory = $workspacePath
    ProcessStartupInformation = $startup
}
if ($created.ReturnValue -ne 0) { throw ('Independent worker creation failed: ' + $created.ReturnValue) }
$metadataPath = Join-Path $RunPath 'processes.json'
$metadata = if (Test-Path -LiteralPath $metadataPath) {
    Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
} else { [pscustomobject]@{ run = $RunPath } }
$metadata | Add-Member -NotePropertyName ($Mode + '_pid') -NotePropertyValue $created.ProcessId -Force
$metadata | Add-Member -NotePropertyName ($Mode + '_launched_at') -NotePropertyValue (Get-Date).ToString('o') -Force
$metadata | Add-Member -NotePropertyName launcher -NotePropertyValue 'Independent Windows WMI worker' -Force
if ($Mode -eq 'training') {
    $metadata | Add-Member -NotePropertyName batch -NotePropertyValue $Batch -Force
    $metadata | Add-Member -NotePropertyName workers -NotePropertyValue $Workers -Force
    $metadata | Add-Member -NotePropertyName image_cache -NotePropertyValue $false -Force
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding utf8
[pscustomobject]@{ mode = $Mode; pid = $created.ProcessId; run = $RunPath; resumed = [bool]$Resume } | ConvertTo-Json
