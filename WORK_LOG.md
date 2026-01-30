# UltraNavbar 작업 로그

> **최종 업데이트**: 2026-01-30
> **현재 버전**: 0.2.1
> **브랜치**: main

---

## 세션 작업 내역 (2026-01-30)

### 1. 키보드 설정 기능 개선

#### 1.1 키보드 단축키 온오프 토글
- **SettingsManager.kt**: `keyboardShortcutsEnabled` 속성 추가
- **KeyEventHandler.kt**: 설정 확인 후 비활성화 시 이벤트 무시
- **fragment_keyboard_settings.xml**: 단축키 관리 카드에 토글 스위치 추가
- **KeyboardSettingsFragment.kt**: 토글 처리 로직 구현

#### 1.2 블루투스 키보드 상태 메시지 개선
- LG KBA10 미등록 시 경고 메시지 표시
  - "LG UltraTab 10A30Q용 블루투스 키보드 악세사리 LG KBA10이 기기에 등록되지 않았습니다..."
- 서드파티 키보드 감지 시 확인 다이얼로그
  - "이 기기는 서드파티 블루투스 키보드입니다. 사용하시겠습니까?"
- 연결 상태 표시 구분: "연결됨" (녹색) / "페어링됨" (회색)
- 서드파티 키보드: 기기명 뒤에 "(Third-Party)" 표시

#### 1.3 관련 파일
- `SettingsManager.kt` - `thirdPartyKeyboardAccepted` 속성 추가
- `KeyboardSettingsFragment.kt` - UI 로직 전면 개편
- `fragment_keyboard_settings.xml` - LG KBA10 안내 영역 추가
- `drawable/warning_background.xml` - 경고 메시지 배경 (신규)
- `values/strings.xml`, `values-ko/strings.xml` - 문자열 추가

---

### 2. Wacom AES 펜 설정 분석

#### 2.1 펜 하드웨어 정보 (himax-stylus)
```
Device: /dev/input/event3
Name: himax-stylus
Features:
- Pressure: 0-4095 (12-bit)
- Tilt X/Y: -60° ~ +60°
- BTN_TOOL_RUBBER (지우개)
- BTN_TOUCH, BTN_DIGI
- KEY_BATTERY, KEY_BLUETOOTH
```

#### 2.2 제어 가능한 설정 (Settings.Global)
| 설정 키 | 타입 | 설명 |
|---------|------|------|
| `pen_pointer` | int (0/1) | 펜 포인터 표시 여부 |
| `ignore_navigation_bar_gestures` | int (0/1) | 필기 시 네비바 제스처 무시 |
| `a_button_setting` | int (0/1) | 펜 버튼 A 단축키 활성화 |
| `b_button_setting` | int (0/1) | 펜 버튼 B 단축키 활성화 |
| `a_button_component_name` | String | 버튼 A 실행 앱 (ComponentName) |
| `b_button_component_name` | String | 버튼 B 실행 앱 (ComponentName) |

#### 2.3 앱에서 접근하기 위한 요구사항
1. **AndroidManifest.xml에 권한 추가**:
   ```xml
   <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
       tools:ignore="ProtectedPermissions" />
   ```
2. **ADB로 권한 부여** (일회성, 재설치 시 필요):
   ```bash
   adb shell pm grant com.minsoo.ultranavbar android.permission.WRITE_SECURE_SETTINGS
   ```

#### 2.4 구현 가능한 기능
- 펜 포인터 표시 토글
- 필기 시 네비바 제스처 무시 토글
- 펜 버튼 A/B 앱 바로가기 설정 (앱 선택기)
- 기존 설정 앱의 펜 설정 미러링/확장

> **상세 구현 가이드**: `PEN_SETTINGS_IMPLEMENTATION.md` 참조

---

### 3. 네비게이션 버튼 배치 반전 기능 (Android 12L 스타일)

#### 3.1 기능 설명
- **기본 배치**: 왼쪽(뒤로/홈/최근앱), 오른쪽(캡처/알림)
- **반전 배치**: 왼쪽(캡처/알림), 오른쪽(뒤로/홈/최근앱)

#### 3.2 관련 파일
- `SettingsManager.kt` - `navButtonsSwapped` 속성 추가
- `NavBarOverlay.kt` - `createNavBar()`에서 버튼 그룹 위치 반전 로직
- `fragment_navbar_settings.xml` - 토글 UI 추가
- `NavBarSettingsFragment.kt` - 토글 처리 로직
- `values/strings.xml`, `values-ko/strings.xml` - 문자열 추가

---

## 코드 구조 참고

