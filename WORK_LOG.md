# UltraNavbar 작업 로그

작업 브랜치: `claude/complete-app-build-f9EjL`

## 완료된 작업

### 1. 간단한 버그 수정 (CONTINUATION.md 기반)
**커밋**: `Fix minor bugs as per CONTINUATION.md`

- ✅ KeyShortcut.toJson() JSONArray 직렬화 오류 수정
  - `modifiers.toList()` → `JSONArray(modifiers.toList())`

- ✅ KeyboardSettingsFragment deprecated API 수정
  - `resources.getColor()` → `ContextCompat.getColor()`

- ✅ 수정 키(Modifier) 정규화 추가
  - KeyEventHandler에 `normalizeModifier()` 함수 추가
  - RIGHT 수정자를 LEFT로 통일 (Ctrl_RIGHT → Ctrl_LEFT)

- ✅ 앱 설정 프래그먼트에 "정보" 타이틀 추가

---

### 2. 키보드 단축키 UI 구현
**커밋**: `Implement keyboard shortcut UI management`

키보드 단축키 백엔드는 이미 구현되어 있었으나 UI가 누락되어 있었음.

#### 추가된 문자열 리소스 (34개, EN/KO)
- 단축키 관리 관련 문자열
- 3단계 마법사 문자열
- 액션 타입 및 설정 메뉴 문자열

#### 추가된 레이아웃 파일 (8개)
1. `activity_keyboard_shortcut.xml` - 메인 액티비티 (RecyclerView + FAB)
2. `item_keyboard_shortcut.xml` - 단축키 목록 아이템
3. `dialog_add_shortcut.xml` - 3단계 마법사 컨테이너
4. `dialog_step1_key_combination.xml` - 키 조합 캡처
5. `dialog_step2_action_type.xml` - 액션 타입 선택
6. `dialog_step3_select_action.xml` - 세부 액션 선택

#### 추가된 Kotlin 클래스 (3개)
1. `KeyboardShortcutActivity.kt` - 단축키 목록 관리
2. `KeyboardShortcutAdapter.kt` - RecyclerView 어댑터
3. `AddShortcutDialog.kt` - 3단계 마법사 다이얼로그
   - `dispatchKeyEvent()` 오버라이드로 키보드 이벤트 캡처
   - 실시간 키 조합 표시

#### 통합
- KeyboardSettingsFragment에 "단축키 관리" 버튼 추가
- AndroidManifest.xml에 KeyboardShortcutActivity 등록

---

### 3. 배터리 잔량 표시 구현
**커밋**: `Add battery level display for Bluetooth keyboards`

#### 기능
- 연결된 블루투스 키보드의 배터리 잔량 표시 (API 33+)
- KeyboardSettingsFragment의 기기 카드에 추가

#### 배터리 레벨 표시
- 색상 코딩:
  - 🔴 빨강: ≤20%
  - 🟠 주황: 21-50%
  - 🟢 초록: >50%
  - ⚫ 회색: 알 수 없음

#### 구현 방식
- `BluetoothDevice.getBatteryLevel()` 사용 (API 33+)
- 리플렉션으로 호출하여 컴파일 호환성 확보

---

### 4. 배터리 위젯 구현
**커밋**: `Implement keyboard battery widget for home screen`

#### 추가된 파일
1. `widget_keyboard_battery.xml` - 위젯 레이아웃
   - 키보드 이름
   - 배터리 퍼센트 (색상 코딩)
   - 마지막 업데이트 시간

2. `keyboard_battery_widget_info.xml` - 위젯 메타데이터
   - 크기: 120x80dp
   - 업데이트 주기: 1시간
   - 크기 조절 가능 (가로/세로)

3. `KeyboardBatteryWidget.kt` - AppWidgetProvider
   - 첫 번째 연결된 키보드 표시
   - 배터리 레벨에 따른 색상 변경
   - 클릭 시 앱 실행

#### 문자열 리소스
- 위젯 설명
- "연결된 키보드 없음"
- "업데이트됨"

#### 등록
- AndroidManifest.xml에 위젯 리시버 등록

---

### 5. 배터리 부족 알림 구현
**커밋**: `Implement battery low notifications for Bluetooth keyboards`

#### 추가된 파일
1. `KeyboardBatteryMonitor.kt` - 배터리 모니터링 서비스
   - `createNotificationChannel()` - 알림 채널 생성 (HIGH 우선순위)
   - `checkBatteryLevels()` - 모든 키보드 배터리 확인
   - `showLowBatteryNotification()` - 알림 표시

#### 기능
- 20% 임계값에서 알림 발송
- 1시간 쿨다운 (중복 알림 방지)
- 사용자 설정으로 활성화/비활성화 가능

