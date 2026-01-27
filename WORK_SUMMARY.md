# UltraNavbar 작업 정리 보고서

**작성일**: 2026-01-27
**버전**: 0.2.0
**세션**: Sonnet 4.5 → Opus 인계

---

## ⚠️ 중요: 완료/미완료 상태

### ✅ 완료된 작업
1. 태블릿 전용 앱 제한 구현
2. 앱 설정 화면에서 홈 배경 비활성화
3. LG KBA10 키보드 특별 처리 (우선 정렬, 이름 표시)
4. 블루투스 권한 관련 전체 작업 (초기 설정, 앱 설정, 키보드 설정)
5. 아이콘 색상 통일
6. 키보드 단축키 백엔드 구조 (데이터 모델, 관리자, 감지 서비스)

### ❌ 미완료된 작업 (다음 세션에서 반드시 구현 필요)

#### 1. 키보드 단축키 UI (사용자가 볼 수 있는 화면 없음!)
- [ ] 단축키 목록 표시
- [ ] 단축키 추가 UI (예: "Ctrl + 1" -> "설정 앱 열기")
- [ ] 단축키 편집/삭제 UI
- [ ] 키 조합 입력 UI (사용자가 Ctrl+1을 직접 누르면 감지)
- [ ] 액션 선택 UI (앱, 바로가기, 설정 중 선택)

**현재 상태**: KeyShortcut.kt, KeyShortcutManager.kt, KeyEventHandler.kt 파일은 생성됨. 하지만 사용자가 단축키를 설정할 UI가 전혀 없음!

#### 2. 배터리 잔량 표시 (전혀 안함!)
- [ ] 키보드 기기 카드에 배터리 잔량 표시
- [ ] BluetoothDevice에서 배터리 정보 가져오기
- [ ] 배터리 아이콘/퍼센트 UI

**현재 상태**: KeyboardSettingsFragment에서 "배터리: 알 수 없음"만 표시. 실제 배터리 정보를 가져오는 로직 없음.

#### 3. 배터리 위젯 (전혀 안함!)
- [ ] AppWidgetProvider 클래스 생성
- [ ] 위젯 레이아웃 XML
- [ ] 위젯 업데이트 서비스
- [ ] AndroidManifest에 위젯 등록

**현재 상태**: 아무것도 안 만들어짐.

#### 4. 배터리 알림 (전혀 안함!)
- [ ] 배터리 부족 감지 로직
- [ ] NotificationChannel 생성
- [ ] 알림 표시 로직
- [ ] 알림 설정 UI

**현재 상태**: 아무것도 안 만들어짐.

---

## 2. 완료된 작업 상세 변경 사항

### 2.1 태블릿 전용 앱 제한

**파일**: `app/src/main/AndroidManifest.xml`

- **목적**: 스마트폰에서 앱 설치 및 실행 방지
- **변경 내용**:
  - `<supports-screens>` 태그 추가하여 600dp 이상 화면 요구
  - BLUETOOTH, BLUETOOTH_CONNECT 권한 추가
  ```xml
  <supports-screens
      android:requiresSmallestWidthDp="600"
      android:largeScreens="true"
      android:xlargeScreens="true" />
  ```

**파일**: `NavBarAccessibilityService.kt:133-137`

- 서비스 시작 시 태블릿 여부 확인하여 태블릿이 아니면 자동 비활성화

---

### 2.2 앱 설정 화면에서 홈 배경 비활성화

**목적**: 사용자가 앱 설정 화면을 볼 때 커스텀 홈 배경이 표시되지 않도록 함

**파일**: `core/BackgroundManager.kt:487-503`

- `shouldUseCustomBackground` 함수에 `currentPackage` 파라미터 추가
- 현재 패키지가 앱 자신의 패키지인 경우 커스텀 배경 사용 안 함

**파일**: `overlay/NavBarOverlay.kt`

- `currentPackage` 필드 추가 (line 80)
- `setForegroundPackage` 메서드 추가 (line 1108-1113)
- `shouldUseCustomBackground` 호출 시 현재 패키지 전달 (line 1218)

**파일**: `service/NavBarAccessibilityService.kt:443`

- `updateForegroundPackage`에서 overlay에 현재 패키지 전달

---

### 2.3 LG KBA10 키보드 특별 처리

**파일**: `ui/KeyboardSettingsFragment.kt`