### 주요 디렉토리
```
app/src/main/java/com/minsoo/ultranavbar/
├── MainActivity.kt              ← 네비게이션 셸
├── UltraNavbarApplication.kt    ← Application (DynamicColors)
├── ui/
│   ├── NavBarSettingsFragment.kt   ← 네비바 설정
│   ├── AppSettingsFragment.kt      ← 앱 설정
│   ├── KeyboardSettingsFragment.kt ← 키보드 설정
│   ├── WacomPenSettingsFragment.kt ← 와콤 펜 (플레이스홀더)
│   ├── SetupActivity.kt           ← 최초 설정 마법사
│   ├── KeyboardShortcutActivity.kt ← 단축키 관리
│   └── AppListActivity.kt         ← 앱 선택 화면
├── service/
│   ├── NavBarAccessibilityService.kt ← 접근성 서비스
│   ├── KeyEventHandler.kt           ← 키보드 이벤트 핸들러
│   └── KeyboardBatteryMonitor.kt    ← 키보드 배터리 모니터
├── settings/
│   ├── SettingsManager.kt         ← 설정 관리
│   └── KeyShortcutManager.kt     ← 단축키 관리
├── model/
│   └── KeyShortcut.kt            ← 단축키 모델
├── core/
│   ├── BackgroundManager.kt      ← 배경 관리
│   ├── ButtonManager.kt          ← 버튼 관리
│   ├── GestureHandler.kt         ← 제스처 처리
│   └── Constants.kt              ← 상수
├── overlay/
│   └── NavBarOverlay.kt          ← 오버레이
└── util/
    ├── BluetoothUtils.kt         ← 블루투스 유틸리티
    └── BleGattBatteryReader.kt   ← BLE 배터리 읽기
```

### 설정 키 목록 (SettingsManager)
| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `navbar_enabled` | Boolean | true | 커스텀 네비바 활성화 |
| `nav_buttons_swapped` | Boolean | false | 버튼 배치 반전 |
| `hotspot_enabled` | Boolean | true | 재호출 핫스팟 |
| `home_bg_enabled` | Boolean | false | 홈 배경 사용 |
| `keyboard_shortcuts_enabled` | Boolean | true | 키보드 단축키 활성화 |
| `third_party_keyboard_accepted` | Boolean | false | 서드파티 키보드 동의 |

---

## 향후 작업 지침

### 코드 수정 시 주의사항
1. **버그 픽스 영역 보호**
   - `BackgroundManager.kt:419-426` - buttonColorAnimationTarget 순서 (이중 fade 방지)
   - `NavBarAccessibilityService.kt:36,376-398` - HOME_EXIT_STABILIZE_MS 및 홈 재진입 억제

2. **빌드 검증**
   - Android SDK 환경에서 `./gradlew assembleDebug` 실행 권장
   - 이 환경에는 Android SDK가 없어 빌드 검증 불가

3. **문자열 동기화**
   - `values/strings.xml` (영어)와 `values-ko/strings.xml` (한국어) 항상 동기화

### 기능 추가 패턴
1. `SettingsManager.kt`에 설정 키/속성 추가
2. 관련 Fragment/Activity에서 UI 초기화 및 리스너 설정
3. 실제 기능 로직 구현 (Overlay, Service 등)
4. strings.xml (EN/KO) 문자열 추가
5. 필요시 레이아웃 XML 수정

### 펜 설정 구현 시 참고사항

1. **Settings.Global 읽기/쓰기**:
   ```kotlin
   // 읽기
   val value = Settings.Global.getInt(contentResolver, "pen_pointer", 0)

   // 쓰기 (WRITE_SECURE_SETTINGS 권한 필요)
   Settings.Global.putInt(contentResolver, "pen_pointer", 1)
   ```

2. **권한 확인**:
   ```kotlin
   // 권한 없으면 ADB 명령어 안내
   try {
       Settings.Global.putInt(contentResolver, "pen_pointer", value)
   } catch (e: SecurityException) {
       // "adb shell pm grant ... 명령어 실행 필요" 안내
   }
   ```

3. **펜 버튼 앱 설정**:
   ```kotlin
   // ComponentName 형식: "com.example.app/.MainActivity"
   val componentName = ComponentName(packageName, activityName).flattenToString()
   Settings.Global.putString(contentResolver, "a_button_component_name", componentName)
   ```

### 테스트 체크리스트
- [ ] 설정 토글 동작 확인
- [ ] 설정 저장/복원 확인
- [ ] 오버레이 즉시 반영 확인
- [ ] 다크 모드 전환 시 정상 동작
- [ ] 방향 전환 시 정상 동작

---

## 변경 이력

| 날짜 | 버전 | 내용 |
|------|------|------|
| 2026-01-30 | 0.2.1+ | Wacom AES 펜 설정 분석 (Settings.Global 키 확인, 권한 요구사항 문서화) |
| 2026-01-30 | 0.2.1 | 키보드 단축키 토글, 블루투스 상태 메시지 개선, 버튼 배치 반전 기능 |
| 2026-01-28 | 0.2.0 | UI 개편, 키보드 단축키 백엔드, 블루투스 기기 감지 |
| 이전 | 0.1.x | 초기 개발 |
