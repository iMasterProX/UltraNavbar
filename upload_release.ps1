param(
    [string]$ApkPath,
    [string]$Tag
)

$ErrorActionPreference = "Stop"

$repo = "iMasterProX/UltraNavbar"
$root = $PSScriptRoot

function Resolve-ApkPath {
    param([string]$Path)

    if (-not $Path) {
        $apks = Get-ChildItem -Path $root -Filter *.apk -File
        if ($apks.Count -eq 0) {
            throw "No APK found in $root. Place the APK in the repo root or pass -ApkPath."
        }
        if ($apks.Count -gt 1) {
            $names = $apks.Name -join ", "
            throw "Multiple APKs found in ${root}: $names. Pass -ApkPath to pick one."
        }
        return $apks[0].FullName
    }

    return (Resolve-Path -Path $Path -ErrorAction Stop).Path
}

$apk = Resolve-ApkPath -Path $ApkPath

if (-not (Test-Path -LiteralPath $apk)) {
    throw "APK not found: $apk"
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI (gh) is required. Install it and run 'gh auth login'."
}

if (-not $Tag) {
    $Tag = gh release view --repo $repo --json tagName --jq .tagName 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $Tag) {
        throw "Failed to resolve latest release tag. Provide -Tag <tag>."
    }
}

gh release view $Tag --repo $repo 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Release $Tag not found. Create it first (gh release create $Tag --repo $repo --title $Tag --generate-notes)."
}

Write-Host "Uploading $apk to $repo release $Tag..."
gh release upload $Tag $apk --repo $repo --clobber
if ($LASTEXITCODE -ne 0) {
    throw "Upload failed."
}
Write-Host "Done."
