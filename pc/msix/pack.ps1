# Builds Controlled for PC's MSIX: release exe + manifest + assets -> pc\target\ControlledForPC-<version>.msix.
# Usage: pc\msix\pack.ps1 0.1.0   (the Store upload needs no signature; Microsoft signs it.)
# For a local test install, sign it with a test certificate whose subject equals the manifest's Publisher (see
# docs/plans/pc-wifi.md, step 4).
param([Parameter(Mandatory)] [string]$Version)
$ErrorActionPreference = 'Stop'
$pc = Split-Path $PSScriptRoot
$sdk = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\makeappx.exe" | Sort-Object FullName | Select-Object -Last 1
if (-not $sdk) { throw "makeappx.exe not found: install the Windows SDK (winget install Microsoft.WindowsSDK.10.0.26100)" }
Push-Location $pc
cargo build --release
if ($LASTEXITCODE) { throw "cargo build failed" }
Pop-Location
$stage = Join-Path $pc 'target\msix'
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $stage | Out-Null
Copy-Item (Join-Path $pc 'target\release\controlled-pc.exe') $stage
# The GNU toolchain links WebView2 dynamically: its loader ships next to the exe.
Get-ChildItem (Join-Path $pc 'target\release\build\webview2-com-sys-*\out\x64\WebView2Loader.dll') | Select-Object -First 1 | Copy-Item -Destination $stage
Copy-Item (Join-Path $PSScriptRoot 'Assets') $stage -Recurse
(Get-Content (Join-Path $PSScriptRoot 'AppxManifest.xml') -Raw) -replace 'Version="[0-9.]+"', "Version=""$Version.0""" |
  Set-Content (Join-Path $stage 'AppxManifest.xml') -Encoding utf8
$out = Join-Path $pc "target\ControlledForPC-$Version.msix"
& $sdk.FullName pack /o /d $stage /p $out
if ($LASTEXITCODE) { throw "makeappx failed" }
"Built $out"
