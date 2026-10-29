$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "native-build-workspace.ps1")

$temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$root = Join-Path $temp ("g2ray-native-workspace-test-" + [guid]::NewGuid().ToString("N"))
$origin = "https://github.com/XTLS/libXray.git"
$checks = 0

function Check-Rejected {
    param([scriptblock]$Action)
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "Unsafe native checkout was accepted." }
    $script:checks++
}

try {
    New-Item -ItemType Directory -Path $root | Out-Null
    Assert-NativeSourceCheckout (Join-Path $root "new") $origin
    $checks++
    $empty = Join-Path $root "empty"
    New-Item -ItemType Directory -Path $empty | Out-Null
    Assert-NativeSourceCheckout $empty $origin
    $checks++
    $personal = Join-Path $root "personal"
    New-Item -ItemType Directory -Path $personal | Out-Null
    $sentinel = Join-Path $personal "do-not-delete.txt"
    [IO.File]::WriteAllText($sentinel, "owner edits")
    Check-Rejected { Assert-NativeSourceCheckout $personal $origin }
    git -C $personal init --quiet
    if ($LASTEXITCODE -ne 0) { throw "Could not initialize the fixture." }
    git -C $personal remote add origin $origin
    if ($LASTEXITCODE -ne 0) { throw "Could not configure the fixture origin." }
    Check-Rejected { Assert-NativeSourceCheckout $personal $origin }
    if ([IO.File]::ReadAllText($sentinel) -ne "owner edits") { throw "Ownership rejection modified personal files." }
    $checks++
    Register-NativeSourceCheckout $personal $origin
    Assert-NativeSourceCheckout $personal $origin
    $checks++
    git -C $personal remote set-url origin https://github.com/example/personal.git
    if ($LASTEXITCODE -ne 0) { throw "Could not change the fixture origin." }
    Check-Rejected { Assert-NativeSourceCheckout $personal $origin }
    git -C $personal remote set-url origin $origin
    if ($LASTEXITCODE -ne 0) { throw "Could not restore the fixture origin." }
    [IO.File]::WriteAllText((Join-Path $personal ".git/g2ray-native-build-owner.json"), '{"schema":1,"kind":"wrong-owner"}')
    Check-Rejected { Assert-NativeSourceCheckout $personal $origin }
    [IO.File]::WriteAllText((Join-Path $personal ".git/g2ray-native-build-owner.json"), 'not json')
    Check-Rejected { Assert-NativeSourceCheckout $personal $origin }
    $worktree = Join-Path $root "worktree"
    New-Item -ItemType Directory -Path $worktree | Out-Null
    [IO.File]::WriteAllText((Join-Path $worktree ".git"), "gitdir: ../personal/.git")
    Check-Rejected { Assert-NativeSourceCheckout $worktree $origin }
    $link = Join-Path $root "linked-checkout"
    $linkType = if ([IO.Path]::DirectorySeparatorChar -eq '\') { "Junction" } else { "SymbolicLink" }
    New-Item -ItemType $linkType -Path $link -Target $personal | Out-Null
    Check-Rejected { Assert-NativeSourceCheckout $link $origin }
    Write-Output "PASS: $checks native workspace safety checks; personal files preserved."
} finally {
    $fullRoot = [IO.Path]::GetFullPath($root)
    if (-not $fullRoot.StartsWith($temp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $fullRoot -Leaf) -notlike "g2ray-native-workspace-test-*") {
        throw "Refusing to clean an unexpected fixture directory."
    }
    if (Test-Path -LiteralPath $fullRoot) { Remove-Item -LiteralPath $fullRoot -Recurse -Force }
}
