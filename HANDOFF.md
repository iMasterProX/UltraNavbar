# UltraNavbar 인수인계 메모

## 개요
- 목적: 기본 네비바를 대체하는 커스텀 네비바 오버레이.
- 방식: `AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY`로 네비바 레이어 표시/숨김.
- 빌드: Android Studio에서 실행. `gradlew`는 어셈블리/버그 테스트에만 사용.

## 레이어 구조 (앞/뒤 레이어)
- **앞 레이어**: `navBarView` (버튼 + 커스텀 배경 적용 대상).
- **뒤 레이어**: `backgroundView` (기본 네비바 가림용 배경색 레이어).
- 기본 상태: `backgroundView`는 기본 배경색을 유지하며 시스템 네비바를 가림.
- **언락 페이드**: 페이드 끝날 때까지 `backgroundView`를 `GONE`으로 유지해 자연스러운 페이드 연출.

## 주요 파일
- `app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt`
  - 상태 수집/판단, 오버레이 표시 제어.
- `app/src/main/java/com/minsoo/ultranavbar/overlay/NavBarOverlay.kt`
  - 앞/뒤 레이어 구성, show/hide/페이드, 버튼 처리.
- `app/src/main/java/com/minsoo/ultranavbar/core/BackgroundManager.kt`
  - 홈 배경 로딩/전환, 버튼색 계산.
- `app/src/main/java/com/minsoo/ultranavbar/core/ButtonManager.kt`
  - 버튼 생성/색상 갱신/회전 애니메이션.
- `app/src/main/java/com/minsoo/ultranavbar/core/WindowAnalyzer.kt`
  - 홈/리센트/패널/IME/전체화면 상태 분석.

## 주요 기능
- 홈화면 전용 커스텀 배경(가로/세로 분리) + 버튼색 자동/흰색/검은색 옵션.
- 알림패널 버튼:
  - 짧게: 알림패널 열기/닫기, QS 열림 상태면 알림패널로 복귀.
  - 길게: Quick Settings 열기/닫기.
  - 패널 열림 상태에서 180도 회전 유지.
- 전체화면 자동 숨김, 제스처 오버레이/핫스팟 자동 숨김.

## 최근 변경/핵심 수정
- **언락 페이드 뒷레이어 노출 문제 해결**
  - 언락 준비 시 배경 전환 애니메이션으로 기본 배경색이 섞여 보이던 문제를 차단.
  - `BackgroundManager.applyBackground(..., animate=false)` 추가.
  - `NavBarOverlay.prepareForUnlockFade()`에서 `forceUpdate=true, animate=false`로 즉시 배경 적용.
  - `show()`에서 `isUnlockPending/isUnlockFadeRunning` 동안 `backgroundView`는 GONE 유지.
  - 언락 페이드 동안만 `backgroundView`에 커스텀 배경 동기화, 종료 시 기본 배경 복원.
  - 화면 꺼짐 시 언락 상태 리셋(`resetUnlockFadeState()`), 애니메이터 취소 재진입 방지.
- **QS 상태 분리 추적**
  - `WindowAnalyzer`에서 SystemUI 뷰 ID 기반 Quick Settings 감지.
  - `NavBarAccessibilityService`에서 `setPanelStates()`로 알림/퀵설정 상태 분리.
- **알림패널/퀵설정 버튼 회전 중복 방지**
  - 패널 닫힘 디바운스(`PANEL_CLOSE_DEBOUNCE_MS`)로 상태 반전 순간 2회 회전 방지.
  - 동일 회전값이면 애니메이션 생략 처리.
- **방향 동기화**
  - `scheduleStateCheck()`에서 `overlay.ensureOrientationSync()`로 방향 불일치 복구.

## 미해결/주의
- QuickStep 런처의 앱 실행 “로딩 화면”(큰 아이콘 + 흰/검 배경)에서는 배경 전환 감지가 아직 불완전.

## 테스트 기기 정보
- 기기: LG 울트라탭 (ADB 무선 디버깅 연결 사용).
- 참고 명령:
  - 모델: `adb shell getprop ro.product.model`
  - 제조사/버전: `adb shell getprop ro.product.manufacturer`, `adb shell getprop ro.build.version.release`
  - 화면 정보: `adb shell wm size`, `adb shell wm density`

## 빌드/테스트 방식
- 빌드: Android Studio에서 실행.
- `gradlew`는 어셈블리/버그 테스트시에만 사용.
- 기본 점검 시나리오:
  1) 홈 배경/버튼색 자동 전환 확인
  2) 가로/세로 전환 시 배경 맞춤 확인
  3) 전체화면 진입/이탈 시 오버레이 자동 숨김 확인
  4) 알림패널/Quick Settings 전환 및 버튼 회전 확인
  5) 화면 끄기 → 잠금 해제 시 언락 페이드에서 뒷레이어 노출 없는지 확인

## 로그 태그
- `NavBarAccessibility`, `NavBarOverlay`, `WindowAnalyzer`, `BackgroundManager`, `ButtonManager`
  - 예: `adb logcat -s NavBarAccessibility WindowAnalyzer NavBarOverlay`

## 작업 일지
- 2026-01-16
  - 홈 이탈 직후 커스텀 배경 과등장을 막기 위해 홈 이탈 억제 창(`HOME_STATE_DEBOUNCE_MS`) 도입.
  - `NavBarOverlay`에서 억제 시간 동안 기본 배경 강제, 억제 종료 시 즉시 배경 복구.
  - 배경 전환 중 목표가 바뀐 경우 즉시 적용으로 페이드 섞임 최소화.
  - `applyBackgroundImmediate()`에 방향 동기화 추가(세로/가로 혼선 방지).
  - `NavBarAccessibilityService`에 최근 비런처 이벤트 추적으로 로딩/앱 시작 구간 홈 판정 억제.
  - Kotlin 컴파일 오류(자기 참조 `task`)를 `Runnable` 객체 방식으로 수정.
