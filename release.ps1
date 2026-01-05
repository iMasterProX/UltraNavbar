$ErrorActionPreference = "Stop"
$repo = "iMasterProX/UltraNavbar"
$root = $PSScriptRoot

# GitHub CLI 확인
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "GitHub CLI (gh)가 설치되어 있지 않습니다." -ForegroundColor Red
    Write-Host "https://cli.github.com/ 에서 설치 후 'gh auth login'을 실행하세요."
    exit 1
}

# APK 파일 찾기
$apks = Get-ChildItem -Path $root -Filter *.apk -File
if ($apks.Count -eq 0) {
    Write-Host "APK 파일을 찾을 수 없습니다." -ForegroundColor Red
    Write-Host "UltraNavbar 폴더에 APK 파일을 넣어주세요."
    exit 1
}

if ($apks.Count -gt 1) {
    Write-Host "여러 APK 파일이 발견되었습니다:" -ForegroundColor Yellow
    for ($i = 0; $i -lt $apks.Count; $i++) {
        Write-Host "  [$($i + 1)] $($apks[$i].Name)"
    }
    $choice = Read-Host "업로드할 APK 번호를 선택하세요 (1-$($apks.Count))"
    $apk = $apks[[int]$choice - 1]
} else {
    $apk = $apks[0]
}

Write-Host ""
Write-Host "선택된 APK: $($apk.Name)" -ForegroundColor Cyan
Write-Host ""

# 버전 입력
$version = Read-Host "릴리즈 버전을 입력하세요 (예: v1.0.0)"
if (-not $version) {
    Write-Host "버전을 입력해야 합니다." -ForegroundColor Red
    exit 1
}

# v 접두사 자동 추가
if (-not $version.StartsWith("v")) {
    $version = "v$version"
}

Write-Host ""
Write-Host "릴리즈 정보:" -ForegroundColor Green
Write-Host "  버전: $version"
Write-Host "  APK: $($apk.Name) -> UltraNavBar.apk"
Write-Host "  저장소: $repo"
Write-Host ""

$confirm = Read-Host "릴리즈를 생성하고 업로드하시겠습니까? (y/n)"
if ($confirm -ne "y" -and $confirm -ne "Y") {
    Write-Host "취소되었습니다."
    exit 0
}

# 임시 파일명으로 복사 (UltraNavBar.apk)
$tempApk = Join-Path $root "UltraNavBar.apk"
if ($apk.FullName -ne $tempApk) {
    Copy-Item -Path $apk.FullName -Destination $tempApk -Force
}

try {
    # 릴리즈 존재 여부 확인
    $releaseExists = $false
    gh release view $version --repo $repo 2>$null
    if ($LASTEXITCODE -eq 0) {
        $releaseExists = $true
        Write-Host "기존 릴리즈 $version 에 업로드합니다..." -ForegroundColor Yellow
    } else {
        Write-Host "새 릴리즈 $version 을 생성합니다..." -ForegroundColor Green
        gh release create $version --repo $repo --title $version --generate-notes
        if ($LASTEXITCODE -ne 0) {
            throw "릴리즈 생성 실패"
        }
    }

    # APK 업로드
    Write-Host "APK 업로드 중..." -ForegroundColor Cyan
    gh release upload $version $tempApk --repo $repo --clobber
    if ($LASTEXITCODE -ne 0) {
        throw "업로드 실패"
    }

    Write-Host ""
    Write-Host "완료!" -ForegroundColor Green
    Write-Host "https://github.com/$repo/releases/tag/$version"
}
finally {
    # 임시 파일 정리 (원본과 다른 경우에만)
    if ($apk.FullName -ne $tempApk -and (Test-Path $tempApk)) {
        Remove-Item $tempApk -Force
    }
}
