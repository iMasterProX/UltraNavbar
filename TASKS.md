# UltraNavbar UI 리팩토링 작업 지시서

> 이 파일은 소넷이 읽고 순서대로 실행해야 할 작업 목록입니다.
> 작업 완료 후 이 파일은 삭제해도 됩니다.

## 배경

현재 앱은 네비바 전용 설정 앱이지만, 향후 블루투스 키보드/와콤 펜 등 다양한 기기별 기능을 추가할 예정입니다.
이를 위해 설정 UI를 안드로이드 태블릿 가이드라인에 맞게 **좌측 네비게이션 + 우측 콘텐츠** 2단 구조로 개편합니다.

## 현재 코드 구조

- **MainActivity.kt** — 모든 설정이 한 Activity에 들어있음 (Fragment 미사용)
- **레이아웃 3벌** — `layout/activity_main.xml`, `layout-sw600dp/activity_main.xml`, `layout-w600dp/activity_main.xml`
- **태블릿 전용** — `DeviceProfile.isTablet()` 체크로 폰은 차단
- **viewBinding** — build.gradle에 활성화되어 있으나 사용 안 함 (전부 findViewById)
- **한국어 리소스** — `values-ko/strings.xml` 이미 생성됨 (이 커밋에 포함)

---

## 작업 1: 영어 strings.xml에 신규 문자열 추가 및 설명 수정

`app/src/main/res/values/strings.xml`에 아래 문자열들을 추가합니다.
(한국어 `values-ko/strings.xml`에는 이미 포함되어 있음)

```xml
<!-- Navigation menu -->
<string name="nav_menu_navbar">Navigation Bar</string>
<string name="nav_menu_keyboard">Keyboard</string>
<string name="nav_menu_wacom_pen">Wacom Pen</string>
<string name="nav_menu_app_settings">App Settings</string>

<!-- App settings screen -->
<string name="app_settings_permissions">Permission status</string>
<string name="app_settings_permissions_summary">Check the status of permissions required for the app.</string>
<string name="permission_accessibility">Accessibility service</string>
<string name="permission_accessibility_summary">Required for navigation buttons and window detection.</string>
<string name="permission_storage">Storage access</string>
<string name="permission_storage_summary">Required for wallpaper access to generate auto backgrounds.</string>
<string name="permission_battery">Battery optimization exemption</string>
<string name="permission_battery_summary">Prevents the service from being stopped in the background.</string>
<string name="permission_status_granted">Granted</string>
<string name="permission_status_not_granted">Not granted</string>
<string name="about_title">About</string>
<string name="about_version">Version</string>
<string name="about_developer">Developer</string>
<string name="about_developer_name">iMasterProX</string>
<string name="about_github">GitHub</string>
<string name="about_description">Custom navigation bar and extension features app for LG UltraTab devices.</string>

<!-- Placeholders -->
<string name="keyboard_settings_title">Bluetooth Keyboard Settings</string>
<string name="keyboard_settings_desc">Settings for LG UltraTab Bluetooth keyboard including device recognition and battery status will be available in a future update.</string>
<string name="wacom_pen_settings_title">Wacom Pen Settings</string>
<string name="wacom_pen_settings_desc">Settings for LG UltraTab Wacom pen including device configuration and custom features will be available in a future update.</string>
<string name="coming_soon">Coming in a future update</string>

<!-- Setup screen -->
<string name="setup_welcome_title">Welcome to LG UltraTab Extension</string>
<string name="setup_welcome_desc">A few permissions are needed to get started.</string>
<string name="setup_accessibility_title">Enable accessibility service</string>
<string name="setup_accessibility_desc">Accessibility service permission is required to display the custom navigation bar and detect window states.</string>
<string name="setup_storage_title">Storage access permission</string>
<string name="setup_storage_desc">Wallpaper access is required to auto-generate home screen backgrounds.</string>
<string name="setup_battery_title">Battery optimization exemption</string>
<string name="setup_battery_desc">Exclude the app from battery optimization so the service runs reliably in the background.</string>
<string name="setup_grant_permission">Grant permission</string>
<string name="setup_complete">Done</string>
<string name="setup_skip">Skip</string>
<string name="setup_next">Next</string>
```

