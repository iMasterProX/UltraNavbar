# v0.2.6 작업내역 / Changelog

## 🇰🇷 한국어

### 1. Play Store 심사 대응: 권한 최소화
- `QUERY_ALL_PACKAGES` 완전 삭제 → `<queries>` 블록의 intent 기반 쿼리로 대체
- `FOREGROUND_SERVICE_SPECIAL_USE` 삭제 → `FOREGROUND_SERVICE`만 유지
- `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`는 배경화면 프리뷰 기능에 필수이므로 유지하되, 런타임 권한 요청 강화
  - `READ_EXTERNAL_STORAGE`에 `maxSdkVersion="32"` 적용 (Android 12 이하만)
  - `READ_MEDIA_IMAGES`는 Android 13+에서만 사용
- `android:resizeableActivity="false"` 삭제 (Android 16 deprecated 대응)

### 2. 배경화면 프리뷰 권한 복구 및 강화
- `WallpaperPreviewActivity.kt`: 런타임 권한 확인/요청 로직 추가 (권한 없이 진입 시 즉시 요청)
- `SetupActivity.kt`: 초기 설정 마법사에 배경화면 권한 단계 추가 (전체 스텝 6→7)
- `AppSettingsFragment.kt` / `fragment_app_settings.xml`: 앱 설정 권한 체크 목록에 배경화면 접근 항목 복원
- `privacy.html`: 배경화면 접근 권한(5.11절) 사용사유 상세 기술 (KR/EN)

### 3. 앱 목록 조회 방식 변경
- `AppListActivity.kt`, `ShortcutHelper.kt`: `getInstalledApplications()` → `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` 변경

### 4. 앱 아이콘 교체
- `pacman.png`를 각 밀도별(mdpi~xxxhdpi)로 리사이즈하여 `ic_launcher.png`, `ic_launcher_round.png` 생성
- adaptive icon용 `ic_launcher_foreground.png` 생성 (안전 영역 66/108 비율 적용)
- 배경 색상 `#FF0000`(빨강)으로 통일
- 기존 `.webp` 래스터 아이콘 및 벡터 XML 삭제

### 5. 배경 이미지 방향 불일치 수정
- `BackgroundManager.kt`의 `getCurrentBitmap()`에서 비트맵 선택 전 `syncOrientationWithSystem()` 호출 추가
- `getActualOrientation()`에서 AccessibilityService 비시각 컨텍스트 호환을 위해 `windowManager.defaultDisplay` 유지 (`context.display` 사용 시 크래시 발생하여 되돌림)

### 6. 최근 앱 목록에서 현재 런처 제외
- `WindowAnalyzer.loadLauncherPackages()`: `queryIntentActivities`로 `CATEGORY_HOME`에 응답하는 모든 앱을 감지하도록 개선
- 기존에는 기본 런처 1개만 감지하여 서드파티 런처(Nova Launcher 등)가 최근 앱 목록에 표시되는 문제 해결

### 7. 블루투스 OFF 시 화면 방향 고정 해제
- `NavBarAccessibilityService.kt`: `BluetoothAdapter.ACTION_STATE_CHANGED` 브로드캐스트 수신 추가
- 기기에서 블루투스를 직접 끌 때(`STATE_OFF` / `STATE_TURNING_OFF`) orientation lock 즉시 해제
- 기존에는 `ACL_DISCONNECTED`만 감지하여 블루투스 OFF 시 방향 고정이 유지되는 버그 존재

### 8. ADB 권한 설명 수정
- `pen_settings_permission_note` (KR/EN): "앱 재설치 후에도 유지됩니다" → "앱 업데이트 시에는 유지되지만, 앱을 삭제 후 재설치하면 다시 부여해야 합니다"로 수정

### 9. 시스템 예약 단축키 수정
- `ReservedShortcuts.kt`: `Search + ←(DPAD_LEFT)` → `Search + Enter`로 수정 (실제 LG UltraTab 홈 단축키는 Enter)

### 10. 펜 버튼 동작 유형 선택 순서 변경
- `PenButtonConfigActivity.kt`: "자동 터치 (UI 요소 기반)"이 "자동 터치 (좌표 기반)"보다 위에 오도록 순서 교체