#### 통합
- UltraNavbarApplication에서 알림 채널 초기화
- NavBarAccessibilityService에서 1시간마다 배터리 확인
  - `startBatteryMonitoring()` - Handler로 주기적 실행
  - `stopBatteryMonitoring()` - 서비스 종료 시 정리

#### UI
- KeyboardSettingsFragment에 배터리 알림 토글 추가
- SettingsManager에 `batteryNotificationEnabled` 설정 추가

---

### 6. 컴파일 에러 수정
**커밋**: `Fix compilation errors in new features`

#### 수정 사항
1. **JSONArray import 누락** (KeyShortcut.kt)
   - `import org.json.JSONArray` 추가

2. **getBatteryLevel() API 레벨 문제** (3개 파일)
   - KeyboardBatteryMonitor.kt
   - KeyboardBatteryWidget.kt
   - KeyboardSettingsFragment.kt
   - `@Suppress("NewApi")` 추가 (효과 없음)

3. **Dialog 키보드 이벤트 처리** (AddShortcutDialog.kt)
   - Dialog는 `onKeyDown()`/`onKeyUp()` 직접 오버라이드 불가
   - `dispatchKeyEvent()`로 변경하여 KEY_DOWN/KEY_UP 처리

---

### 7. 리플렉션 기반 배터리 레벨 접근
**커밋**: `Use reflection for getBatteryLevel() to fix compilation`

#### 문제
- `BluetoothDevice.getBatteryLevel()` (API 33+)이 Kotlin 컴파일러에서 인식 안됨
- `@Suppress("NewApi")`로도 해결 안됨

#### 해결 방법
- 리플렉션을 사용하여 런타임에 메서드 호출
- 3개 파일에 `getDeviceBatteryLevel()` helper 함수 추가:
  ```kotlin
  private fun getDeviceBatteryLevel(device: BluetoothDevice): Int {
      return try {
          val method = device.javaClass.getMethod("getBatteryLevel")
          method.invoke(device) as? Int ?: -1
      } catch (e: Exception) {
          -1
      }
  }
  ```

---

### 8. UX 개선 및 디버깅
**커밋**: `Improve UX: battery settings, dialog size, icons, and debugging`

#### 1. 배터리 알림 임계값 설정
- 설정 가능한 임계값 추가 (5-50%, 기본 20%)
- SettingsManager에 `batteryLowThreshold` 속성 추가
- KeyboardSettingsFragment에 슬라이더 UI 추가
  - 알림 토글에 따라 표시/숨김
  - 5% 단위로 조절 가능
- KeyboardBatteryMonitor가 설정된 임계값 사용

#### 2. 단축키 다이얼로그 크기 수정
- `dialog_add_shortcut.xml`의 너비 변경
- `match_parent` → `600dp`
- 태블릿 화면에서 적절한 크기로 표시

#### 3. Material Design 아이콘 통일
새로운 아이콘 추가:
- `ic_navigation.xml` - 네비게이션 바 설정
- `ic_stylus.xml` - 와콤 펜 설정
- `ic_settings.xml` - 앱 설정
- `ic_keyboard.xml` - 기존 유지

`settings_nav.xml` 메뉴 업데이트:
- Android 기본 아이콘 → 커스텀 Material 아이콘

#### 4. 키보드 감지 및 디버깅 개선
**isKeyboardDevice() 로직 수정**:
- 변경 전: `majorDeviceClass == 0x500 || (deviceClassCode and 0x40) != 0`
- 변경 후: `isPeripheral && isKeyboard`
  - isPeripheral: `majorDeviceClass == 0x500`
  - isKeyboard: `(deviceClassCode and 0x40) != 0`

**상세한 로깅 추가**:
- 각 블루투스 기기의 클래스 코드 출력
- 키보드 감지 여부 출력
- 배터리 레벨 확인 로그
- 태그: `KeyboardSettings`, `KeyboardBatteryWidget`, `KeyboardBatteryMonitor`

---

## 알려진 버그 및 수정 필요 사항

### 🐛 버그 #1: 권한 상태 메시지 오류
**위치**: 권한 설정 화면

**증상**:
- 이미 권한이 부여된 상태에서 각 권한 메뉴 버튼을 누를 때
- 버튼 종류와 상관없이 모두 동일한 메시지 표시
- "권한이 승인됨, 자동 배경 생성 사용 가능"

**예상 원인**:
- 권한 체크 로직이 특정 권한을 구분하지 못함
- 모든 권한에 대해 동일한 메시지 반환

**수정 필요**:
- 권한별로 적절한 메시지 표시
- 각 권한의 상태를 개별적으로 확인

---

### 🐛 버그 #2: 단축키 키 조합 캡처 실패
**위치**: 키보드 단축키 관리 → 단축키 추가

