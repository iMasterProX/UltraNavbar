# 다음 세션 작업 지침 (Next Work Instructions)

> **작성일**: 2026-01-28
> **브랜치**: `claude/complete-app-build-f9EjL`
> **작성자**: Opus (코드 리뷰 및 부분 수정 완료)
> **참조**: `WORK_LOG.md`의 알려진 버그 #1~#4

---

## 이미 수정 완료 (이번 Opus 세션)

### A. AddShortcutDialog — modifier 캡처 손실 (Bug #2 근본 원인)

**파일**: `ui/AddShortcutDialog.kt`

**문제**: `pressedModifiers`가 KEY_UP에서 제거되므로, 유저가 Ctrl+1을 눌렀다 떼면 Ctrl이 사라지고 1만 남았음. `finishShortcut()`에서도 비어있는 `pressedModifiers`를 사용하여 modifier 없는 단축키가 저장됨.

**수정 내용**:
- `capturedModifiers` 세트 추가: 메인키(비modifier 키)가 눌리는 시점에 `pressedModifiers`를 스냅샷 복사
- `updateKeyCombinationDisplay()`: 메인키 캡처 후에는 `capturedModifiers` 기반으로 표시
- `finishShortcut()`: `capturedModifiers.toSet()` 사용
- `setupStep3()` 자동 이름 생성: `capturedModifiers` 사용
- 검증에 `capturedModifiers.isEmpty()` 체크 추가

### B. Step 2 RadioGroup 동작 불가 (Bug #2 관련)

**파일**: `res/layout/dialog_step2_action_type.xml`

**문제**: `RadioButton`이 `MaterialCardView` 안에 감싸져 있어서 `RadioGroup`의 직접 자식이 아니었음. Android `RadioGroup`은 직접 자식 `RadioButton`만 관리하므로, 선택/해제 상호배타 로직과 `setOnCheckedChangeListener`가 동작하지 않아 Step 2를 넘길 수 없었음.

**수정 내용**: `MaterialCardView` 래퍼 제거, `RadioButton`을 `RadioGroup`의 직접 자식으로 변경.

---

## 남은 버그 수정 작업

### 버그 #1: 권한 상태 메시지 오류

**위치**: `ui/AppSettingsFragment.kt`
**증상**: 이미 권한이 부여된 상태에서 각 권한 버튼을 누르면, 모두 동일한 Toast 메시지 "permission_granted"만 표시됨.

**원인**: `requestStoragePermission()`(line 213), `requestBluetoothPermission()`(line 254), `requestIgnoreBatteryOptimizations()`(line 235) 모두 이미 부여된 경우 동일한 `R.string.permission_granted` Toast를 보여줌. 문제는 이것이 기능의 용도를 설명하지 않는다는 점.

**수정 방향**:
각 권한별로 이미 부여된 상태에서의 메시지를 구체화:

```kotlin
// 저장소 권한 — 이미 부여 시
Toast.makeText(requireContext(), R.string.storage_permission_already_granted, Toast.LENGTH_SHORT).show()

// 블루투스 — 이미 부여 시
Toast.makeText(requireContext(), R.string.bluetooth_permission_already_granted, Toast.LENGTH_SHORT).show()

// 배터리 최적화 — 이미 제외 시 (이건 이미 별도 메시지 있음: battery_opt_already_ignored)
```

**필요 작업**:
1. `values/strings.xml`과 `values-ko/strings.xml`에 권한별 구체 메시지 추가:
   - `storage_permission_already_granted` = "Storage permission already granted. Auto background is available." / "저장소 권한이 이미 승인되었습니다. 자동 배경 생성을 사용할 수 있습니다."
   - `bluetooth_permission_already_granted` = "Bluetooth permission already granted." / "블루투스 권한이 이미 승인되었습니다."
   - `accessibility_already_enabled` = "Accessibility service is already enabled." / "접근성 서비스가 이미 활성화되어 있습니다."
2. `AppSettingsFragment.kt`의 각 권한 요청 함수에서 이미 부여된 경우 해당 메시지 사용

**난이도**: 간단

---

### 버그 #2: 단축키 캡처 잔여 이슈