**기능 1: 우선 정렬** (line 105-120)
- LG KBA10으로 시작하는 키보드를 목록 최상단에 표시
- `sortedByDescending`을 사용하여 우선순위 정렬

**기능 2: 표시 이름 개선** (line 178-182)
- LG KBA10 기기는 "LG UltraTab Keyboard (LG KBA10)"으로 표시
- 사용자가 기기를 쉽게 식별할 수 있도록 개선

---

### 2.4 키보드 단축키 시스템 구현

#### 데이터 모델

**파일**: `model/KeyShortcut.kt` (신규 생성)

- 단축키 데이터 클래스 정의
- 주요 필드:
  - `id`: 고유 식별자
  - `modifiers`: Ctrl, Shift, Alt, Meta 등
  - `keyCode`: 주요 키 코드
  - `actionType`: APP, SHORTCUT, SETTINGS, CUSTOM_ACTION
  - `actionData`: 실행할 앱 패키지명 또는 Intent URI
- 주요 메서드:
  - `toJson()` / `fromJson()`: 직렬화/역직렬화
  - `getDisplayString()`: "Ctrl + Shift + 1" 형식 표시
  - `matches()`: 눌린 키 조합 매칭

#### 단축키 관리자

**파일**: `settings/KeyShortcutManager.kt` (신규 생성)

- SharedPreferences 기반 단축키 저장/로드
- 주요 기능:
  - `loadShortcuts()`: 저장된 단축키 로드
  - `addShortcut()`: 새 단축키 추가
  - `updateShortcut()`: 단축키 수정
  - `deleteShortcut()`: 단축키 삭제
  - `findShortcut()`: 키 조합으로 단축키 찾기
  - `isDuplicate()`: 중복 확인

#### 키 이벤트 핸들러

**파일**: `service/KeyEventHandler.kt` (신규 생성)

- AccessibilityService의 키 이벤트 처리 전담
- 주요 기능:
  - 수정자 키(Ctrl, Shift 등) 상태 추적
  - 키 조합 매칭 및 단축키 실행
  - 앱 실행, Intent 실행, 시스템 설정 열기 지원

#### 서비스 통합

**파일**: `service/NavBarAccessibilityService.kt`

- `KeyEventHandler` 인스턴스 추가 (line 48)
- `onKeyEvent` 메서드 구현 (line 778-781)

**파일**: `res/xml/accessibility_service_config.xml`

- `flagRequestFilterKeyEvents` 플래그 추가하여 키 이벤트 필터링 활성화

---

### 2.5 블루투스 권한 관련 작업

#### 문자열 리소스 추가

**파일**: `res/values/strings.xml`

```xml
<string name="permission_bluetooth">Bluetooth access</string>
<string name="permission_bluetooth_summary">Required for keyboard shortcuts and device detection.</string>
<string name="setup_bluetooth_title">Bluetooth access permission</string>
<string name="setup_bluetooth_desc">Bluetooth permission is required to detect connected keyboards and use keyboard shortcuts.</string>
```

**파일**: `res/values-ko/strings.xml`

한국어 번역 추가

#### 초기 설정 화면 (SetupActivity)

**파일**: `ui/SetupActivity.kt`

- 4단계로 확장 (접근성 → 저장소 → 배터리 → **블루투스**)
- Android 12(API 31) 이상에서 BLUETOOTH_CONNECT 권한 요청
- 주요 변경:
  - `Build` import 추가
  - 단계 수 3 → 4로 변경
  - `showStep` 함수에 블루투스 단계 추가 (line 106-111)
  - `updateStepStatus`에 블루투스 권한 확인 추가 (line 128-138)
  - `grantCurrentStepPermission`에 블루투스 권한 요청 추가 (line 172-187)
  - `nextStep` 조건 수정 (line 212)

#### 앱 설정 화면 (AppSettingsFragment)

**파일**: `res/layout/fragment_app_settings.xml`

- 블루투스 권한 상태 표시 UI 추가 (line 284-326)
- 접근성, 저장소, 배터리와 동일한 패턴

**파일**: `ui/AppSettingsFragment.kt`

- `Build` import 추가
- `txtPermBluetooth` 필드 추가 (line 46)
- `initViews`에 블루투스 버튼 리스너 추가 (line 121-124)
- `updatePermissionStatus`에 블루투스 권한 확인 추가 (line 175-189)
- `requestBluetoothPermission` 메서드 추가 (line 246-260)

