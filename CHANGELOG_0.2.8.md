# UltraNavbar v0.2.8 Changelog

## 방향 감지 개선
- `configuration.orientation` 및 `maximumWindowMetrics.bounds` 기반 방향 감지가 이 태블릿에서 신뢰할 수 없는 문제 수정
- `display.rotation` + `currentWindowMetrics.bounds` 조합으로 실제 디스플레이 회전 기반 방향 감지로 전환
- `BackgroundManager`, `NavBarOverlay`, `NavBarAccessibilityService` 모두 동일한 방식 적용
- `WindowAnalyzer.getOrientationFromDisplay()` 공용 메서드 추가

## 키보드 배터리 위젯 리디자인
- Material 3 스타일 아이콘으로 전면 교체 (`ic_widget_battery`, `ic_widget_keyboard`, `ic_widget_refresh`)
- 위젯 배경 라운드 처리 (`widget_background.xml`, 24dp radius)
- 배터리 잔량에 따른 색상 표시 (20% 이하 빨강, 50% 이하 주황, 그 외 초록)
- ProgressBar 기반 배터리 바 추가
- 위젯 선택기 미리보기 레이아웃 추가 (`widget_keyboard_battery_preview.xml`)
- 위젯 크기 사양 업데이트 (3x2 셀, `previewLayout` 지원)

## 위젯 새로고침 버튼
- 위젯 우측 하단에 새로고침 버튼 추가
- 버튼 클릭 시 360도 회전 애니메이션 (8단계, 400ms)
- `ACTION_REFRESH` 브로드캐스트 액션 등록

## 블루투스 연결 감지 개선
- `BluetoothDevice.isConnected()` 리플렉션 실패 시 폴백 추가
  - `getBatteryLevel() >= 0` 이면 연결로 판단
  - BLE GATT 캐시에 값이 있으면 연결로 판단

## 비활성화 앱 복구 버그 수정
- 분할화면 종료 후 `currentPackage`가 비활성화 앱에 남아 오버레이가 복구되지 않는 문제 수정
- 분할화면 종료 시 300ms 후 포그라운드 패키지 갱신 및 가시성 재확인
- 비활성화 앱 상태에서 500ms 주기 복구 체크 추가 (`disabledAppRecoveryRunnable`)

## 분할화면 토스트 메시지 통일
- UltraNavbar 자체 앱에서 분할화면 시도 시 "UltraNavbar은(는) 분할화면을 지원하지 않습니다" 형식으로 통일

## 메모리 관리 개선
- `SplitScreenHelper.appLabelCache`: 무제한 Map → LRU 캐시 (최대 50개)로 변경
- `KeyboardBatteryMonitor.lastNotificationTimes`: 만기 항목 자동 정리 추가
- `BackgroundManager.buttonColorAnimator`: cancel 후 null 처리 누락 수정