Modifier 캡처 문제는 위 (A)에서 수정됨. 하지만 WORK_LOG에 언급된 추가 문제가 남아있음:

#### 2a. "바로가기 연결" 옵션 미표시

**위치**: `ui/AddShortcutDialog.kt` line 86-93, `dialog_step2_action_type.xml`

**증상**: Step 2에서 "앱 연결"과 "설정 연결"만 표시되고 "바로가기 연결"(`ActionType.SHORTCUT`)이 없음.

**원인**: `dialog_step2_action_type.xml`에 3번째 `RadioButton`이 없고, `setupStep2()`에서도 `SHORTCUT` 타입을 처리하지 않음.

**수정 방향**:
1. `dialog_step2_action_type.xml`에 세 번째 RadioButton 추가:
   ```xml
   <RadioButton
       android:id="@+id/radioShortcut"
       android:layout_width="match_parent"
       android:layout_height="wrap_content"
       android:text="@string/action_shortcut"
       android:padding="16dp"
       android:layout_marginBottom="8dp"
       android:background="?attr/selectableItemBackground" />
   ```

2. `AddShortcutDialog.setupStep2()`에 SHORTCUT 처리 추가:
   ```kotlin
   R.id.radioShortcut -> selectedActionType = KeyShortcut.ActionType.SHORTCUT
   ```

3. `setupStep3()`에 SHORTCUT 타입 UI 추가 (Intent URI 직접 입력 또는 시스템 바로가기 선택)

**난이도**: 중간 (SHORTCUT 타입의 UI 설계 필요)

#### 2b. Step 3 "앱 연결" 선택 후 진행 불가

**위치**: `ui/AddShortcutDialog.kt` line 152-163

**증상**: Step 3에서 앱을 선택해도 다이얼로그가 앱 목록 Activity로 전환된 후 돌아오면 진행이 안 될 수 있음.

**원인**: `AppListActivity` 결과는 `KeyboardShortcutActivity.appPickerLauncher`에서 수신되어 `currentDialog?.setSelectedApp(packageName)`을 호출함. 만약 다이얼로그가 Activity 재생성 중에 사라지면 `currentDialog`가 null이 됨.

**수정 방향**:
- `KeyboardShortcutActivity`에서 `onSaveInstanceState`/`onRestoreInstanceState` 처리
- 또는 `appPickerLauncher` 결과를 임시 저장하고 다이얼로그 재표시 시 적용
- **가장 간단한 방법**: `AddShortcutDialog`를 `DialogFragment`로 변환하면 생명주기가 자동 관리됨

**난이도**: 중간~높음 (DialogFragment 전환 권장)

---

### 버그 #3: 배터리 기능 전체 미작동

**위치**: `KeyboardBatteryMonitor.kt`, `KeyboardBatteryWidget.kt`, `KeyboardSettingsFragment.kt`

**증상**: 배터리 잔량 표시/알림/위젯 모두 동작하지 않음. 키보드가 연결되어 있어도 "연결된 키보드 없음" 표시.

#### 진단 순서 (Logcat 필요):

**Step 1 — 키보드 감지 확인**:
```
adb logcat -s KeyboardSettings KeyboardBatteryWidget KeyboardBatteryMonitor
```
로그에서 "Found X bonded devices" → "Device: ... isKeyboard=false" 가 나오면 감지 실패.

**Step 2 — device class 확인**:
LG UltraTab 키보드(LG KBA10)의 Bluetooth Device Class 값을 확인.
로그에서 `class=XXXX, major=YYYY` 확인 후:
- `major`가 `0x500`이 아니면: Peripheral이 아닌 다른 major class로 보고되는 것
- `(deviceClassCode and 0x40) == 0`이면: keyboard minor bit가 아닌 것

**Step 3 — `isKeyboardDevice()` 수정**:
3개 파일에 동일한 `isKeyboardDevice()` 함수가 중복되어 있음 (DRY 위반):
- `KeyboardSettingsFragment.kt:195`
- `KeyboardBatteryWidget.kt:173`
- `KeyboardBatteryMonitor.kt:196`