**증상**:
1. Ctrl + 1을 동시에 누름
2. 키에서 손을 떼면 숫자 1만 남고 Ctrl이 사라짐
3. 둘 다 누른 상태에서 "다음" 버튼 클릭
4. "앱 연결" 또는 "설정 연결" 화면으로 이동
   - "바로가기 연결" 옵션은 표시되지 않음
5. 앱 또는 설정 중 하나 또는 둘 다 선택해도 진행 불가

**예상 원인**:
1. `dispatchKeyEvent()`에서 modifier key 상태 관리 문제
2. KEY_UP 이벤트에서 modifier가 너무 빨리 제거됨
3. ActionType.SHORTCUT이 UI에 표시되지 않음
4. Step 3에서 선택 검증 로직 오류

**수정 필요**:
- Modifier key 상태 관리 개선
- 바로가기 연결 옵션 추가
- Step 3 진행 조건 수정

---

### 🐛 버그 #3: 배터리 기능 작동 안함
**위치**:
- 배터리 알림
- 배터리 위젯
- 키보드 설정의 배터리 잔량 표시

**증상**:
- 배터리 알림이 전혀 발송되지 않음
- 위젯에 "연결된 키보드 없음" 표시
- 실제로는 키보드가 연결되어 있음
- 배터리 잔량 자체를 감지하지 못함

**예상 원인**:
1. `isKeyboardDevice()` 로직이 여전히 부정확
   - Bluetooth device class 판별 실패
2. `getBatteryLevel()` 리플렉션 호출 실패
3. 권한 문제 (BLUETOOTH_CONNECT)
4. API 레벨 체크 문제

**디버깅 방법**:
- Logcat에서 다음 태그로 필터링:
  - `KeyboardSettings`
  - `KeyboardBatteryWidget`
  - `KeyboardBatteryMonitor`
- 로그 확인 항목:
  - "Found X bonded devices"
  - "Device: ... isKeyboard=..."
  - "Battery level: ..."

**수정 필요**:
1. Bluetooth device class 감지 로직 재검토
2. 다양한 키보드 모델 지원
3. 배터리 잔량을 키보드 설정 메뉴에도 표시
4. 권한 체크 강화

---

### 🐛 버그 #4: 홈 배경 자동 생성 해상도 불일치
**위치**: 네비게이션 바 설정 → 홈 배경

**증상**:
- 가로/세로 배경을 Auto로 생성할 수 있는 기능이 있음
- 울트라탭 해상도(2000x1200)에 맞지 않음
- 실제 홈 화면에서의 배경과 불일치

**예상 원인**:
1. 배경 크롭 시 고정 높이(72px) 사용
2. 기기 해상도를 고려하지 않음
3. 가로/세로 모드별 크롭 영역 계산 오류

**관련 파일**:
- `ImageCropUtil.kt`
- `SettingsManager.kt` (CROP_HEIGHT_PX = 72)

**수정 필요**:
1. 기기의 실제 해상도 감지
2. 네비게이션 바 높이를 동적으로 계산
3. 크롭 영역을 해상도에 맞게 조정
4. 실제 홈 화면 배경과 일치하도록 보정

---

## 다음 작업 계획

1. **버그 수정 우선순위**:
   - [ ] #3: 배터리 기능 작동 (최우선)
   - [ ] #2: 단축키 키 조합 캡처
   - [ ] #1: 권한 상태 메시지
   - [ ] #4: 홈 배경 자동 생성

2. **테스트 필요**:
   - [ ] 다양한 블루투스 키보드 모델에서 배터리 감지
   - [ ] 단축키 추가/삭제/실행
   - [ ] 배터리 알림 발송
   - [ ] 위젯 업데이트
   - [ ] 홈 배경 자동 생성

3. **문서화**:
   - [ ] 사용자 매뉴얼
   - [ ] API 문서
   - [ ] 릴리스 노트

---

## 기술 스택

- **언어**: Kotlin
- **최소 API 레벨**: Android 8.0 (API 26)
- **대상 API 레벨**: Android 14 (API 34)
- **UI**: Material Design 3
- **권한**:
  - SYSTEM_ALERT_WINDOW
  - FOREGROUND_SERVICE
  - BLUETOOTH / BLUETOOTH_CONNECT
  - POST_NOTIFICATIONS
  - REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

---

## 커밋 히스토리

1. `Fix minor bugs as per CONTINUATION.md`
2. `Implement keyboard shortcut UI management`
3. `Add battery level display for Bluetooth keyboards`
4. `Implement keyboard battery widget for home screen`
5. `Implement battery low notifications for Bluetooth keyboards`
6. `Fix compilation errors in new features`
7. `Use reflection for getBatteryLevel() to fix compilation`
8. `Improve UX: battery settings, dialog size, icons, and debugging`

---

**마지막 업데이트**: 2026-01-28
**작업자**: Claude Sonnet 4.5