또한 기존 문자열 2개를 수정합니다:

- `home_bg_note` → `"Tap Auto to preview your current wallpaper and capture the bottom 72px as the navigation bar background."`
- `wallpaper_preview_hint` → `"This is a preview of your current wallpaper. Tap Apply to capture and use it as a custom background."`

---

## 작업 2: 네비게이션 메뉴 리소스 생성

### 2-1. 메뉴 XML 생성: `app/src/main/res/menu/settings_nav.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">

    <group android:id="@+id/nav_group_features">
        <item
            android:id="@+id/nav_navbar"
            android:icon="@android:drawable/ic_menu_compass"
            android:title="@string/nav_menu_navbar" />
        <item
            android:id="@+id/nav_keyboard"
            android:icon="@android:drawable/ic_menu_edit"
            android:title="@string/nav_menu_keyboard" />
        <item
            android:id="@+id/nav_wacom_pen"
            android:icon="@android:drawable/ic_menu_crop"
            android:title="@string/nav_menu_wacom_pen" />
    </group>

    <group android:id="@+id/nav_group_system">
        <item
            android:id="@+id/nav_app_settings"
            android:icon="@android:drawable/ic_menu_preferences"
            android:title="@string/nav_menu_app_settings" />
    </group>

</menu>
```

> 아이콘은 일단 시스템 기본 아이콘 사용. 나중에 커스텀 아이콘으로 교체 가능.

---

## 작업 3: 레이아웃 파일 재구성

### 3-1. 새 activity_main.xml 작성 (모든 layout 변형 공통)

기존 `layout/activity_main.xml`, `layout-sw600dp/activity_main.xml`, `layout-w600dp/activity_main.xml` 3개를
**모두 동일한 내용으로** 교체합니다 (태블릿 전용 앱이므로 항상 2단 레이아웃).

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="horizontal"
    android:background="?android:colorBackground">

    <!-- 좌측 네비게이션 패널 -->
    <com.google.android.material.navigation.NavigationView
        android:id="@+id/navigationView"
        android:layout_width="280dp"
        android:layout_height="match_parent"
        app:menu="@menu/settings_nav"
        app:headerLayout="@layout/nav_header" />

    <!-- 구분선 -->
    <View
        android:layout_width="1dp"
        android:layout_height="match_parent"
        android:background="?android:attr/listDivider" />

    <!-- 우측 콘텐츠 영역 -->
    <FrameLayout
        android:id="@+id/contentFrame"
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_weight="1" />

</LinearLayout>
```

### 3-2. 네비게이션 헤더 생성: `app/src/main/res/layout/nav_header.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="20sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:text="@string/about_description"
        android:textSize="12sp"
        android:textColor="?android:textColorSecondary" />

