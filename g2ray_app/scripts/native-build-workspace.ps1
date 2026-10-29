$ErrorActionPreference = "Stop"

function Assert-NativeSourceCheckout {
    param([string]$Directory, [string]$ExpectedOrigin, [switch]$NewClone)

    $full = [IO.Path]::GetFullPath($Directory)
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Native build paths must not traverse a symlink or junction."
            }
        }
        $cursor = Split-Path $cursor -Parent
    }
    if (-not (Test-Path -LiteralPath $full)) { return }
    if (-not (Test-Path -LiteralPath $full -PathType Container)) {
        throw "Native source path must be a dedicated build directory."
    }
    if (@(Get-ChildItem -LiteralPath $full -Force).Count -eq 0) { return }

    $gitDir = Join-Path $full ".git"
    if (-not (Test-Path -LiteralPath $gitDir -PathType Container) -or
        ((Get-Item -LiteralPath $gitDir -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Native builds require standalone disposable clones, not worktrees or personal source folders."
    }
    $top = & git -C $full rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath(($top -join "").Trim()) -ne $full) {
        throw "Native build directory is not its own Git checkout."
    }
    $origin = & git -C $full remote get-url origin 2>$null
    if ($LASTEXITCODE -ne 0 -or ($origin -join "").Trim() -notin @($ExpectedOrigin, ($ExpectedOrigin -replace '\.git$', ''))) {
        throw "Native checkout origin does not match the pinned upstream repository. No source files were reset."
    }
    if ($NewClone) { return }

    $marker = Join-Path $gitDir "g2ray-native-build-owner.json"
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
        throw "Refusing to reset an unowned native checkout. Choose a fresh dedicated WorkDir; existing files were preserved."
    }
    if (((Get-Item -LiteralPath $marker -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        (Get-Item -LiteralPath $marker).Length -gt 4096) {
        throw "Invalid native build ownership marker."
    }
    try { $owner = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json }
    catch { throw "Invalid native build ownership marker." }
    if ($owner.schema -ne 1 -or $owner.kind -ne "g2ray-native-build" -or
        $owner.path -ne $full -or $owner.origin -ne $ExpectedOrigin) {
        throw "Native build ownership does not match this checkout. Existing files were preserved."
    }
}

function Register-NativeSourceCheckout {
    param([string]$Directory, [string]$ExpectedOrigin)

    Assert-NativeSourceCheckout -Directory $Directory -ExpectedOrigin $ExpectedOrigin -NewClone
    $full = [IO.Path]::GetFullPath($Directory)
    $gitDir = Join-Path $full ".git"
    if (-not (Test-Path -LiteralPath $gitDir -PathType Container)) {
        throw "Cannot own a native build checkout before a successful clone."
    }
    $owner = @{ schema = 1; kind = "g2ray-native-build"; path = $full; origin = $ExpectedOrigin } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText((Join-Path $gitDir "g2ray-native-build-owner.json"), $owner, [Text.UTF8Encoding]::new($false))
}
