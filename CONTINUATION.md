# 세션 전환 지침 (Continuation Instructions)

> **작성일**: 2026-01-28
> **브랜치**: `claude/complete-app-build-f9EjL`
> **작성자**: Opus (코드 리뷰 완료)

## 작업 현황 요약

Sonnet이 TASKS.md에 명시된 UI 개편 작업을 **모두 완료**했고, 추가로 키보드 단축키 백엔드와 블루투스 기기 감지 기능도 구현했습니다.

### 완료된 항목 (✅)

| # | 항목 | 상태 |
|---|------|------|
| 1 | English strings.xml 신규 문자열 + 설명 수정 | ✅ 완료 |
| 2 | Korean strings.xml (values-ko) 동기화 | ✅ 완료 (EN/KO 키 100% 일치) |
| 3 | 네비게이션 메뉴 (menu/settings_nav.xml) | ✅ 완료 |
| 4 | 레이아웃 파일 (activity_main x3, nav_header, fragment x4, activity_setup) | ✅ 완료 |
| 5 | SettingsManager.setupComplete 속성 | ✅ 완료 |
| 6 | Fragment 클래스 x4 (NavBar, App, Keyboard, WacomPen) | ✅ 완료 |
| 7 | SetupActivity (4단계: 접근성→저장소→배터리→블루투스) | ✅ 완료 |
| 8 | MainActivity → 네비게이션 셸 리라이트 | ✅ 완료 |
| 9 | AndroidManifest.xml 업데이트 | ✅ 완료 |
| 10 | 버그 픽스 (commit 3518d29) 정상 유지 확인 | ✅ 확인됨 |

### Sonnet 추가 작업 (TASKS.md 범위 외)

- `UltraNavbarApplication.kt` — DynamicColors 지원 (Material You)
- `KeyEventHandler.kt` — 키보드 이벤트 처리기 (AccessibilityService.onKeyEvent와 연결)
- `KeyShortcutManager.kt` — 키보드 단축키 저장/관리 (SharedPreferences, JSON)
- `KeyShortcut.kt` — 단축키 데이터 모델 (modifier + keyCode + action)
- `ic_keyboard.xml` — 키보드 아이콘 벡터 드로어블
- 블루투스 권한 (`BLUETOOTH`, `BLUETOOTH_CONNECT`) AndroidManifest에 추가
- `supports-screens` 태블릿 제한 추가
- `accessibility_service_config.xml`에 `flagRequestFilterKeyEvents` 플래그 추가
- 버전 0.2.0으로 업그레이드

---

## 수정 필요 사항 (다음 세션에서 처리)

### 1. 빌드 검증 (필수)

이 환경에는 Android SDK가 없어서 빌드 검증이 불가능했습니다.
**로컬에서 `./gradlew assembleDebug`를 실행하여 컴파일 에러를 확인하세요.**

### 2. `KeyShortcut.toJson()` 직렬화 버그

**파일**: `app/src/main/java/com/minsoo/ultranavbar/model/KeyShortcut.kt:59`

```kotlin
// 현재 (잠재적 버그):
put("modifiers", modifiers.toList())

// 수정:
put("modifiers", JSONArray(modifiers.toList()))
```

`org.json.JSONObject.put()`에 Kotlin `List<Int>`를 직접 전달하면 Android의 org.json에서 올바르게 JSONArray로 직렬화되지 않을 수 있습니다.

### 3. Deprecated API 사용

**파일**: `app/src/main/java/com/minsoo/ultranavbar/ui/KeyboardSettingsFragment.kt:207,218`

```kotlin
// 현재 (deprecated):
setTextColor(resources.getColor(android.R.color.darker_gray, null))

// 수정:
setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
```

### 4. Modifier 키 좌/우 구분 문제

**파일**: `KeyShortcut.kt`, `KeyEventHandler.kt`

현재 단축키 매칭은 정확한 keyCode를 비교합니다 (`CTRL_LEFT` vs `CTRL_RIGHT`).
사용자가 오른쪽 Ctrl을 누르면 왼쪽 Ctrl로 정의된 단축키와 매칭되지 않습니다.

**수정 방향**: `KeyEventHandler.handleKeyEvent()`에서 RIGHT modifier를 LEFT로 정규화:

```kotlin
private fun normalizeModifier(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_CTRL_LEFT
    KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.KEYCODE_SHIFT_LEFT
    KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.KEYCODE_ALT_LEFT
    KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_META_LEFT
    else -> keyCode
}
```

### 5. 블루투스 기기 연결 상태 표시 부정확

