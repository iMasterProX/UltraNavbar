# UltraNavbar 추후 작업 가이드

이 문서는 향후 구현할 기능들에 대한 상세 가이드입니다.

---

## 1. 급격한 가로세로 회전시 앱 크래시 버그 픽스

### 문제 분석
- `MainActivity`에 `android:configChanges` 속성이 없어 화면 회전 시 Activity가 재생성됨
- Fragment 상태가 제대로 저장/복원되지 않을 수 있음
- `NavBarAccessibilityService.onConfigurationChanged()`에서 오버레이 재생성 시 타이밍 이슈 가능

### 해결 방안

#### Option A: configChanges 추가 (권장)
`AndroidManifest.xml`의 MainActivity에 다음 추가:
```xml
<activity
    android:name=".MainActivity"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"
    ...>
```

그리고 `MainActivity.kt`에 `onConfigurationChanged` 오버라이드:
```kotlin
override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // 필요시 UI 업데이트
}
```

#### Option B: savedInstanceState 처리 강화
각 Fragment에서 상태 저장/복원 구현 확인:
- `NavBarSettingsFragment`
- `KeyboardSettingsFragment`
- `WacomPenSettingsFragment`
- `HardwareInfoFragment`
- `AppSettingsFragment`

### 확인할 파일
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/minsoo/ultranavbar/MainActivity.kt`
- `app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt` (lines 259-263)
- 모든 Fragment 파일들

### 테스트 방법
1. 앱 실행 후 각 탭으로 이동
2. 빠르게 가로/세로 회전 반복
3. 설정 변경 중 회전
4. 다이얼로그 표시 중 회전

---

## 2. 권한 토스트 메시지 버그 픽스

### 문제 분석
`permission_granted` 문자열이 "자동 배경 생성을 사용할 수 있습니다"로 되어 있어 블루투스 등 다른 권한에도 이 메시지가 표시됨.

### 해결 방안
각 권한별로 별도 메시지 사용하도록 수정

### 수정할 파일

#### `app/src/main/res/values/strings.xml` 및 `values-ko/strings.xml`
```xml
<!-- 기존 -->
<string name="permission_granted">Permission granted. Auto background generation is available.</string>

<!-- 수정: 각 권한별 메시지 -->
<string name="permission_granted_generic">Permission granted.</string>
<string name="permission_granted_storage">Storage permission granted. Auto background generation is available.</string>
<string name="permission_granted_bluetooth">Bluetooth permission granted.</string>
```

#### `app/src/main/java/com/minsoo/ultranavbar/ui/SetupActivity.kt`
- Line 56: `requestPermissionLauncher` 콜백에서 현재 단계에 맞는 메시지 표시
- Line 174, 190: 각각 storage/bluetooth에 맞는 메시지 사용

#### `app/src/main/java/com/minsoo/ultranavbar/ui/AppSettingsFragment.kt`
- Line 69: 권한 타입에 따른 메시지 분기 필요

---

## 3. 키보드 단축키에 길게 누름 동작 추가

### 요구사항
- 단일 키 길게 누름만 지원 (다른 키와 조합 X)
- 예: `A 길게 누름` -> 특정 앱 실행

### 구현 방안

#### 3.1 데이터 모델 수정
`app/src/main/java/com/minsoo/ultranavbar/model/KeyShortcut.kt`:
```kotlin
data class KeyShortcut(
    val id: String,
    val name: String,
    val modifiers: Set<Int>,
    val keyCode: Int,
    val actionType: ActionType,
    val actionData: String,
    val isLongPress: Boolean = false,  // 추가
    val longPressThreshold: Long = 500L  // 추가 (ms)
)
```

#### 3.2 UI 수정
`AddShortcutDialog.kt`의 Step 1에서:
- 길게 누름 모드 선택 옵션 추가
- 길게 누름 선택 시 modifier 입력 비활성화
- 단일 키만 입력받도록 처리

#### 3.3 키 이벤트 처리
`app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt`:
- `onKeyEvent`에서 KEY_DOWN 시 타이머 시작
- KEY_UP 시 타이머 취소 및 경과 시간 체크
- threshold 초과 시 길게 누름 액션 실행

### 참고 파일
- `app/src/main/java/com/minsoo/ultranavbar/ui/AddShortcutDialog.kt`
- `app/src/main/java/com/minsoo/ultranavbar/ui/KeyboardShortcutActivity.kt`
- `app/src/main/java/com/minsoo/ultranavbar/settings/KeyShortcutManager.kt`

---

## 4. 기본 제공 단축키 중첩 방지

### LG UltraTab 기본 단축키 목록 (key1.png, key2.png 참조)

| 기능 | 단축키 |
|------|--------|
| 앱 서랍 | Ctrl + A |
| 위젯 | Ctrl + W |
| 홈 | Search + ← |
| 뒤로 | Search + Backspace |
| 최근 앱 | Alt + Tab |
| 알림 | Search + N |
| 단축키 목록 | Search + / |
| 키보드 레이아웃 | Search + Space |
| 브라우저 | Search + B |
| 음악 | Search + P |
| 이메일 | Search + E |
| 주소록 | Search + C |
| 캘린더 | Search + L |

### 구현 방안

#### 4.1 예약 단축키 상수 정의
`app/src/main/java/com/minsoo/ultranavbar/model/ReservedShortcuts.kt` (새 파일):
```kotlin
object ReservedShortcuts {
    data class Reserved(val modifiers: Set<Int>, val keyCode: Int, val description: String)

