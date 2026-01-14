# UltraNavbar 인수인계 메모

## 개요
- 목적: 기본 네비바를 대체하는 커스텀 네비바 오버레이 앱
- 동작 방식: AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY 윈도우로 네비바 오버레이 표시/숨김
- 빌드: Android Studio에서만 수행, `gradlew`는 어셈블리/버그 테스트 때만 사용

## 프로젝트 구조 (주요 파일)
- `app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt`
  - 접근성 서비스, 상태 수집/판단, 오버레이 제어 총괄
- `app/src/main/java/com/minsoo/ultranavbar/overlay/NavBarOverlay.kt`
  - 오버레이 뷰 생성/표시/숨김, 애니메이션, 버튼 클릭/롱클릭 처리
- `app/src/main/java/com/minsoo/ultranavbar/core/BackgroundManager.kt`
  - 커스텀 배경 로드/전환, 버튼 색상 계산, 배경 전환 애니메이션
- `app/src/main/java/com/minsoo/ultranavbar/core/ButtonManager.kt`
  - 버튼 생성/색상 갱신/회전 애니메이션
- `app/src/main/java/com/minsoo/ultranavbar/core/WindowAnalyzer.kt`
  - 전체화면/IME/알림패널/리센트 감지 로직
- `app/src/main/java/com/minsoo/ultranavbar/settings/SettingsManager.kt`
  - 앱 설정 관리 (홈 배경 사용/버튼 색상 모드 등)
- `app/src/main/java/com/minsoo/ultranavbar/MainActivity.kt`
  - 설정 UI

## 주요 기능
- 홈 화면 전용 커스텀 배경 (세로/가로 별도 이미지)
- 홈 배경 버튼 색상 자동/흰색/검정색 선택 옵션
- 알림패널 버튼:
  - 짧게: 알림패널 열기/닫기, QS가 열려 있으면 알림패널로 복귀
  - 길게: Quick Settings 열기/닫기
  - 열림 상태일 때 버튼 180도 회전
- 전체화면 자동 숨김/제스처 표시/핫스팟 표시

## 최근 변경 요약
- 전체화면 판정: API 30+에서는 WindowInsets 기반 우선 판정, 하위는 SystemUI 윈도우 히ュー리스틱
- 세로/가로 배경 불일치 재발 방지: `scheduleStateCheck()`에서 `overlay.ensureOrientationSync()` 호출
- 리센트 감지 개선: `overview_panel` 뷰 ID 기반 감지 추가
- 알림패널/Quick Settings 상태 분리 추적 및 UI 동작 분기
- 버튼 회전 애니메이션에 `withLayer()` 적용 (패널/뒤로 버튼)

## 알려진 미해결 이슈
- QuickStep 런처의 “앱 실행 로딩 화면(큰 아이콘 + 단색 배경)”에서 홈 배경 전환이 잘 감지되지 않음
  - 관련 시도 코드 삭제됨 (추후 소스/로그 확보 후 재시도 필요)

## 하드웨어 가속/퍼포먼스 메모
- 오버레이 show/hide와 페이드 애니메이션은 이미 하드웨어 레이어 적용
- 버튼 회전 애니메이션만 `withLayer()` 사용
- 버튼 색상 애니메이션에 하드웨어 레이어 적용 시 렉이 늘어나서 제거함

## 테스트 기기 정보
- 기기: LG 울트라탭 (유선/무선 ADB 디버깅 사용)
- 확인 필요 정보 (필요 시 아래 명령으로 갱신):
  - 모델명: `adb shell getprop ro.product.model`
  - 제조사/OS: `adb shell getprop ro.product.manufacturer`, `adb shell getprop ro.build.version.release`
  - 화면 해상도/밀도: `adb shell wm size`, `adb shell wm density`

## 빌드/테스트 방식
- 빌드: Android Studio에서 실행
- `gradlew`는 어셈블리/버그 테스트 때만 사용
- 기본 검증 시나리오:
  1) 홈 화면에서 커스텀 배경/버튼 색상 적용 확인
  2) 세로/가로 전환 후 배경 맞춤 확인
  3) 전체화면 앱 진입/이탈 시 오버레이 자동 숨김 확인
  4) 리센트/알림패널/Quick Settings 전환 시 배경/버튼 회전 확인

## 디버깅 로그 태그
- `NavBarAccessibility`, `NavBarOverlay`, `WindowAnalyzer`, `BackgroundManager`, `ButtonManager`
  - 예: `adb logcat -s NavBarAccessibility WindowAnalyzer NavBarOverlay`