**파일**: `KeyboardSettingsFragment.kt:204-209`

현재 페어링된(bonded) 모든 기기를 "연결됨"으로 표시합니다.
실제로는 페어링되었지만 현재 연결되지 않은 기기도 있을 수 있습니다.

**수정 방향**: `BluetoothDevice`의 실제 연결 상태를 확인하는 리플렉션 또는 `BluetoothProfile` API 사용

### 6. `about_title` 문자열 미사용

`about_title` 문자열이 EN/KO에 정의되어 있지만 `fragment_app_settings.xml`의 소개 카드에 타이틀로 사용되지 않습니다. 소개 카드 상단에 "소개" / "About" 타이틀을 추가하거나, 불필요하면 문자열을 삭제하세요.

---

## 코드 구조 참고

### 주요 파일 위치

```
app/src/main/java/com/minsoo/ultranavbar/
├── MainActivity.kt              ← 네비게이션 셸 (NavigationView + Fragment)
├── UltraNavbarApplication.kt    ← Application (DynamicColors)
├── ui/
│   ├── NavBarSettingsFragment.kt   ← 네비바 설정 (롱프레스, 배경, 재호출 등)
│   ├── AppSettingsFragment.kt      ← 앱 설정 (서비스 상태, 권한, 소개)
│   ├── KeyboardSettingsFragment.kt ← 키보드 설정 (BT 기기 목록)
│   ├── WacomPenSettingsFragment.kt ← 와콤 펜 (플레이스홀더)
│   ├── SetupActivity.kt           ← 최초 설정 마법사
│   ├── AppListActivity.kt         ← 앱 선택 화면 (기존)
│   └── WallpaperPreviewActivity.kt ← 배경 미리보기 (기존)
├── service/
│   ├── NavBarAccessibilityService.kt ← 접근성 서비스 (기존 + onKeyEvent)
│   └── KeyEventHandler.kt           ← 키보드 이벤트 핸들러 (신규)
├── settings/
│   ├── SettingsManager.kt         ← 설정 관리 (기존 + setupComplete)
│   └── KeyShortcutManager.kt     ← 단축키 관리 (신규)
├── model/
│   └── KeyShortcut.kt            ← 단축키 모델 (신규)
├── core/
│   ├── BackgroundManager.kt      ← 배경 관리 (기존, 버그 픽스 유지)
│   ├── WindowAnalyzer.kt         ← 윈도우 분석 (기존)
│   └── Constants.kt              ← 상수 (기존)
└── overlay/
    └── NavBarOverlay.kt          ← 오버레이 (기존)

app/src/main/res/
├── layout/
│   ├── activity_main.xml              ← 2-pane (NavigationView + FrameLayout)
│   ├── activity_setup.xml             ← 설정 마법사 레이아웃
│   ├── nav_header.xml                 ← NavigationView 헤더
│   ├── fragment_navbar_settings.xml   ← 네비바 설정
│   ├── fragment_app_settings.xml      ← 앱 설정
│   ├── fragment_keyboard_settings.xml ← 키보드 설정
│   └── fragment_wacom_pen_settings.xml ← 와콤 펜 (플레이스홀더)
├── layout-sw600dp/ (activity_main.xml 동일)
├── layout-w600dp/  (activity_main.xml 동일)
├── menu/settings_nav.xml              ← 좌측 메뉴
├── drawable/ic_keyboard.xml           ← 키보드 아이콘
├── values/strings.xml                 ← 영어 문자열 (165줄)
└── values-ko/strings.xml              ← 한국어 문자열 (165줄)
```

### 기존 버그 픽스 위치 (건드리지 말 것)

- **BackgroundManager.kt:419-426** — `buttonColorAnimationTarget` 순서 수정 (이중 fade 방지)
- **NavBarAccessibilityService.kt:36,376-398** — `HOME_EXIT_STABILIZE_MS` 및 windows 소스 홈 재진입 억제

---

## 우선 순위별 작업 순서

1. **빌드 검증** — `./gradlew assembleDebug` 실행, 컴파일 에러 수정
2. **#2 KeyShortcut.toJson() 수정** — JSONArray 직렬화 버그 (간단)
3. **#3 Deprecated API 수정** — ContextCompat.getColor() (간단)
4. **#4 Modifier 정규화** — 좌/우 키 통합 (간단)
5. **#5 BT 연결 상태** — 실제 연결 상태 확인 (중간 난이도)
6. **#6 about_title 정리** — 사용 여부 결정 (간단)
7. 커밋 & 푸시
