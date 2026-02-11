# UltraNavbar v0.2.8 Changelog

> **[한국어](#한국어)** | **[English](#english)**

---

## 한국어

### 방향 감지 개선
- `configuration.orientation` 및 `maximumWindowMetrics.bounds` 기반 방향 감지가 이 태블릿에서 신뢰할 수 없는 문제 수정
- `display.rotation` + `currentWindowMetrics.bounds` 조합으로 실제 디스플레이 회전 기반 방향 감지로 전환
- `BackgroundManager`, `NavBarOverlay`, `NavBarAccessibilityService` 모두 동일한 방식 적용
- `WindowAnalyzer.getOrientationFromDisplay()` 공용 메서드 추가

### 키보드 배터리 위젯 리디자인
- Material 3 스타일 아이콘으로 전면 교체 (`ic_widget_battery`, `ic_widget_keyboard`, `ic_widget_refresh`)
- 위젯 배경 라운드 처리 (`widget_background.xml`, 24dp radius)
- 배터리 잔량에 따른 색상 표시 (20% 이하 빨강, 50% 이하 주황, 그 외 초록)
- ProgressBar 기반 배터리 바 추가
- 위젯 선택기 미리보기 레이아웃 추가 (`widget_keyboard_battery_preview.xml`)
- 위젯 크기 사양 업데이트 (3x2 셀, `previewLayout` 지원)

### 위젯 새로고침 버튼
- 위젯 우측 하단에 새로고침 버튼 추가
- 버튼 클릭 시 360도 회전 애니메이션 (8단계, 400ms)
- `ACTION_REFRESH` 브로드캐스트 액션 등록

### 블루투스 연결 감지 개선
- `BluetoothDevice.isConnected()` 리플렉션 실패 시 폴백 추가
  - `getBatteryLevel() >= 0` 이면 연결로 판단
  - BLE GATT 캐시에 값이 있으면 연결로 판단

### 비활성화 앱 복구 버그 수정
- 분할화면 종료 후 `currentPackage`가 비활성화 앱에 남아 오버레이가 복구되지 않는 문제 수정
- 분할화면 종료 시 300ms 후 포그라운드 패키지 갱신 및 가시성 재확인
- 비활성화 앱 상태에서 500ms 주기 복구 체크 추가 (`disabledAppRecoveryRunnable`)

### 분할화면 토스트 메시지 통일
- UltraNavbar 자체 앱에서 분할화면 시도 시 "UltraNavbar은(는) 분할화면을 지원하지 않습니다" 형식으로 통일

### 메모리 관리 개선
- `SplitScreenHelper.appLabelCache`: 무제한 Map → LRU 캐시 (최대 50개)로 변경
- `KeyboardBatteryMonitor.lastNotificationTimes`: 만기 항목 자동 정리 추가
- `BackgroundManager.buttonColorAnimator`: cancel 후 null 처리 누락 수정

---

## English

### Orientation Detection Improvements
- Fixed unreliable orientation detection using `configuration.orientation` and `maximumWindowMetrics.bounds` on this tablet
- Switched to `display.rotation` + `currentWindowMetrics.bounds` combination for rotation-based orientation detection
- Applied consistently across `BackgroundManager`, `NavBarOverlay`, and `NavBarAccessibilityService`
- Added public `WindowAnalyzer.getOrientationFromDisplay()` method

### Keyboard Battery Widget Redesign
- Replaced all icons with Material 3 style (`ic_widget_battery`, `ic_widget_keyboard`, `ic_widget_refresh`)
- Rounded widget background (`widget_background.xml`, 24dp radius)
- Color-coded battery level display (red below 20%, orange below 50%, green otherwise)
- Added ProgressBar-based battery bar
- Added widget picker preview layout (`widget_keyboard_battery_preview.xml`)
- Updated widget size specs (3x2 cells, `previewLayout` support)

### Widget Refresh Button
- Added refresh button at the bottom-right corner of the widget
- 360-degree spin animation on tap (8 steps, 400ms)
- Registered `ACTION_REFRESH` broadcast action

### Bluetooth Connection Detection Improvements
- Added fallback methods when `BluetoothDevice.isConnected()` reflection fails
  - Treat as connected if `getBatteryLevel() >= 0`
  - Treat as connected if BLE GATT cache has a value

### Disabled App Overlay Recovery Bug Fix
- Fixed overlay not recovering after split screen ends while `currentPackage` remains set to a disabled app
- Added 300ms delayed foreground package refresh and visibility re-check after split screen exit
- Added 500ms periodic recovery check while in disabled app state (`disabledAppRecoveryRunnable`)

### Split Screen Toast Message Consistency
- Unified toast message format when attempting split screen with UltraNavbar itself to match other apps

### Memory Management Improvements
- `SplitScreenHelper.appLabelCache`: Changed from unbounded Map to LRU cache (max 50 entries)
- `KeyboardBatteryMonitor.lastNotificationTimes`: Added automatic cleanup of expired entries
- `BackgroundManager.buttonColorAnimator`: Fixed missing null assignment after cancel