#### 키보드 설정 화면 (KeyboardSettingsFragment)

**파일**: `ui/KeyboardSettingsFragment.kt`

- `ActivityResultContracts` import 추가
- `requestBluetoothPermissionLauncher` 추가 (line 34-42)
- `loadDevices`에서 권한 없을 때 요청 (line 102)

---

### 2.6 아이콘 색상 통일

**문제**: 키보드 아이콘만 흰색으로 표시되고 다른 아이콘들은 진한 회색

**해결**: `res/drawable/ic_keyboard.xml`

- 색상을 `?attr/colorControlNormal`에서 `#FF000000`(검정)으로 변경
- NavigationView의 자동 tint 적용되도록 수정
- 이제 모든 메뉴 아이콘이 통일된 색상으로 표시됨

---

## 3. 생성된 파일 목록

1. `model/KeyShortcut.kt` - 키보드 단축키 데이터 모델
2. `settings/KeyShortcutManager.kt` - 단축키 관리자
3. `service/KeyEventHandler.kt` - 키 이벤트 처리 핸들러

---

## 4. 수정된 파일 목록

### 핵심 파일
- `app/src/main/AndroidManifest.xml` - 태블릿 제한, 블루투스 권한
- `service/NavBarAccessibilityService.kt` - 키 이벤트 통합
- `overlay/NavBarOverlay.kt` - 패키지 추적
- `core/BackgroundManager.kt` - 앱 설정 배경 제어

### UI 파일
- `ui/SetupActivity.kt` - 블루투스 권한 단계 추가
- `ui/AppSettingsFragment.kt` - 블루투스 권한 상태/요청
- `ui/KeyboardSettingsFragment.kt` - LG KBA10 처리, 권한 요청

### 레이아웃
- `res/layout/fragment_app_settings.xml` - 블루투스 권한 UI

### 리소스
- `res/values/strings.xml` - 블루투스 관련 문자열
- `res/values-ko/strings.xml` - 블루투스 관련 한국어 문자열
- `res/drawable/ic_keyboard.xml` - 아이콘 색상 수정
- `res/xml/accessibility_service_config.xml` - 키 이벤트 필터링 플래그

---

## 5. 테스트 필요 사항

1. **블루투스 권한**: 초기 설정에서 블루투스 권한 요청 및 승인 확인
2. **키보드 감지**: LG KBA10 키보드가 목록에 표시되는지 확인
3. **키보드 단축키**: 단축키 설정 및 실행 테스트 (아직 UI 미구현)
4. **앱 설정 배경**: 앱 설정 화면에서 일반 배경 표시 확인
5. **태블릿 제한**: 스마트폰에서 앱 설치/실행 제한 확인
6. **아이콘 색상**: 메뉴 아이콘 색상 통일 확인

---

## 6. 다음 세션 필수 작업 (Opus에게)

### 우선순위 1: 키보드 단축키 UI 구현

**파일 위치**: `ui/KeyboardShortcutActivity.kt` (신규 생성 필요)

**구현 필요 사항**:
1. 단축키 목록 RecyclerView
   - 각 항목에 "Ctrl + 1" → "설정 앱" 표시
   - 편집/삭제 버튼

2. 단축키 추가 다이얼로그
   - 단계 1: 키 조합 입력 받기 (onKeyDown으로 Ctrl, Shift, 키 감지)
   - 단계 2: 액션 선택 (앱, 바로가기, 설정)
   - 단계 3: 구체적 액션 선택 (앱 선택 또는 설정 종류 선택)

3. KeyboardSettingsFragment에 "단축키 관리" 버튼 추가

**참고 파일**:
- `settings/KeyShortcutManager.kt` - 이미 구현됨
- `model/KeyShortcut.kt` - 이미 구현됨
- `service/KeyEventHandler.kt` - 이미 구현됨

### 우선순위 2: 배터리 잔량 표시

**파일 위치**: `ui/KeyboardSettingsFragment.kt` (수정)

**구현 필요 사항**:
1. BluetoothDevice에서 배터리 정보 가져오기
   ```kotlin
   // API 33 이상
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
       val batteryLevel = device.getBatteryLevel()
   }
   ```