</LinearLayout>
```

### 3-3. Fragment 레이아웃 파일 4개 생성

#### `app/src/main/res/layout/fragment_navbar_settings.xml`

기존 `activity_main.xml`의 **전체 내용**을 가져옵니다:
- 서비스 상태 카드 (statusIndicator, statusText, btnOpenAccessibility)
- 안정성 설정 카드 (btnBatteryOptimization) — **제거** (AppSettingsFragment로 이동)
- 온보딩 카드 (onboardingCard) — **제거** (SetupActivity로 이동)
- 롱프레스 동작 카드
- 비활성화 앱 카드
- 재호출 설정 카드 (switchHotspot, switchIgnoreStylus)
- 홈 화면 배경 카드 (switchHomeBg, 버튼 색상, 가로/세로/다크모드 배경 등)

단, **서비스 상태 카드, 안정성 설정 카드, 온보딩 카드**는 NavBarSettingsFragment에서 **제외**하고 AppSettingsFragment로 이동합니다.

결과적으로 NavBarSettingsFragment에 포함될 카드:
1. 롱프레스 동작 설정 카드
2. 비활성화 앱 설정 카드
3. 재호출 설정 카드 (핫스팟 + 스타일러스)
4. 홈 화면 배경 설정 카드 (다크 모드 배경 포함)

전체를 `ScrollView > LinearLayout` 으로 감쌉니다.

#### `app/src/main/res/layout/fragment_app_settings.xml`

`ScrollView > LinearLayout` 안에:
1. **서비스 상태 카드** (기존 것 그대로)
2. **권한 상태 카드** — 새로 만듦:
   - 제목: `@string/app_settings_permissions`
   - 설명: `@string/app_settings_permissions_summary`
   - 접근성 서비스 행: 제목(`@string/permission_accessibility`) + 상태 텍스트(id: `txtPermAccessibility`) + 버튼(id: `btnPermAccessibility`)
   - 저장소 접근 행: 제목(`@string/permission_storage`) + 상태 텍스트(id: `txtPermStorage`) + 버튼(id: `btnPermStorage`)
   - 배터리 최적화 행: 제목(`@string/permission_battery`) + 상태 텍스트(id: `txtPermBattery`) + 버튼(id: `btnPermBattery`)
3. **소개 카드**:
   - 앱 설명: `@string/about_description`
   - 버전: `@string/about_version` + 동적 버전 텍스트(id: `txtVersion`)
   - 개발자: `@string/about_developer` → `@string/about_developer_name`
   - GitHub: `@string/about_github` → 링크
   - 크레딧: `@string/credits_text`

#### `app/src/main/res/layout/fragment_keyboard_settings.xml`

플레이스홀더. `ScrollView > LinearLayout` 안에:
- MaterialCardView 하나:
  - 제목: `@string/keyboard_settings_title`
  - 설명: `@string/keyboard_settings_desc`
  - 부제: `@string/coming_soon` (textColor secondary)

#### `app/src/main/res/layout/fragment_wacom_pen_settings.xml`

플레이스홀더. 위와 동일한 구조:
- 제목: `@string/wacom_pen_settings_title`
- 설명: `@string/wacom_pen_settings_desc`
- 부제: `@string/coming_soon`

---

## 작업 4: Fragment 클래스 생성

패키지: `com.minsoo.ultranavbar.ui`

### 4-1. `NavBarSettingsFragment.kt`

- `Fragment()` 상속, `onCreateView`에서 `fragment_navbar_settings.xml` inflate
- **기존 MainActivity에서 다음 로직 이동:**
  - 롱프레스 설정 UI 바인딩 + 런처 (`longPressActionPickerLauncher`, `shortcutPickerLauncher`)
  - 비활성화 앱 관리 버튼
  - 재호출 핫스팟/스타일러스 스위치
  - 홈 배경 전체 (switchHomeBg, 버튼 색상 토글, 가로/세로 이미지 선택, 다크모드 배경)
  - `imagePickerLauncher`, `wallpaperPreviewLauncher`
  - `handleImageSelected()`, `updateBgImageStatus()`, `generateBackground()`
  - `notifySettingsChanged()`, `notifyBackgroundStyleChanged()` → Fragment에서 `requireContext().sendBroadcast()`
  - `updateLongPressActionUI()`, `loadSettings()`, `setupListeners()`
- `registerForActivityResult()`는 Fragment에서도 동일하게 사용 가능 (Fragment.registerForActivityResult)
- `onResume()`에서 `updateBgImageStatus()`, `updateLongPressActionUI()` 호출

### 4-2. `AppSettingsFragment.kt`

- `Fragment()` 상속
- **서비스 상태** 섹션:
  - `statusIndicator`, `statusText`, `btnOpenAccessibility`
  - `onboardingCard` (서비스 비활성화 시에만 표시)
  - `updateServiceStatus()` 로직 이동
- **권한 상태** 섹션:
  - 접근성 상태: `NavBarAccessibilityService.isRunning()` 확인
  - 저장소 상태: `ContextCompat.checkSelfPermission()` 확인
  - 배터리 최적화 상태: `PowerManager.isIgnoringBatteryOptimizations()` 확인
  - 각 항목에 상태 텍스트 (승인됨/미승인) + 설정 열기 버튼
  - `requestPermissionLauncher`는 이 Fragment에서 등록
  - `requestIgnoreBatteryOptimizations()` 로직 이동
- **소개** 섹션:
  - 버전: `BuildConfig.VERSION_NAME` 또는 `PackageInfo`에서 가져옴
  - 개발자, GitHub, 크레딧 표시
- `onResume()`에서 `updateServiceStatus()`, 권한 상태 갱신

### 4-3. `KeyboardSettingsFragment.kt`

- 단순 플레이스홀더. `onCreateView`에서 `fragment_keyboard_settings.xml` inflate만 수행.

### 4-4. `WacomPenSettingsFragment.kt`

- 단순 플레이스홀더. `onCreateView`에서 `fragment_wacom_pen_settings.xml` inflate만 수행.

---

## 작업 5: MainActivity 재작성

기존 로직 대부분을 Fragment로 이전한 후, MainActivity는 **네비게이션 셸**로 축소합니다.

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var navigationView: NavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeviceProfile.isTablet(this)) {
            Toast.makeText(this, R.string.tablet_only, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 최초 실행 시 SetupActivity로 이동
        val settings = SettingsManager.getInstance(this)
        if (!settings.setupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        navigationView = findViewById(R.id.navigationView)
        navigationView.setNavigationItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_navbar -> NavBarSettingsFragment()
                R.id.nav_keyboard -> KeyboardSettingsFragment()
                R.id.nav_wacom_pen -> WacomPenSettingsFragment()
                R.id.nav_app_settings -> AppSettingsFragment()
                else -> return@setNavigationItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.contentFrame, fragment)
                .commit()
            true
        }

        // 기본: 네비바 설정 표시
        if (savedInstanceState == null) {
            navigationView.setCheckedItem(R.id.nav_navbar)
            supportFragmentManager.beginTransaction()
                .replace(R.id.contentFrame, NavBarSettingsFragment())
                .commit()
        }
    }
}
```