    val list = listOf(
        Reserved(setOf(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_A, "앱 서랍"),
        Reserved(setOf(KeyEvent.KEYCODE_CTRL_LEFT), KeyEvent.KEYCODE_W, "위젯"),
        Reserved(setOf(KeyEvent.KEYCODE_META_LEFT), KeyEvent.KEYCODE_DPAD_LEFT, "홈"),
        // ... 나머지 추가
    )

    fun isReserved(modifiers: Set<Int>, keyCode: Int): Boolean {
        return list.any { it.modifiers == modifiers && it.keyCode == keyCode }
    }

    fun getDescription(modifiers: Set<Int>, keyCode: Int): String? {
        return list.find { it.modifiers == modifiers && it.keyCode == keyCode }?.description
    }
}
```

#### 4.2 단축키 추가 시 검증
`AddShortcutDialog.kt`에서 키 입력 후 검증:
```kotlin
if (ReservedShortcuts.isReserved(capturedModifiers, mainKeyCode)) {
    // 경고 다이얼로그 표시
    showReservedWarning(ReservedShortcuts.getDescription(capturedModifiers, mainKeyCode))
}
```

#### 4.3 가이드 UI 추가
키보드 설정 화면에 "시스템 단축키 보기" 버튼 추가:
- 다이얼로그로 기본 단축키 목록 표시
- 또는 별도 Activity로 표시

### 수정할 파일
- `app/src/main/java/com/minsoo/ultranavbar/ui/AddShortcutDialog.kt`
- `app/src/main/java/com/minsoo/ultranavbar/ui/KeyboardSettingsFragment.kt`
- `app/src/main/res/layout/fragment_keyboard_settings.xml`

---

## 5. 하드웨어 메뉴에서 디스플레이 패널 제조사 확인

### 조사 필요 사항
Android에서 디스플레이 패널 제조사 정보를 가져오는 방법:

#### 방법 1: sysfs 읽기
```kotlin
fun getDisplayPanelInfo(): String? {
    val paths = listOf(
        "/sys/class/graphics/fb0/panel_info",
        "/sys/class/drm/card0-DSI-1/panel_info",
        "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/panel_info",
        "/proc/display_info"
    )
    for (path in paths) {
        try {
            val file = File(path)
            if (file.exists()) {
                return file.readText().trim()
            }
        } catch (e: Exception) { }
    }
    return null
}
```

#### 방법 2: dumpsys 사용 (Shizuku 필요)
```kotlin
fun getDisplayInfoViaDumpsys(): String? {
    // Shizuku로 실행
    val process = Runtime.getRuntime().exec("dumpsys display")
    return process.inputStream.bufferedReader().readText()
}
```

#### 방법 3: Build 클래스 정보
```kotlin
Build.DISPLAY  // 빌드 디스플레이 정보
Build.HARDWARE // 하드웨어 정보
```

### 구현 위치
`app/src/main/java/com/minsoo/ultranavbar/ui/HardwareInfoFragment.kt`

### UI 추가
`app/src/main/res/layout/fragment_hardware_info.xml`에 디스플레이 섹션 추가

---

## 6. 블루투스 키보드 연결 시 화면 방향 고정 기능

### 요구사항
- 블루투스 키보드 연결 중 가로/세로 모드 고정 옵션
- 옵션: 끄기 / 가로 고정 / 세로 고정

### 구현 방안

#### 6.1 설정 추가
`SettingsManager.kt`:
```kotlin
var keyboardOrientationLock: Int
    get() = prefs.getInt("keyboard_orientation_lock", 0)  // 0=off, 1=landscape, 2=portrait
    set(value) = prefs.edit().putInt("keyboard_orientation_lock", value).apply()
```

#### 6.2 블루투스 연결 감지
`NavBarAccessibilityService.kt`에서 블루투스 키보드 연결 상태 모니터링:
```kotlin
private val bluetoothReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (isKeyboard(device)) {
                    applyOrientationLock()
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                removeOrientationLock()
            }
        }
    }
}
```

#### 6.3 화면 방향 고정
```kotlin
private fun applyOrientationLock() {
    val lock = settingsManager.keyboardOrientationLock
    if (lock == 0) return

    try {
        Settings.System.putInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0  // 자동 회전 끄기
        )
        Settings.System.putInt(
            contentResolver,
            Settings.System.USER_ROTATION,
            if (lock == 1) Surface.ROTATION_90 else Surface.ROTATION_0
        )
    } catch (e: Exception) {
        // WRITE_SETTINGS 권한 필요
    }
}
```

#### 6.4 UI 추가
`fragment_keyboard_settings.xml`에 드롭다운 또는 라디오 버튼 추가

### 필요 권한
```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
```

---

## 7. 기기 성능 최적화

### 최적화 가능 영역

#### 7.1 메모리 최적화
- Bitmap 캐싱 개선 (`BackgroundManager.kt`)
- 미사용 리소스 해제
- WeakReference 활용

#### 7.2 배터리 최적화
- 불필요한 리스너 해제
- Handler/Coroutine 작업 최적화
- WakeLock 사용 최소화

#### 7.3 UI 성능
- RecyclerView ViewHolder 재사용 확인
- 레이아웃 중첩 최소화
- overdraw 줄이기

#### 7.4 접근성 서비스 최적화
`NavBarAccessibilityService.kt`:
- `onAccessibilityEvent` 필터링 강화
- 이벤트 처리 debounce/throttle
- 불필요한 윈도우 쿼리 최소화

### 프로파일링 도구
- Android Studio Profiler
- LeakCanary (메모리 누수 감지)
- StrictMode (개발 중 성능 이슈 감지)

### 구현 예시
```kotlin
// StrictMode 설정 (디버그 빌드에서만)
if (BuildConfig.DEBUG) {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .build()
    )
}
```

---

## 추후 진행 예정 (아직 진행 X)

### A. 네비바 중앙 최근 앱 바로가기 (Android 12L 스타일)

**개념**: 하단 네비바 가운데에 최근 사용 앱 아이콘을 표시하여 빠른 전환 가능

**조사 필요**:
- `UsageStatsManager`로 최근 앱 목록 가져오기
- 오버레이 레이아웃 수정
- 아이콘 크기 및 개수 설정

### B. Q페어 스타일 스마트폰-태블릿 연동

**개념**: 스마트폰(iOS 포함)과 태블릿 간 알림, 클립보드, 파일 동기화

**조사 필요**:
- 로컬 네트워크 통신 (WebSocket, mDNS)
- iOS 연동을 위한 companion app (또는 웹 기반)
- 암호화 및 페어링 프로토콜
- 알림 미러링 (NotificationListenerService)
- 클립보드 동기화

**복잡도**: 매우 높음 - 별도 프로젝트 수준

---

## 작업 우선순위 권장

1. **버그 픽스** (높음)
   - 회전 크래시 (영향도 높음)
   - 권한 토스트 (간단한 수정)

2. **기능 개선** (중간)
   - 기본 단축키 중첩 방지
   - 길게 누름 단축키
   - 키보드 방향 고정

3. **정보성 기능** (낮음)
   - 디스플레이 패널 정보

4. **최적화** (지속)
   - 성능 프로파일링 및 개선

---

## 테스트 체크리스트

- [ ] 화면 회전 시 크래시 없음
- [ ] 각 권한 승인 시 올바른 메시지 표시
- [ ] 길게 누름 단축키 정상 동작
- [ ] 기본 단축키와 충돌 시 경고 표시
- [ ] 디스플레이 정보 정상 표시
- [ ] 키보드 연결 시 방향 고정 동작
- [ ] 메모리 누수 없음