### 11. 개인정보 처리방침
- `privacy.html` 신규 작성 및 앱 설정에 연결
- 전체 권한별 사용사유 상세 기술 (KR/EN 양국어)
- `QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE_SPECIAL_USE`, 불필요 저장소 권한 설명 삭제 및 항목 재정렬

### 12. 버전 업데이트
- `versionName` 0.2.5 → 0.2.6, `versionCode` 11 → 13

---

## 🇺🇸 English

### 1. Play Store Review Compliance: Permission Minimization
- Removed `QUERY_ALL_PACKAGES` entirely → replaced with intent-based `<queries>` block
- Removed `FOREGROUND_SERVICE_SPECIAL_USE` → kept only `FOREGROUND_SERVICE`
- Retained `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` (required for wallpaper preview), but strengthened runtime permission handling
  - Applied `maxSdkVersion="32"` to `READ_EXTERNAL_STORAGE` (Android 12 and below only)
  - `READ_MEDIA_IMAGES` used on Android 13+ only
- Removed `android:resizeableActivity="false"` (deprecated in Android 16)

### 2. Wallpaper Preview Permission Restoration & Enhancement
- `WallpaperPreviewActivity.kt`: Added runtime permission check/request (prompts immediately if permission not granted)
- `SetupActivity.kt`: Added wallpaper permission step to initial setup wizard (total steps 6→7)
- `AppSettingsFragment.kt` / `fragment_app_settings.xml`: Restored wallpaper access permission row in app settings
- `privacy.html`: Detailed wallpaper access permission description added (§5.11, KR/EN)

### 3. App List Query Method Change
- `AppListActivity.kt`, `ShortcutHelper.kt`: Changed `getInstalledApplications()` → `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`

### 4. App Icon Replacement
- Resized `pacman.png` to all density buckets (mdpi–xxxhdpi) as `ic_launcher.png` and `ic_launcher_round.png`
- Generated `ic_launcher_foreground.png` for adaptive icons (66/108 safe zone ratio)
- Unified background color to `#FF0000` (red)
- Removed old `.webp` raster icons and vector XML drawables

### 5. Background Image Orientation Mismatch Fix
- Added `syncOrientationWithSystem()` call in `BackgroundManager.getCurrentBitmap()` before bitmap selection
- Kept `windowManager.defaultDisplay` in `getActualOrientation()` for AccessibilityService non-visual context compatibility (`context.display` caused crash, reverted)

### 6. Filter Current Launcher from Recent Apps
- `WindowAnalyzer.loadLauncherPackages()`: Improved to detect all apps responding to `CATEGORY_HOME` via `queryIntentActivities`
- Previously only detected 1 default launcher, causing third-party launchers (e.g., Nova Launcher) to appear in recent apps bar

### 7. Fix Orientation Lock Not Releasing on Bluetooth Off
- `NavBarAccessibilityService.kt`: Added `BluetoothAdapter.ACTION_STATE_CHANGED` broadcast listener
- Immediately removes orientation lock when Bluetooth is turned off (`STATE_OFF` / `STATE_TURNING_OFF`)
- Previously only listened for `ACL_DISCONNECTED`, so toggling Bluetooth off kept orientation lock active

### 8. ADB Permission Description Fix
- `pen_settings_permission_note` (KR/EN): Changed "will persist across app reinstalls" → "persists across app updates, but must be re-granted if the app is uninstalled and reinstalled"

### 9. System Reserved Shortcut Fix
- `ReservedShortcuts.kt`: Fixed `Search + ←(DPAD_LEFT)` → `Search + Enter` (actual LG UltraTab home shortcut uses Enter key)

### 10. Pen Button Action Type Order Change
- `PenButtonConfigActivity.kt`: Swapped order so "Auto Touch (UI Element)" appears above "Auto Touch (Coordinate)"

### 11. Privacy Policy
- Created `privacy.html` and linked from app settings
- Detailed per-permission usage descriptions (KR/EN bilingual)
- Removed descriptions for `QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE_SPECIAL_USE`, and unused storage permissions; renumbered sections

### 12. Version Bump
- `versionName` 0.2.5 → 0.2.6, `versionCode` 11 → 13