- **중요**: 기존 `registerForActivityResult` 런처들은 모두 해당 Fragment로 이동
- `isAccessibilityServiceEnabled()` companion object은 유지하거나 AppSettingsFragment에서 직접 구현

---

## 작업 6: SetupActivity 생성

### 6-1. SettingsManager에 `setupComplete` 프로퍼티 추가

`app/src/main/java/com/minsoo/ultranavbar/settings/SettingsManager.kt`:

```kotlin
var setupComplete: Boolean
    get() = prefs.getBoolean("setup_complete", false)
    set(value) = prefs.edit().putBoolean("setup_complete", value).apply()
```

### 6-2. SetupActivity 클래스 생성

`app/src/main/java/com/minsoo/ultranavbar/ui/SetupActivity.kt`

- ViewPager2 또는 단순 단계별 UI (간단한 구현을 위해 **단계별 카드** 방식 권장)
- 화면 구성:
  - 상단: 환영 제목 (`@string/setup_welcome_title`)
  - 중앙: 현재 단계 설명 카드
  - 하단: 진행 버튼 (`@string/setup_next` / `@string/setup_complete`)
- 3단계:
  1. **접근성 서비스**: 설명 + "권한 허용" 버튼 → `Settings.ACTION_ACCESSIBILITY_SETTINGS` 열기
  2. **저장소 접근**: 설명 + "권한 허용" 버튼 → `requestPermissionLauncher` 실행
  3. **배터리 최적화**: 설명 + "권한 허용" 버튼 → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 실행
- 각 단계에서 권한 상태를 실시간 확인하여 승인됨 표시
- "건너뛰기" 버튼으로 어떤 단계든 건너뛸 수 있음
- 마지막 단계 후 `settings.setupComplete = true` 설정하고 `MainActivity`로 이동

