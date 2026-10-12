[CmdletBinding()]
param(
    [string]$NdkRoot = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358",
    [string]$SourceRoot = "$PSScriptRoot\..\build\hev-socks5-tunnel-src",
    [string]$OutputRoot = "$PSScriptRoot\..\app\src\main\jniLibs",
    [string[]]$Abis = @("armeabi-v7a", "arm64-v8a", "x86", "x86_64"),
    [ValidateRange(21, 100)]
    [int]$MinSdkVersion = 24,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

# The source is pinned so the JNI class binding and ABI outputs are reproducible.
$HevRepository = "https://github.com/heiher/hev-socks5-tunnel.git"
$HevRevision = "9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a"
$CustomCFlags = "-O3 -DPKGNAME=com/g2ray/client/vpn -DCLSNAME=HevTunController"
$SupportedAbis = @("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

$requestedAbis = @($Abis | Sort-Object -Unique)
$expectedAbis = @($SupportedAbis | Sort-Object)
if (Compare-Object -ReferenceObject $expectedAbis -DifferenceObject $requestedAbis) {
    throw "Production HEV builds must rebuild all supported ABIs together: $($SupportedAbis -join ', ')."
}

$sourcePath = [System.IO.Path]::GetFullPath($SourceRoot)
$outputPath = [System.IO.Path]::GetFullPath($OutputRoot)
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\build"))
$buildRootPrefix = $buildRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $sourcePath.StartsWith($buildRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "HEV SourceRoot must stay inside the project's disposable build directory: '$buildRoot'."
}
$ndkBuild = Join-Path $NdkRoot "ndk-build.cmd"
$llvmReadElf = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
$appPlatform = "android-$MinSdkVersion"

if (-not (Test-Path -LiteralPath $ndkBuild -PathType Leaf)) {
    throw "Android NDK was not found at '$NdkRoot'. Install NDK 28.2.13676358 or pass -NdkRoot."
}
if (-not (Test-Path -LiteralPath $llvmReadElf -PathType Leaf)) {
    throw "llvm-readelf was not found at '$llvmReadElf'. The NDK installation is incomplete."
}

if ($Clean -and (Test-Path -LiteralPath $sourcePath)) {
    Remove-Item -LiteralPath $sourcePath -Recurse -Force
}

if (-not (Test-Path -LiteralPath (Join-Path $sourcePath ".git") -PathType Container)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $sourcePath) -Force | Out-Null
    git clone --recurse-submodules $HevRepository $sourcePath
}

git -C $sourcePath fetch --depth 1 origin $HevRevision
if ($LASTEXITCODE -ne 0) { throw "Could not fetch pinned HEV revision $HevRevision." }
git -C $sourcePath checkout --detach --force $HevRevision
if ($LASTEXITCODE -ne 0) { throw "Could not check out pinned HEV revision $HevRevision." }
git -C $sourcePath clean -ffdx
if ($LASTEXITCODE -ne 0) { throw "Could not clean the disposable HEV build checkout." }
git -C $sourcePath submodule sync --recursive
if ($LASTEXITCODE -ne 0) { throw "Could not synchronize HEV submodules." }
git -C $sourcePath submodule update --init --recursive --force
if ($LASTEXITCODE -ne 0) { throw "Could not restore pinned HEV submodules." }
git -C $sourcePath submodule foreach --recursive git reset --hard
if ($LASTEXITCODE -ne 0) { throw "Could not reset pinned HEV submodules." }
git -C $sourcePath submodule foreach --recursive git clean -ffdx
if ($LASTEXITCODE -ne 0) { throw "Could not clean pinned HEV submodules." }

# Git for Windows may materialize POSIX symlinks from HEV submodules as tiny files
# containing a relative target path. Convert only that exact form inside this build
# copy so ndk-build reads the intended source without touching the app code.
Get-ChildItem -LiteralPath $sourcePath -Recurse -File | ForEach-Object {
    if ($_.Length -gt 512) {
        return
    }
    $content = [System.IO.File]::ReadAllText($_.FullName).Trim()
    if ($content -notmatch '^\.\.[\\/][^\r\n]+$') {
        return
    }
    $target = [System.IO.Path]::GetFullPath((Join-Path $_.DirectoryName $content))
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        Copy-Item -LiteralPath $target -Destination $_.FullName -Force
    }
}

$buildOut = Join-Path $sourcePath "out-g2ray"
$buildLibs = Join-Path $sourcePath "libs-g2ray"
Remove-Item -LiteralPath $buildOut -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $buildLibs -Recurse -Force -ErrorAction SilentlyContinue

& $ndkBuild `
    "NDK_PROJECT_PATH=$sourcePath" `
    "APP_BUILD_SCRIPT=$(Join-Path $sourcePath 'Android.mk')" `
    "NDK_APPLICATION_MK=$(Join-Path $sourcePath 'Application.mk')" `
    "NDK_OUT=$buildOut" `
    "NDK_LIBS_OUT=$buildLibs" `
    "APP_ABI=$($Abis -join ' ')" `
    "APP_PLATFORM=$appPlatform" `
    "APP_CFLAGS=$CustomCFlags" `
    "-j$([Math]::Max(1, [Environment]::ProcessorCount))"

if ($LASTEXITCODE -ne 0) {
    throw "HEV native build failed with exit code $LASTEXITCODE."
}

foreach ($abi in $Abis) {
    $library = Join-Path $buildLibs "$abi\libhev-socks5-tunnel.so"
    if (-not (Test-Path -LiteralPath $library -PathType Leaf)) {
        throw "Expected HEV library was not produced for $abi."
    }
    $elfNotes = (& $llvmReadElf --notes $library 2>&1) -join "`n"
    if ($elfNotes -notmatch 'description data:\s+([0-9a-fA-F]{2}) 00 00 00') {
        throw "Could not read the Android API note from the HEV library for $abi."
    }
    $builtMinSdk = [Convert]::ToInt32($Matches[1], 16)
    if ($builtMinSdk -ne $MinSdkVersion) {
        throw "HEV library for $abi targets Android API $builtMinSdk instead of $MinSdkVersion."
    }
    $destination = Join-Path $outputPath "$abi\libhev-socks5-tunnel.so"
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath $library -Destination $destination -Force
}

$ndkProperties = Join-Path $NdkRoot "source.properties"
$ndkRevision = if (Test-Path -LiteralPath $ndkProperties -PathType Leaf) {
    ((Get-Content -LiteralPath $ndkProperties | Select-String '^Pkg\.Revision\s*=\s*(.+)$').Matches.Groups[1].Value).Trim()
} else {
    Split-Path -Leaf $NdkRoot
}
$metadata = [ordered]@{
    "hev.ref" = $HevRevision
    "hev.ndk" = $ndkRevision
    "hev.minSdk" = $MinSdkVersion
    "hev.jni" = "boolean-runtime-v2"
}
foreach ($abi in $SupportedAbis) {
    $library = Join-Path $outputPath "$abi\libhev-socks5-tunnel.so"
    $metadata["hev.$abi.sha256"] = (Get-FileHash -LiteralPath $library -Algorithm SHA256).Hash
}
$metadataLines = $metadata.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
[System.IO.File]::WriteAllLines(
    (Join-Path $PSScriptRoot "..\app\libs\hev.properties"),
    $metadataLines,
    [System.Text.Encoding]::ASCII
)

Write-Host "Built HEV TUN JNI libraries for Android API $MinSdkVersion ($($Abis -join ', ')) and copied them to $outputPath"
