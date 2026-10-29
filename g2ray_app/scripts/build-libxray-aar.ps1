param(
    [string]$OutputDir = "$PSScriptRoot\..\app\libs",
    [string]$WorkDir = "$PSScriptRoot\..\build\libxray-src",
    [string]$NdkVersion = "28.2.13676358",
    [string]$LibXrayRef = "80263da83e96b2972455b0a94b13ee1a10e51391",
    [string]$GoVersion = "go1.26.10",
    [string]$GomobileVersion = "v0.0.0-20260611195102-4dd8f1dbf5d2",
    [string]$XrayCoreRef = "5ca6f4b7d4dc20a881d4330e498892697627ec0c"
)

$ErrorActionPreference = "Stop"
$AndroidControllerPatch = Join-Path $PSScriptRoot "patches\libxray-android-controller.patch"
$XrayObserverPatch = Join-Path $PSScriptRoot "patches\xray-observer-detached-snapshot.patch"
$LibXrayMeasurePatch = Join-Path $PSScriptRoot "patches\libxray-measure-status.patch"
$LibXrayMeasureTests = Join-Path $PSScriptRoot "fixtures\libxray_measure_status_test.go"

function Assert-PinnedMobileTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ToolName
    )

    $tool = Get-Command $ToolName -ErrorAction Stop
    $buildInfo = (& go version -m $tool.Source) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect $ToolName build information."
    }
    if ($buildInfo -notmatch "mod\s+golang.org/x/mobile\s+$([regex]::Escape($GomobileVersion))(?:\s|$)") {
        throw "$ToolName does not match the pinned x/mobile revision $GomobileVersion."
    }
}

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}
if (-not $env:JAVA_HOME) {
    $candidate = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $candidate) {
        $env:JAVA_HOME = $candidate
    }
}

$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME "ndk\$NdkVersion"
$env:GOTOOLCHAIN = $GoVersion
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:USERPROFILE\go\bin;$env:Path"