### 6-3. 레이아웃: `app/src/main/res/layout/activity_setup.xml`

간단한 중앙 정렬 레이아웃:
```xml
<LinearLayout (vertical, center gravity)>
    <TextView (제목: setup_welcome_title) />
    <TextView (설명: setup_welcome_desc) />
    <MaterialCardView (현재 단계 카드)>
        <LinearLayout>
            <TextView id="setupStepTitle" />
            <TextView id="setupStepDesc" />
            <LinearLayout (horizontal)>
                <TextView id="setupStepStatus" />  <!-- 승인됨/미승인 -->
                <MaterialButton id="btnGrantPermission" />
            </LinearLayout>
        </LinearLayout>
    </MaterialCardView>
    <LinearLayout (horizontal, 하단 버튼)>
        <MaterialButton id="btnSkip" (text: setup_skip) />
        <MaterialButton id="btnNext" (text: setup_next) />
    </LinearLayout>
</LinearLayout>
```

### 6-4. AndroidManifest.xml 업데이트

```xml
<activity
    android:name=".ui.SetupActivity"
    android:exported="false"
    android:theme="@style/Theme.UltraNavbar" />
```

---

## 작업 7: 버그 수정 2건 (중요!)

이전 세션에서 분석은 완료했으나 코드 적용이 누락된 버그 2건.

### 7-1. 버튼 색상 애니메이션 타겟 순서 버그

**파일**: `app/src/main/java/com/minsoo/ultranavbar/core/BackgroundManager.kt`
**함수**: `updateButtonColor()`

**현재 코드 (버그)**:
```kotlin
fun updateButtonColor(targetColor: Int, animate: Boolean = true) {
    if (_currentButtonColor == targetColor && buttonColorAnimationTarget == targetColor) {
        return
    }

    buttonColorAnimationTarget = targetColor  // ← 체크 전에 설정됨

    if (!animate) {
        buttonColorAnimator?.cancel()
        _currentButtonColor = targetColor
        ...
        return
    }

    // 이 체크는 위에서 이미 타겟을 설정했으므로 항상 true → 새 애니메이션 시작 불가
    if (buttonColorAnimator?.isRunning == true && buttonColorAnimationTarget == targetColor) {
        return
    }

    buttonColorAnimator?.cancel()
    ...
}
```

**수정 방법**: `buttonColorAnimationTarget = targetColor`를 running 체크 **이후**로 이동:

```kotlin
fun updateButtonColor(targetColor: Int, animate: Boolean = true) {
    if (_currentButtonColor == targetColor && buttonColorAnimationTarget == targetColor) {
        return
    }

    if (!animate) {
        buttonColorAnimator?.cancel()
        buttonColorAnimationTarget = targetColor
        _currentButtonColor = targetColor
        listener.onButtonColorChanged(targetColor)
        Log.d(TAG, "Button color set immediately: ${getColorName(targetColor)}")
        return
    }

    // buttonColorAnimationTarget은 이 체크 이후에 업데이트해야
    // 방향 전환 시 (예: WHITE→BLACK 중 다시 WHITE) 새 애니메이션이 정상 시작됨
    if (buttonColorAnimator?.isRunning == true && buttonColorAnimationTarget == targetColor) {
        return
    }

    buttonColorAnimationTarget = targetColor
    buttonColorAnimator?.cancel()
    // ... 나머지 애니메이션 시작 코드 그대로
```

### 7-2. 홈 이탈 후 잘못된 홈 재진입 방지

**파일**: `app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt`

앱 실행 시 event 소스가 홈 이탈을 감지하지만, ~80ms 후 windows 소스가 런처를 최상위로 보고 잘못된 홈 재진입을 함.
이로 인해 배경이 3번 전환됨 (홈→앱→홈(잘못됨)→앱).

**수정 방법**:

