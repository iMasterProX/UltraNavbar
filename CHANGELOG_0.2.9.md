# UltraNavbar v0.2.9 Changelog

> **[한국어](#한국어)** | **[English](#english)**

---

## 한국어

### 네비바앱스 (NavbarApps) - 신규 기능
- 네비게이션 바에 즐겨찾기 앱 패널 추가 (최대 10개 앱, 3열 그리드)
- 앱 아이콘 길게 눌러 분할화면으로 드래그 가능
- 다크 모드 지원: 패널 배경 자동 전환

### 분할화면 드래그 오버레이 다크 모드
- 분할화면 드래그 시 반투명 오버레이 색상을 모드에 따라 변경
  - 라이트 모드: 검은색 반투명 (흰색 배경 앱에서 잘 보이도록)
  - 다크 모드: 흰색 반투명
- 다크 모드 전환 시 오버레이 색상 자동 업데이트

### 분할화면 버그 수정
- 네비바앱스에서 앱 추가 후 분할화면 시도 시, UltraNavbar 자체가 분할화면에 열리는 버그 수정
  - `NavbarAppsAddActivity`에 별도 `taskAffinity` 적용으로 기존 MainActivity task 오염 방지
  - `excludeFromRecents`, `noHistory` 설정으로 task 잔류 방지
  - split toggle 전 primary 앱을 항상 foreground로 올려 task 스택 최상위 확보

### 네비바앱스 패널 개선
- 홈 화면에서 네비바앱스 열 때 흰색 배경 깜빡임 수정
- 네비바앱스 열 때 태스크바가 잠깐 보이는 현상 수정
- 그리드 레이아웃: weight 기반 균등 열 배분으로 아이콘 정렬 수정

---

## English

### NavbarApps - New Feature
- Added favorite apps panel to the navigation bar (up to 10 apps, 3-column grid)
- Drag app icons to trigger split-screen mode
- Dark mode support: panel background automatically adjusts

### Split-Screen Drag Overlay Dark Mode
- Split-screen drag overlay color now adapts to current mode
  - Light mode: black semi-transparent (better visibility over white app backgrounds)
  - Dark mode: white semi-transparent
- Overlay color auto-updates on dark mode transitions

### Split-Screen Bug Fixes
- Fixed UltraNavbar itself opening in split-screen after adding apps via NavbarApps
  - Applied separate `taskAffinity` to `NavbarAppsAddActivity` to prevent contamination of the main task stack
  - Added `excludeFromRecents` and `noHistory` to prevent residual task records
  - Always bring primary app to foreground before split toggle to ensure correct task stack order

### NavbarApps Panel Improvements
- Fixed white background flash when opening NavbarApps on home screen
- Fixed taskbar briefly appearing when NavbarApps is opened
- Fixed grid layout alignment with weight-based equal column distribution
