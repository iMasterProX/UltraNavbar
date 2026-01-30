# Wacom AES 펜 설정 구현 가이드

> **작성일**: 2026-01-30
> **대상**: Claude Opus/Sonnet (토큰 한도 회복 후 작업용)

---

## 개요

LG UltraTab의 Wacom AES 펜 버튼(A/B)에 대한 확장 기능 구현 가이드입니다.
기본 설정 앱에서는 "앱 실행"만 가능하지만, 이 확장 기능에서는:
1. **앱 바로가기(Shortcuts)** 지원
2. **페인팅 앱 표준 기능** (Undo/Redo, 도구 전환 등) 지원

---

## 사전 요구사항: WRITE_SECURE_SETTINGS 권한

### 권한 확인 로직
```kotlin
// PenSettingsFragment.kt 또는 WacomPenSettingsFragment.kt
private fun hasWriteSecureSettings(): Boolean {
    return try {
        // 테스트로 현재 값 읽고 다시 쓰기
        val current = Settings.Global.getInt(contentResolver, "pen_pointer", 0)
        Settings.Global.putInt(contentResolver, "pen_pointer", current)
        true
    } catch (e: SecurityException) {
        false
    }
}
```

### 권한 없을 때 UI
펜 설정 메뉴 진입 시 권한이 없으면 다음 화면 표시:

```xml
<!-- fragment_pen_settings_permission.xml -->
<LinearLayout>
    <ImageView android:src="@drawable/ic_warning" />

    <TextView android:text="@string/pen_settings_permission_required_title" />
    <!-- "이 메뉴를 사용하려면 ADB로 '한 번만' 권한 부여가 필요합니다" -->

    <TextView android:text="@string/pen_settings_permission_guide" />
    <!--
    "1. PC에 ADB를 설치합니다
     2. USB 디버깅을 활성화합니다
     3. 다음 명령어를 실행합니다:"
    -->

    <TextView
        android:fontFamily="monospace"
        android:text="adb shell pm grant com.minsoo.ultranavbar android.permission.WRITE_SECURE_SETTINGS"
        android:textIsSelectable="true" />

    <Button
        android:text="@string/pen_settings_check_permission"
        android:onClick="checkPermissionAndProceed" />
        <!-- "권한 확인 후 진행" -->
</LinearLayout>
```

---

## 기능 1: 앱 바로가기(Shortcuts) 지원

### 배경
Android의 `ShortcutManager`를 통해 앱들이 제공하는 바로가기를 가져올 수 있습니다.
예: 카카오톡 특정 채팅방 열기, Chrome 시크릿 모드 등

### 구현 방법

#### 1. 바로가기 목록 가져오기
```kotlin
// ShortcutHelper.kt
@RequiresApi(Build.VERSION_CODES.N_MR1)
fun getAvailableShortcuts(context: Context): List<ShortcutInfo> {
    val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val userHandle = Process.myUserHandle()

    val shortcuts = mutableListOf<ShortcutInfo>()

    // 설치된 모든 앱의 static/dynamic/pinned shortcuts 수집
    val packages = context.packageManager.getInstalledApplications(0)
    for (app in packages) {
        try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )

            launcherApps.getShortcuts(query, userHandle)?.let {
                shortcuts.addAll(it)
            }
        } catch (e: Exception) {
            // 권한 없는 앱은 스킵
        }
    }
    return shortcuts
}
```

#### 2. 바로가기 실행
```kotlin
fun launchShortcut(context: Context, shortcutInfo: ShortcutInfo) {
    val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    launcherApps.startShortcut(
        shortcutInfo.`package`,
        shortcutInfo.id,
        null,
        null,
        Process.myUserHandle()
    )
}
```

#### 3. 저장 형식
Settings.Global에는 ComponentName 형식만 저장 가능하므로, 바로가기는 별도 저장:
```kotlin
// SettingsManager.kt에 추가
private const val KEY_PEN_A_SHORTCUT_PACKAGE = "pen_a_shortcut_package"
private const val KEY_PEN_A_SHORTCUT_ID = "pen_a_shortcut_id"
private const val KEY_PEN_B_SHORTCUT_PACKAGE = "pen_b_shortcut_package"
private const val KEY_PEN_B_SHORTCUT_ID = "pen_b_shortcut_id"

// 타입 구분
enum class PenButtonActionType {
    NONE,           // 비활성화
    APP,            // 앱 실행 (기본 설정 앱과 동일)
    SHORTCUT,       // 앱 바로가기
    PAINT_FUNCTION  // 페인팅 기능 (아래 참조)
}
```

---

## 기능 2: 페인팅 앱 표준 기능

### 표준 키 이벤트 (대부분의 페인팅 앱 지원)