2. 기기 카드에 배터리 UI 추가
   - 배터리 아이콘
   - 퍼센트 표시
   - "충전 중" 표시

### 우선순위 3: 배터리 위젯

**신규 파일 필요**:
1. `widget/KeyboardBatteryWidget.kt` - AppWidgetProvider
2. `res/layout/widget_keyboard_battery.xml` - 위젯 레이아웃
3. `res/xml/keyboard_battery_widget_info.xml` - 위젯 메타데이터

**구현 필요 사항**:
- 키보드 배터리 잔량을 홈 화면에 표시
- 1시간마다 또는 배터리 변경 시 업데이트
- 클릭 시 키보드 설정 열기

### 우선순위 4: 배터리 알림

**파일 위치**: `service/KeyboardBatteryMonitor.kt` (신규 생성)

**구현 필요 사항**:
1. NotificationChannel 생성
2. 배터리 20% 이하 시 알림
3. 설정에서 알림 on/off 옵션

**AndroidManifest 추가 필요**:
```xml
<receiver android:name=".widget.KeyboardBatteryWidget">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data
        android:name="android.appwidget.provider"
        android:resource="@xml/keyboard_battery_widget_info" />
</receiver>
```

---

## 7. 주요 기술적 결정

1. **키보드 단축키 저장**: SharedPreferences + JSON
   - 이유: 간단한 구조, 별도 DB 불필요
   - 대안: Room DB (확장성 필요 시)

2. **블루투스 권한 처리**: Android 12 분기
   - API 31 이상: BLUETOOTH_CONNECT 런타임 권한
   - API 30 이하: 매니페스트 권한만으로 충분

3. **키 이벤트 처리**: AccessibilityService
   - AccessibilityService의 `onKeyEvent` 사용
   - 시스템 전역 키 이벤트 감지 가능

4. **아이콘 색상**: 검정색 벡터 + NavigationView tint
   - 테마에 따라 자동으로 적절한 색상 적용

---

## 8. 코드 품질 및 베스트 프랙티스

- ✅ 모든 문자열 리소스화 (다국어 지원)
- ✅ 권한 체크 및 요청 로직 분리
- ✅ Fragment 생명주기 적절히 활용
- ✅ 싱글톤 패턴 (SettingsManager, KeyShortcutManager)
- ✅ ActivityResultContracts 사용 (deprecated API 회피)
- ✅ 로깅 추가 (디버깅 용이)

---

## 9. 알려진 이슈

없음 (현재까지 확인된 이슈 없음)

---

## 10. 세션 요약

### 실제 완료된 것 (사용자가 볼 수 있는 기능)
- ✅ 블루투스 권한 요청 (초기 설정 4단계, 앱 설정에 상태 표시)
- ✅ 키보드 기기 목록 표시 (LG KBA10 우선 표시)
- ✅ 아이콘 색상 통일
- ✅ 태블릿 전용 제한
- ✅ 앱 설정 화면 배경 비활성화

### 만들어놓았지만 사용자가 못 보는 것
- ⚠️ 키보드 단축키 백엔드 (KeyShortcut.kt, KeyShortcutManager.kt, KeyEventHandler.kt)
  - **문제**: UI가 없어서 사용자가 단축키를 설정할 방법이 없음!
  - **필요한 것**: 단축키 관리 화면, 추가/편집 다이얼로그

### 전혀 안 만든 것
- ❌ 배터리 잔량 표시
- ❌ 배터리 위젯
- ❌ 배터리 알림

---

## 11. Opus에게 전달 사항

**이번 세션의 실수**:
사용자가 요청한 "키보드 단축키"와 "배터리 기능"의 실제 사용 가능한 UI를 만들지 못했습니다. 백엔드 구조만 만들었기 때문에 사용자 입장에서는 아무것도 보이지 않습니다.

**다음 세션 우선순위**:
1. **최우선**: 키보드 단축키 설정 UI (사용자가 Ctrl+1 등을 설정할 수 있는 화면)
2. **우선**: 배터리 잔량 표시 (기기 카드에)
3. **중요**: 배터리 위젯 (홈 화면)
4. **중요**: 배터리 알림 (배터리 부족 시)

위 섹션 6에 구체적인 구현 가이드가 있습니다.

---

**작업 완료 상태**: ⚠️ 부분 완료 (백엔드만 완료, UI 미완성)
**세션 인계 준비 완료**: ✅