**수정 방향**:
1. **공용 유틸 추출**: `BluetoothUtils.kt`를 만들어 `isKeyboardDevice()`를 한 곳에서 관리
   ```kotlin
   // util/BluetoothUtils.kt
   object BluetoothUtils {
       fun isKeyboardDevice(device: BluetoothDevice): Boolean {
           val deviceClass = device.bluetoothClass ?: return false
           val majorDeviceClass = deviceClass.majorDeviceClass
           val deviceClassCode = deviceClass.deviceClass

           // 방법 1: Peripheral + Keyboard minor class
           val isPeripheral = majorDeviceClass == 0x500
           val isKeyboard = (deviceClassCode and 0x40) != 0

           // 방법 2: 이름 기반 폴백 (기기명에 "keyboard" 포함)
           val nameContainsKeyboard = device.name
               ?.contains("keyboard", ignoreCase = true) == true
           val isLgKeyboard = device.name
               ?.startsWith("LG KBA", ignoreCase = true) == true

           return (isPeripheral && isKeyboard) || nameContainsKeyboard || isLgKeyboard
       }
   }
   ```

2. 3개 파일의 `isKeyboardDevice()`를 `BluetoothUtils.isKeyboardDevice(device)`로 교체

3. **getBatteryLevel() 대안**:
   리플렉션이 실패하면 `BluetoothDevice.getBatteryLevel()`이 해당 OEM에서 구현되지 않은 것임.
   대안 접근법:
   - `ACTION_BATTERY_LEVEL_CHANGED` broadcast 수신 등록 (가장 안정적)
   - `BluetoothHidDevice` 또는 `BluetoothHidHost` profile 사용
   - Battery Service (BAS) GATT profile 직접 읽기

   **참고**: `BluetoothDevice.getBatteryLevel()`은 `@SystemApi`로, 일반 앱에서는 reflection으로만 접근 가능하며, 모든 기기에서 지원하지 않음. LG UltraTab에서 실제로 지원하는지 Logcat 확인 필요.

**난이도**: 높음 (실기기 테스트 필수)

---

### 버그 #4: 홈 배경 자동 생성 해상도 불일치

**위치**: `util/ImageCropUtil.kt`, `settings/SettingsManager.kt`

**증상**: 울트라탭(2000×1200)에서 홈 배경 크롭이 실제 네비바 높이와 맞지 않음.

**원인**: `SettingsManager.CROP_HEIGHT_PX = 72`가 하드코딩되어 있음. 실제 시스템 네비바 높이는 기기/방향에 따라 다름. `NavBarOverlay.kt:406-409`에서는 이미 동적으로 가져오는 코드가 있음.

**수정 방향**:
1. `ImageCropUtil`에 동적 높이 계산 추가:
   ```kotlin
   fun getNavigationBarHeight(context: Context, isLandscape: Boolean): Int {
       val res = context.resources
       val resName = if (!isLandscape) "navigation_bar_height" else "navigation_bar_height_landscape"
       val id = res.getIdentifier(resName, "dimen", "android")
       return if (id > 0) res.getDimensionPixelSize(id) else CROP_HEIGHT_PX
   }
   ```

2. `cropAndSave()`에서 `CROP_HEIGHT_PX` 대신 동적 높이 사용:
   ```kotlin
   val cropHeight = getNavigationBarHeight(context, isLandscape)
   ```

3. `CROP_HEIGHT_PX`는 폴백 기본값으로만 유지

**난이도**: 간단

---

## 추가 발견 이슈 (버그 목록에 없지만 수정 권장)

### 이슈 #5: BT 기기 "연결됨" 상태 부정확

**위치**: `KeyboardSettingsFragment.kt:279-285`

**증상**: 모든 페어링된(bonded) 키보드가 "연결됨"으로 표시됨. 실제로는 페어링만 되고 연결되지 않은 기기도 있을 수 있음.

**수정 방향**:
`BluetoothDevice`에는 public `isConnected()` 메서드가 없으므로, 리플렉션 또는 `BluetoothProfile` 사용:
```kotlin
// 리플렉션 방식
private fun isDeviceConnected(device: BluetoothDevice): Boolean {
    return try {
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }
}
```

**난이도**: 간단 (리플렉션 추가)

---

### 이슈 #6: 알림 아이콘 색상

**위치**: `drawable/ic_keyboard.xml`, `KeyboardBatteryMonitor.kt:178`