| 기능 | 키 조합 | KeyEvent |
|------|---------|----------|
| Undo | Ctrl+Z | KEYCODE_Z + META_CTRL_ON |
| Redo | Ctrl+Y 또는 Ctrl+Shift+Z | KEYCODE_Y + META_CTRL_ON |
| 브러시/지우개 전환 | E | KEYCODE_E |
| 브러시 크기 증가 | ] | KEYCODE_RIGHT_BRACKET |
| 브러시 크기 감소 | [ | KEYCODE_LEFT_BRACKET |
| 색상 피커 | I (eyedropper) | KEYCODE_I |
| 전체 선택 | Ctrl+A | KEYCODE_A + META_CTRL_ON |
| 선택 해제 | Ctrl+D | KEYCODE_D + META_CTRL_ON |

### 호환 앱 목록 (예시로 문서에 포함)

다음 앱들이 위 표준 키보드 단축키를 지원합니다:
- **ibis Paint X** - Undo(Ctrl+Z), Redo(Ctrl+Y)
- **MediBang Paint** - Undo/Redo, E(지우개)
- **Clip Studio Paint** - 전체 지원
- **Autodesk Sketchbook** - Undo/Redo
- **Infinite Painter** - Undo/Redo
- **Krita (Android)** - 전체 지원
- **Concepts** - Undo/Redo

### 구현 방법

#### 1. 키 이벤트 주입 (AccessibilityService 사용)
```kotlin
// NavBarAccessibilityService.kt에 추가
fun injectKeyEvent(keyCode: Int, metaState: Int = 0) {
    val down = KeyEvent(
        SystemClock.uptimeMillis(),
        SystemClock.uptimeMillis(),
        KeyEvent.ACTION_DOWN,
        keyCode,
        0,
        metaState
    )
    val up = KeyEvent(
        SystemClock.uptimeMillis(),
        SystemClock.uptimeMillis(),
        KeyEvent.ACTION_UP,
        keyCode,
        0,
        metaState
    )

    // InputManager를 통한 주입 (리플렉션 필요)
    // 또는 Instrumentation 사용
}
```

#### 2. 펜 버튼 이벤트 감지
펜 버튼은 `himax-stylus` 디바이스에서 발생합니다.
AccessibilityService의 `onKeyEvent()`에서 감지:
```kotlin
override fun onKeyEvent(event: KeyEvent): Boolean {
    // BTN_STYLUS (펜 버튼 A) = 331
    // BTN_STYLUS2 (펜 버튼 B) = 332
    if (event.device?.name == "himax-stylus") {
        when (event.keyCode) {
            331 -> handlePenButtonA(event)
            332 -> handlePenButtonB(event)
        }
    }
    return super.onKeyEvent(event)
}
```

**주의**: 펜 버튼 keyCode는 실제 테스트로 확인 필요. `getevent -l` 명령으로 확인:
```bash
adb shell getevent -l /dev/input/event3
# 펜 버튼 누르면서 확인
```

#### 3. 페인팅 기능 정의
```kotlin
enum class PaintFunction(
    val displayName: String,
    val keyCode: Int,
    val metaState: Int = 0
) {
    UNDO("실행 취소 (Undo)", KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
    REDO("다시 실행 (Redo)", KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON),
    ERASER_TOGGLE("지우개 전환", KeyEvent.KEYCODE_E, 0),
    BRUSH_SIZE_UP("브러시 크기 +", KeyEvent.KEYCODE_RIGHT_BRACKET, 0),
    BRUSH_SIZE_DOWN("브러시 크기 -", KeyEvent.KEYCODE_LEFT_BRACKET, 0),
    COLOR_PICKER("색상 피커", KeyEvent.KEYCODE_I, 0)
}
```

---

## UI 구조

### 메뉴 구조
```
와콤 펜 설정
├── [권한 안내 화면] (권한 없을 때만)
├── 펜 포인터 표시 (토글)
├── 필기 시 네비바 제스처 무시 (토글)
├── 펜 버튼 A 설정
│   ├── 비활성화
│   ├── 앱 실행 → 앱 선택기
│   ├── 앱 바로가기 → 바로가기 선택기
│   └── 페인팅 기능 → 기능 선택기
├── 펜 버튼 B 설정
│   └── (위와 동일)
└── 설정 초기화 (Settings.Global 값 제거)
```

### 설정 초기화 기능
```kotlin
fun resetPenSettings() {
    // Settings.Global 값 삭제
    Settings.Global.putString(contentResolver, "a_button_component_name", null)
    Settings.Global.putString(contentResolver, "b_button_component_name", null)
    Settings.Global.putInt(contentResolver, "a_button_setting", 0)
    Settings.Global.putInt(contentResolver, "b_button_setting", 0)

    // 앱 내부 설정도 초기화
    settingsManager.penAActionType = PenButtonActionType.NONE
    settingsManager.penBActionType = PenButtonActionType.NONE
    // ... 기타

    Toast.makeText(context, "펜 설정이 초기화되었습니다. 기본 설정 앱을 사용하세요.", LENGTH_LONG).show()
}
```

---

## 파일 생성/수정 목록

### 새로 생성할 파일
1. `ui/WacomPenSettingsFragment.kt` - 메인 펜 설정 화면
2. `ui/PenButtonConfigActivity.kt` - 버튼 설정 상세 화면
3. `ui/ShortcutPickerActivity.kt` - 바로가기 선택기
4. `model/PenButtonConfig.kt` - 펜 버튼 설정 모델
5. `util/ShortcutHelper.kt` - 바로가기 유틸리티
6. `res/layout/fragment_pen_settings.xml`
7. `res/layout/fragment_pen_permission_required.xml`
8. `res/layout/activity_pen_button_config.xml`
9. `res/layout/activity_shortcut_picker.xml`
10. `res/layout/item_shortcut.xml`

### 수정할 파일
1. `AndroidManifest.xml` - WRITE_SECURE_SETTINGS 권한 추가
2. `SettingsManager.kt` - 펜 관련 설정 키 추가
3. `NavBarAccessibilityService.kt` - 펜 버튼 이벤트 처리
4. `strings.xml` / `strings-ko.xml` - 문자열 추가
5. `nav_graph.xml` - 네비게이션 추가

---

## 문자열 (strings.xml)

```xml
<!-- 펜 설정 -->
<string name="pen_settings_title">Wacom 펜 설정</string>
<string name="pen_settings_permission_required_title">권한 부여가 필요합니다</string>
<string name="pen_settings_permission_guide">이 메뉴를 사용하려면 ADB로 \'한 번만\' 권한을 부여해야 합니다.\n\n1. PC에 ADB를 설치합니다\n2. USB 디버깅을 활성화합니다\n3. 다음 명령어를 실행합니다:</string>
<string name="pen_settings_adb_command">adb shell pm grant com.minsoo.ultranavbar android.permission.WRITE_SECURE_SETTINGS</string>
<string name="pen_settings_check_permission">권한 확인 후 진행</string>

<string name="pen_pointer_title">펜 포인터 표시</string>
<string name="pen_pointer_summary">화면에 펜 위치를 표시합니다</string>
<string name="pen_ignore_gestures_title">필기 시 네비바 제스처 무시</string>
<string name="pen_ignore_gestures_summary">펜 사용 중 실수로 네비바를 터치하는 것을 방지합니다</string>

<string name="pen_button_a_title">펜 버튼 A 설정</string>
<string name="pen_button_b_title">펜 버튼 B 설정</string>
<string name="pen_button_action_none">비활성화</string>
<string name="pen_button_action_app">앱 실행</string>
<string name="pen_button_action_shortcut">앱 바로가기</string>
<string name="pen_button_action_paint">페인팅 기능</string>

<string name="pen_paint_function_undo">실행 취소 (Undo)</string>
<string name="pen_paint_function_redo">다시 실행 (Redo)</string>
<string name="pen_paint_function_eraser">지우개 전환</string>
<string name="pen_paint_function_brush_up">브러시 크기 +</string>
<string name="pen_paint_function_brush_down">브러시 크기 -</string>
<string name="pen_paint_function_color_picker">색상 피커</string>

<string name="pen_paint_compatible_apps">호환 앱: ibis Paint X, MediBang Paint, Clip Studio Paint, Krita, Infinite Painter 등</string>

<string name="pen_reset_settings">펜 설정 초기화</string>
<string name="pen_reset_settings_summary">이 앱의 펜 설정을 제거하고 기본 설정 앱 사용으로 돌아갑니다</string>
<string name="pen_reset_confirm">정말 초기화하시겠습니까?</string>
<string name="pen_reset_done">펜 설정이 초기화되었습니다</string>
```

---

## 테스트 체크리스트

- [ ] 권한 없이 메뉴 진입 시 안내 화면 표시
- [ ] ADB 권한 부여 후 설정 화면 진입 가능
- [ ] 펜 포인터 토글 동작
- [ ] 네비바 제스처 무시 토글 동작
- [ ] 앱 선택 및 실행 동작
- [ ] 바로가기 선택 및 실행 동작
- [ ] 페인팅 기능 (ibis Paint X에서 Undo/Redo 테스트)
- [ ] 설정 초기화 후 기본 설정 앱 정상 동작
- [ ] 앱 재설치 후에도 권한 유지 확인 (또는 재부여 안내)

---

## 추가 조사 필요 사항

1. **펜 버튼 KeyCode 확인**: 실제 디바이스에서 `getevent -l /dev/input/event3` 실행하여 버튼 A/B의 정확한 keyCode 확인
2. **키 이벤트 주입 방법**: AccessibilityService에서 키 이벤트 주입이 가능한지, 또는 다른 방법 필요한지 확인
3. **바로가기 권한**: `BIND_APPWIDGET` 또는 기타 권한 필요 여부 확인
