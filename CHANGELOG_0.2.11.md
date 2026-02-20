# UltraNavbar v0.2.11 Changelog

> **[한국어](#한국어)** | **[English](#english)**

---

## 한국어

### 펜 버튼 자동 터치 안정화 (A/B 공통)
- 펜 버튼 롱프레스 판별 로직을 press-id 기반으로 재작성해, 같은 누름에서 브릿지 Activity가 중복 실행되더라도 1회만 처리되도록 개선
- 롱프레스 재설정 임계값을 약 2초(2000ms)로 상향 조정
- 롱프레스 시 재설정 화면으로 즉시 진입하도록 개선
  - UI 요소 기반: 요소 선택 오버레이로 직진입
  - 좌표 기반: 좌표 선택 오버레이로 직진입
- 초단 펄스 노이즈 필터 및 릴리즈 대기 보정으로 장시간 사용 시 인식 저하/오동작 완화
- 펜 버튼 키 이벤트가 키보드 단축키 처리 경로에 간섭되지 않도록 이벤트 전달 경로 정리

### 자동 터치/노드 클릭 동작 개선
- NODE_CLICK 단일 누름 처리에서 첫 시도는 즉시 실행하고, 재시도만 지연 적용해 연속 입력 안정성 향상
- 세션 만료/중복 처리 로직을 정리해 연타 중 이전 세션이 현재 입력을 취소하는 케이스 완화

### 펜 설정 가이드 문구 업데이트 (한/영)
- UI 요소 기반/좌표 기반 가이드 상세 설명에
  - "할당된 펜 버튼을 약 2초 이상 길게 누르면 즉시 재설정 화면이 열린다" 안내 추가

### 네비게이션 버튼 배치/설정 정리
- "버튼 배치 반전" 기본값을 활성화(ON)로 변경 (신규 설치 기준)
- 버튼 배치 반전 설명 문구를 현재 순서에 맞게 수정
  - 추가 버튼 순서: 캡처 -> 앱 즐겨찾기 -> 알림패널
- 실제 우측 기본 버튼 순서도 동일 기준으로 정리

### 앱 즐겨찾기/작업표시줄/배경/분할화면 안정화
- 앱 즐겨찾기 패널 표시/숨김 애니메이션을 제거하고 즉시 전환으로 단순화
- 앱 목록 RecyclerView 재바인딩 시 체크박스 선택 상태가 어긋나는 문제 수정
- 최근 앱 작업표시줄 표시 조건(홈/리센트/IME/전환 타이밍)과 진입/퇴장 애니메이션 안정화
- 비활성화 앱 전환 후 홈 복귀 시 커스텀 배경 복원 로직 개선
- 분할화면 실행 안정성 강화 및 I/O 시스템 에러 전용 안내 토스트(ko/en) 추가

---

## English

### Pen Button Auto Touch Stability (A/B)
- Reworked long-press detection with press-id tracking so duplicated bridge launches during one physical press are handled exactly once
- Increased the long-press reconfigure threshold to about 2 seconds (2000ms)
- Long-press now opens reconfiguration immediately
  - UI Element mode: direct entry to the element selection overlay
  - Coordinate mode: direct entry to the coordinate selection overlay
- Added pulse-noise filtering and release-wait guards to reduce degraded recognition during long sessions
- Adjusted key-event routing so pen-button events do not interfere with keyboard-shortcut handling

### Auto Touch / Node Click Improvements
- For NODE_CLICK single-press handling, the first attempt now runs immediately while only retries are delayed
- Refined session expiration/duplicate handling to reduce cancellation of current input during rapid presses

### Pen Setup Guide Text Updates (KR/EN)
- Added guidance in both UI-element and coordinate guides that long-pressing the assigned pen button for about 2 seconds immediately reopens setup

### Navigation Button Layout / Defaults
- Changed the default for "Swap Button Layout" to enabled (for fresh installs)
- Updated swap-description text to match the current order
  - Extra buttons: Screenshot -> App Favorites -> Notification Panel
- Synced right-side default ordering with the same sequence

### App Favorites / Taskbar / Background / Split-Screen Stability
- Simplified App Favorites panel show/hide behavior to immediate transitions (animation removed)
- Fixed checkbox selection mismatch during RecyclerView rebind in app lists
- Stabilized recent-apps taskbar visibility rules (home/recents/IME/transition timing) and entry/exit animations
- Improved custom-background recovery when returning home after disabled-app transitions
- Hardened split-screen launch flow and added a dedicated I/O system error toast (ko/en)