**증상**: 알림 소형 아이콘(`setSmallIcon`)에 검정색(`#FF000000`) 벡터를 사용. Android 알림 아이콘은 흰색/모노크롬이어야 시스템이 올바르게 렌더링함.

**수정 방향**: `ic_keyboard.xml`의 `fillColor`와 `strokeColor`를 `#FFFFFFFF`로 변경하거나, 별도 알림용 아이콘 생성.

**난이도**: 간단

---

### 이슈 #7: 불필요한 API 레벨 체크

**위치**: 다수 파일

`minSdk = 31` (Android 12)이므로 아래 체크는 항상 참이라 불필요:
- `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31) — 항상 true
- `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` (API 26) — 항상 true

제거하면 코드가 간결해짐. 단, `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` (API 33) 체크는 targetSdk=34이므로 유지 필요.

**난이도**: 간단 (일괄 검색/치환)

---

### 이슈 #8: READ_EXTERNAL_STORAGE → READ_MEDIA_IMAGES

**위치**: `AndroidManifest.xml`, `AppSettingsFragment.kt`, `SetupActivity.kt`

`targetSdk=34`이므로 API 33+ 기기에서 `READ_EXTERNAL_STORAGE`가 부여되지 않음. `READ_MEDIA_IMAGES`로 전환 필요.

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"
    android:minSdkVersion="33" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

Kotlin에서:
```kotlin
val permission = if (Build.VERSION.SDK_INT >= 33) {
    Manifest.permission.READ_MEDIA_IMAGES
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}
```

**난이도**: 간단

---

## 작업 우선순위

| 순서 | 항목 | 난이도 | 비고 |
|------|------|--------|------|
| 1 | **빌드 검증** (`./gradlew assembleDebug`) | 필수 | 이번 수정 + 이전 변경 모두 포함 |
| 2 | **버그 #4**: 홈 배경 해상도 동적 계산 | 간단 | `ImageCropUtil.kt` 한 파일 |
| 3 | **이슈 #8**: READ_MEDIA_IMAGES 권한 전환 | 간단 | targetSdk=34에서 필수 |
| 4 | **버그 #1**: 권한별 구체적 메시지 | 간단 | 문자열 추가 + Toast 교체 |
| 5 | **이슈 #5**: BT 연결 상태 정확도 | 간단 | 리플렉션 추가 |
| 6 | **이슈 #6**: 알림 아이콘 흰색 변환 | 간단 | XML 색상 변경 |
| 7 | **이슈 #7**: 불필요 API 체크 제거 | 간단 | minSdk=31 기반 정리 |
| 8 | **버그 #2a**: 바로가기 연결 옵션 추가 | 중간 | RadioButton + Step 3 UI |
| 9 | **버그 #3**: 배터리 감지 수정 | 높음 | 실기기 Logcat 필요 |
| 10 | **버그 #2b**: 다이얼로그 생명주기 | 높음 | DialogFragment 전환 권장 |

---

## 파일 구조 참고

```
변경된 파일 (이번 Opus 세션):
  ui/AddShortcutDialog.kt          ← modifier 캡처 수정
  res/layout/dialog_step2_action_type.xml  ← RadioGroup 수정

주요 버그 관련 파일:
  ui/AppSettingsFragment.kt        ← 버그 #1 (권한 메시지)
  ui/AddShortcutDialog.kt          ← 버그 #2 (잔여 이슈)
  service/KeyboardBatteryMonitor.kt ← 버그 #3 (배터리)
  widget/KeyboardBatteryWidget.kt  ← 버그 #3 (위젯)
  ui/KeyboardSettingsFragment.kt   ← 버그 #3 (설정 UI) + 이슈 #5
  util/ImageCropUtil.kt            ← 버그 #4 (해상도)
  settings/SettingsManager.kt      ← 버그 #4 (CROP_HEIGHT_PX)
```

---

## 기존 버그 픽스 (건드리지 말 것)

- **BackgroundManager.kt:419-426** — `buttonColorAnimationTarget` 순서 수정
- **NavBarAccessibilityService.kt:36,376-398** — `HOME_EXIT_STABILIZE_MS` 홈 이탈 안정화