if (-not (Test-Path $env:ANDROID_NDK_HOME)) {
    throw "Android NDK $NdkVersion is missing at $env:ANDROID_NDK_HOME. Install it with sdkmanager `"ndk;$NdkVersion`" first."
}
if (-not (Test-Path -LiteralPath $AndroidControllerPatch -PathType Leaf)) {
    throw "The required Android controller patch is missing at '$AndroidControllerPatch'."
}
if (-not (Test-Path -LiteralPath $XrayObserverPatch -PathType Leaf)) {
    throw "The required Xray observer patch is missing at '$XrayObserverPatch'."
}
if (-not (Test-Path -LiteralPath $LibXrayMeasurePatch -PathType Leaf)) {
    throw "The required libXray measurement patch is missing at '$LibXrayMeasurePatch'."
}
if (-not (Test-Path -LiteralPath $LibXrayMeasureTests -PathType Leaf)) {
    throw "The required libXray measurement fixtures are missing at '$LibXrayMeasureTests'."
}
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw "Go $GoVersion is required but the go command was not found."
}
$actualGoVersion = (go env GOVERSION).Trim()
if ($LASTEXITCODE -ne 0 -or $actualGoVersion -ne $GoVersion) {
    throw "libXray must be built with $GoVersion; found '$actualGoVersion'."
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $WorkDir -Parent) | Out-Null
$xrayCoreDir = Join-Path (Split-Path $WorkDir -Parent) "Xray-core"

if (-not (Test-Path $WorkDir)) {
    git clone https://github.com/XTLS/libXray.git $WorkDir
} else {
    git -C $WorkDir fetch --tags --force origin
}

git -C $WorkDir -c core.autocrlf=false -c core.eol=lf checkout --force $LibXrayRef
if ($LASTEXITCODE -ne 0) {
    throw "Could not check out pinned libXray ref $LibXrayRef."
}
# Also refresh an unchanged checkout that a previous Windows build wrote as CRLF.
git -C $WorkDir -c core.autocrlf=false -c core.eol=lf checkout-index --force --all
if ($LASTEXITCODE -ne 0) {
    throw "Could not normalize the disposable libXray source checkout."
}
git -C $WorkDir clean -ffdx
if ($LASTEXITCODE -ne 0) {
    throw "Could not clean the disposable libXray build checkout."
}
$resolvedRef = (git -C $WorkDir rev-parse HEAD).Trim()
if ($LibXrayRef -eq "main") {
    Write-Warning "LibXrayRef is 'main'. For production, rerun with -LibXrayRef <tested-commit-sha> and record that SHA."
}
Write-Host "Building libXray ref $LibXrayRef ($resolvedRef)"

if (-not (Test-Path $xrayCoreDir)) {
    git clone https://github.com/XTLS/Xray-core.git $xrayCoreDir
} else {
    git -C $xrayCoreDir fetch --tags --force origin
}
git -C $xrayCoreDir -c core.autocrlf=false -c core.eol=lf checkout --force $XrayCoreRef
if ($LASTEXITCODE -ne 0) {
    throw "Could not check out pinned Xray-core ref $XrayCoreRef."
}
git -C $xrayCoreDir -c core.autocrlf=false -c core.eol=lf checkout-index --force --all
if ($LASTEXITCODE -ne 0) {
    throw "Could not normalize the disposable Xray-core source checkout."
}
git -C $xrayCoreDir clean -ffdx
if ($LASTEXITCODE -ne 0) {
    throw "Could not clean the disposable Xray-core build checkout."
}
$resolvedXrayCoreRef = (git -C $xrayCoreDir rev-parse HEAD).Trim()
git -C $xrayCoreDir apply --check $XrayObserverPatch
if ($LASTEXITCODE -ne 0) {
    throw "The pinned Xray-core source no longer accepts the observer snapshot patch."
}
git -C $xrayCoreDir apply $XrayObserverPatch
if ($LASTEXITCODE -ne 0) {
    throw "Could not apply the Xray observer snapshot patch."
}
$xrayObserverPatchHash = (Get-FileHash $XrayObserverPatch -Algorithm SHA256).Hash
Write-Host "Applied Xray observer snapshot patch $xrayObserverPatchHash at $resolvedXrayCoreRef"

git -C $WorkDir apply --check $LibXrayMeasurePatch
if ($LASTEXITCODE -ne 0) {
    throw "The pinned libXray source no longer accepts the measurement-status patch."
}
git -C $WorkDir apply $LibXrayMeasurePatch
if ($LASTEXITCODE -ne 0) {
    throw "Could not apply the libXray measurement-status patch."
}
$libXrayMeasurePatchHash = (Get-FileHash $LibXrayMeasurePatch -Algorithm SHA256).Hash
Write-Host "Applied libXray measurement-status patch $libXrayMeasurePatchHash at $resolvedRef"
Copy-Item -LiteralPath $LibXrayMeasureTests -Destination (Join-Path $WorkDir "nodep\g2ray_measure_status_test.go")
Push-Location $WorkDir
try {
    go test ./nodep -run TestG2RayMeasure -count=1
    if ($LASTEXITCODE -ne 0) {
        throw "The patched native measurement failed its HTTP status, redirect, timeout or cancellation fixtures."
    }
} finally {
    Pop-Location
}

git -C $WorkDir apply --check $AndroidControllerPatch
if ($LASTEXITCODE -ne 0) {
    throw "The pinned libXray source no longer accepts the Android controller patch."
}
git -C $WorkDir apply $AndroidControllerPatch
if ($LASTEXITCODE -ne 0) {
    throw "Could not apply the Android controller patch."
}
$androidWrapper = Join-Path $WorkDir "android_wrapper.go"
if (-not (Test-Path -LiteralPath $androidWrapper -PathType Leaf)) {
    throw "The pinned libXray source is missing android_wrapper.go after patching."
}
$androidWrapperContent = Get-Content -LiteralPath $androidWrapper -Raw
if ($androidWrapperContent -notmatch 'RegisterDialerController\(func\(fd uintptr\) bool' -or
    $androidWrapperContent -notmatch 'RegisterListenerController\(func\(fd uintptr\) bool' -or
    $androidWrapperContent -notmatch 'return controller\.ProtectFd\(int\(fd\)\)') {
    throw "The Android controller patch did not update the Go wrapper callback signatures."
}
$androidControllerPatchHash = (Get-FileHash $AndroidControllerPatch -Algorithm SHA256).Hash
Write-Host "Applied idempotent Android socket-controller patch $androidControllerPatchHash"

$buildPy = Join-Path $WorkDir "build\app\build.py"
$content = Get-Content $buildPy -Raw
$preparePattern = '(?s)    def prepare_gomobile\(self\):.*?    def prepare_static_lib\(self\):'
$prepareMatches = [regex]::Matches($content, $preparePattern)
if ($prepareMatches.Count -ne 1) {
    throw "Expected exactly one libXray prepare_gomobile function, found $($prepareMatches.Count)."
}
$replacement = @'
    def prepare_gomobile(self):
        # The production wrapper installs and attests gomobile and gobind.
        # Do not let upstream replace either pinned tool with @latest.
        return

'@
$content = [regex]::Replace(
    $content,
    $preparePattern,
    $replacement + "    def prepare_static_lib(self):"
)
[System.IO.File]::WriteAllText($buildPy, $content, [System.Text.UTF8Encoding]::new($false))
if ((Get-Content $buildPy -Raw) -match 'golang\.org/x/mobile/cmd/(?:gomobile|gobind)@latest') {
    throw "The patched libXray Android build still contains a mutable x/mobile @latest dependency."
}
Write-Host "Patched libXray build.py to preserve the pinned mobile toolchain."

$androidBuildPy = Join-Path $WorkDir "build\app\android.py"
$androidBuildContent = Get-Content $androidBuildPy -Raw
$androidLdflagsPattern = '(?m)^\s*"-ldflags=-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384",\s*$'
$androidLdflagsMatches = [regex]::Matches($androidBuildContent, $androidLdflagsPattern)
if ($androidLdflagsMatches.Count -ne 1) {
    throw "Expected exactly one libXray Android linker-flag entry, found $($androidLdflagsMatches.Count)."
}
$deterministicAndroidFlags = @'
                "-trimpath",
                "-ldflags=-buildid= -checklinkname=0 -extldflags=-Wl,--build-id=none,-z,max-page-size=16384",
'@
$androidBuildContent = [regex]::Replace(
    $androidBuildContent,
    $androidLdflagsPattern,
    $deterministicAndroidFlags
)
[System.IO.File]::WriteAllText(
    $androidBuildPy,
    $androidBuildContent,
    [System.Text.UTF8Encoding]::new($false)
)
Write-Host "Patched libXray Android flags for path-independent, build-ID-free native outputs."

go install "golang.org/x/mobile/cmd/gomobile@$GomobileVersion"
if ($LASTEXITCODE -ne 0) {
    throw "Could not install pinned gomobile $GomobileVersion."
}
go install "golang.org/x/mobile/cmd/gobind@$GomobileVersion"
if ($LASTEXITCODE -ne 0) {
    throw "Could not install pinned gobind $GomobileVersion."
}

Assert-PinnedMobileTool -ToolName "gomobile"
Assert-PinnedMobileTool -ToolName "gobind"

Push-Location $WorkDir
try {
    go get -tool "golang.org/x/mobile/cmd/gobind@$GomobileVersion"
    if ($LASTEXITCODE -ne 0) {
        throw "Could not pin the x/mobile tool dependency in libXray's module."
    }
    go mod tidy
    if ($LASTEXITCODE -ne 0) {
        throw "Could not tidy libXray's pinned module graph."
    }
    $resolvedMobileVersion = (go list -m -f '{{.Version}}' golang.org/x/mobile).Trim()
    if ($LASTEXITCODE -ne 0 -or $resolvedMobileVersion -ne $GomobileVersion) {
        throw "libXray resolved x/mobile '$resolvedMobileVersion' instead of '$GomobileVersion'."
    }
} finally {
    Pop-Location
}

python (Join-Path $WorkDir "build\main.py") android local
if ($LASTEXITCODE -ne 0) {
    throw "libXray Android build failed with exit code $LASTEXITCODE."
}

$postBuildGoVersion = (go env GOVERSION).Trim()
if ($LASTEXITCODE -ne 0 -or $postBuildGoVersion -ne $GoVersion) {
    throw "The Go toolchain changed during the build; expected $GoVersion, found '$postBuildGoVersion'."
}
Assert-PinnedMobileTool -ToolName "gomobile"
Assert-PinnedMobileTool -ToolName "gobind"

$builtAar = Join-Path $WorkDir "libXray.aar"
if (-not (Test-Path $builtAar)) {
    throw "libXray build finished without producing $builtAar"
}

Copy-Item $builtAar (Join-Path $OutputDir "libXray.aar") -Force
$aarHash = (Get-FileHash (Join-Path $OutputDir "libXray.aar") -Algorithm SHA256).Hash
@"
libxray.ref=$resolvedRef
libxray.sha256=$aarHash
libxray.go=$postBuildGoVersion
libxray.gomobile=$GomobileVersion
libxray.gobind=$GomobileVersion
libxray.android.controller.patch.sha256=$androidControllerPatchHash
libxray.xray.ref=$resolvedXrayCoreRef
libxray.xray.observer.patch.sha256=$xrayObserverPatchHash
libxray.measure.patch.sha256=$libXrayMeasurePatchHash
"@ | Set-Content (Join-Path $OutputDir "libXray.properties") -Encoding ascii
Write-Host "Copied libXray.aar to $OutputDir"
Write-Host "libXray commit: $resolvedRef"
Write-Host "libXray SHA-256: $aarHash"
