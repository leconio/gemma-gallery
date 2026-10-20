param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$Apk,
    [int]$AdbPort=5037,
    [ValidateSet('CPU','GPU')][string]$Backend='CPU',
    [switch]$ProvisionModel
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$spec = Get-Content -LiteralPath (Join-Path $projectRoot 'model-manifest.json') -Raw | ConvertFrom-Json
$modelPath = Join-Path (Join-Path $projectRoot 'models') $spec.filename
if ($ProvisionModel) {
    if (-not (Test-Path -LiteralPath $modelPath)) { throw 'Run python scripts/download_model.py first, or omit -ProvisionModel to download in the app.' }
    if ((Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $spec.sha256) { throw 'Local model checksum mismatch.' }
}

function Invoke-TargetAdb {
    param([string[]]$Arguments)
    $result = & adb -P $AdbPort -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}

$state = Invoke-TargetAdb -Arguments @('get-state')
if (($state -join '').Trim() -ne 'device') { throw 'The selected device is not online and authorized.' }
$deviceModel = ((Invoke-TargetAdb -Arguments @('shell','getprop','ro.product.model')) -join '').Trim()
$manufacturer = ((Invoke-TargetAdb -Arguments @('shell','getprop','ro.product.manufacturer')) -join '').Trim()
$abis = ((Invoke-TargetAdb -Arguments @('shell','getprop','ro.product.cpu.abilist')) -join '').Trim()
if ($abis -notmatch 'arm64-v8a') { throw 'This APK requires an arm64-v8a capable Android device.' }
Write-Output "Installing Gemma Pocket on $manufacturer $deviceModel ($Serial)"
$installation = Invoke-TargetAdb -Arguments @('install','-r',$apkPath)
if (($installation -join "`n") -notmatch '(?m)^Success\s*$') { throw ($installation -join "`n") }
$installation

if ($ProvisionModel) {
    & python (Join-Path $PSScriptRoot 'provision_model.py') --serial $Serial --adb-port $AdbPort
    if ($LASTEXITCODE -ne 0) { throw 'Private model provisioning failed.' }
}
Invoke-TargetAdb -Arguments @('shell','am','force-stop','com.spawnlau.gemmapocket') | Out-Null
$launchArgs = @('shell','am','start','-n','com.spawnlau.gemmapocket/.MainActivity','--es','backend',$Backend)
Invoke-TargetAdb -Arguments $launchArgs

$evidenceDirectory = Join-Path $projectRoot 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
@{
    serial=$Serial
    manufacturer=$manufacturer
    model=$deviceModel
    apk=$apkPath
    apkSha256=(Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    modelSha256=$spec.sha256
    modelProvisioned=[bool]$ProvisionModel
    backend=$Backend
    installationPassed=$true
    installedAt=(Get-Date).ToString('o')
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidenceDirectory 'installation.json') -Encoding utf8
Write-Output 'Installed and launched. Connect Photos in the app to grant access to system photos. Use the isolated benchmark activity for latency tests.'
