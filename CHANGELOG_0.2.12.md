# UltraNavbar v0.2.12 Changelog

> **[한국어](#한국어)** | **[English](#english)**

---

## 한국어

### 분할화면 실행 방식 개선
- 최근 앱 작업표시줄/앱 즐겨찾기 드래그 분할화면 실행 흐름을 안정화하여 성공률을 개선
- 드래그한 앱이 의도한 보조 영역(가로: 오른쪽, 세로: 아래)에 배치되도록 동작 보정
- 홈 화면에서는 분할화면 드래그가 비활성화되도록 정리

### 최근 앱 작업표시줄 + 앱 즐겨찾기 아이콘 모양 커스텀 추가
- 아이콘 모양 선택 기능 추가: 원형, 네모(기본값), 스퀘어클, 둥근 사각형
- 선택한 모양이 최근 앱 작업표시줄 아이콘, 앱 즐겨찾기 창 아이콘, 드래그 중 아이콘 미리보기에 동일 적용되도록 개선

### 펜 버튼 자동터치 재설정 진입 개선
- UI 요소 기반/좌표 기반 자동터치 모두에서, 할당된 펜 버튼을 약 0.75초 길게 누르면 재설정 화면이 즉시 열리도록 개선
- 관련 설정 안내 문구(한/영)를 0.75초 기준으로 업데이트

---

## English

### Split-Screen Launch Flow Improvements
- Stabilized drag-to-split execution from both the recent-apps taskbar and the App Favorites panel for better reliability
- Corrected pane placement so the dragged app lands in the intended secondary area (landscape: right, portrait: bottom)
- Disabled split-screen drag while on the home screen

### Icon Shape Customization for Taskbar + App Favorites
- Added icon shape options: Circle, Square (Default), Squircle, and Rounded Rect
- Applied the selected shape consistently to recent-apps taskbar icons, App Favorites panel icons, and drag preview icons

### Faster Pen Auto-Touch Reconfigure Entry
- For both UI-element and coordinate auto-touch modes, long-pressing the assigned pen button for about 0.75 seconds now opens reconfiguration immediately
- Updated related KR/EN guide text to reflect the 0.75-second threshold