1. companion object에 상수 추가:
```kotlin
private const val HOME_EXIT_STABILIZE_MS = 500L  // 홈 이탈 후 안정화 시간
```

2. 필드 추가:
```kotlin
private var lastHomeExitAt: Long = 0  // 홈 이탈 안정화용
```

3. `updateHomeScreenState()` 함수에 이탈 안정화 로직 추가:
```kotlin
private fun updateHomeScreenState(isHome: Boolean, source: String) {
    if (isOnHomeScreen == isHome) return

    val now = SystemClock.elapsedRealtime()

    // 홈 진입 안정화: 홈에 진입한 직후 false 이벤트 무시 (기존 코드)
    if (!isHome && isOnHomeScreen) {
        val elapsed = now - lastHomeEntryAt
        if (elapsed < HOME_ENTRY_STABILIZE_MS) {
            Log.d(TAG, "Home exit ignored (stabilizing, ${elapsed}ms < ${HOME_ENTRY_STABILIZE_MS}ms)")
            return
        }
    }

    // 홈 이탈 안정화: 홈을 떠난 직후 windows 소스의 잘못된 홈 재진입 방지 (신규)
    if (isHome && !isOnHomeScreen && source == "windows") {
        val elapsed = now - lastHomeExitAt
        if (elapsed < HOME_EXIT_STABILIZE_MS) {
            Log.d(TAG, "Home re-entry from windows ignored (stabilizing, ${elapsed}ms < ${HOME_EXIT_STABILIZE_MS}ms)")
            return
        }
    }

    // 홈 진입 시 타임스탬프 기록
    if (isHome && !isOnHomeScreen) {
        lastHomeEntryAt = now
    }

    // 홈 이탈 시 타임스탬프 기록 (신규)
    if (!isHome && isOnHomeScreen) {
        lastHomeExitAt = now
    }

    isOnHomeScreen = isHome
    Log.d(TAG, "Home screen state ($source): $isOnHomeScreen")
    overlay?.setHomeScreenState(isOnHomeScreen)
}
```

---

## 작업 순서 요약

1. ✅ 한국어 strings 생성 — 이미 완료됨 (`values-ko/strings.xml`)
2. 영어 strings.xml 수정 (신규 문자열 + 설명 수정)
3. 메뉴 리소스 생성 (`menu/settings_nav.xml`)
4. 레이아웃 파일 생성/교체:
   - `nav_header.xml` 생성
   - `activity_main.xml` 3벌 모두 2단 레이아웃으로 교체
   - Fragment 레이아웃 4개 생성
   - `activity_setup.xml` 생성
5. SettingsManager에 `setupComplete` 추가
6. Fragment 클래스 4개 생성 (NavBar, App, Keyboard, WacomPen)
7. SetupActivity 생성
8. MainActivity 재작성 (네비게이션 셸로)
9. AndroidManifest.xml 업데이트 (SetupActivity 등록)
10. 버그 수정 2건 (BackgroundManager + NavBarAccessibilityService)
11. 빌드 확인 (빌드 에러 수정)
12. 커밋 & 푸시

---

## 주의사항

- **Fragment에서 registerForActivityResult**: Fragment에서도 `registerForActivityResult()`를 동일하게 사용 가능. `onCreate()` 또는 필드 초기화 시 등록해야 함.
- **Fragment에서 Context**: `requireContext()` 또는 `requireActivity()` 사용.
- **Fragment에서 sendBroadcast**: `requireContext().sendBroadcast(intent)`.
- **SettingsManager 접근**: `SettingsManager.getInstance(requireContext())`.
- **기존 다크 모드 배경 코드**: 현재 브랜치에 다크 모드 배경 UI 및 로직이 이미 포함되어 있음. Fragment 이전 시 그대로 유지할 것.
- **viewBinding**: 사용하지 않고 있으므로 기존 패턴대로 `findViewById` 사용.
- **태블릿 전용**: 3개 레이아웃 모두 동일한 2단 레이아웃으로 통일해도 무방.
