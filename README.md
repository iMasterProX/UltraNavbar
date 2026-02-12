# LG UltraTab Extension

![Guide](guide.png)

[한국어](#한국어) | [English](#english)

---

<p align="center">
  <a href="https://imasterprox.github.io/UltraNavbar/beta-test.html">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/ko_badge_web_generic.png" alt="Google Play에서 다운로드" height="80">
  </a>
</p>

## 한국어

### 개요
LG UltraTab Extension은 LG UltraTab 기기를 위한 종합 확장 앱입니다. 커스텀 네비게이션 바, 블루투스 키보드 단축키, 와콤 펜 버튼 커스텀, 배터리 모니터링 등 다양한 기능으로 태블릿 사용 경험을 개선합니다.

### 주요 기능

#### 커스텀 네비게이션 바
- 기본 네비게이션 버튼: 뒤로, 홈, 최근 앱
- 추가 버튼: 스크린샷 캡처, 알림 패널
- 최근 앱 목록(커스텀 테스크바)를 네비바 중앙에 표시
- 버튼 배치 변경: 추가 버튼(알림/스크린샷)을 좌/우 배치
- 홈 버튼 커스텀: 길게 눌렀을 때 동작 설정(구글 어시스턴트/앱 실행/바로가기)
- 핫스팟 호출: 네비게이션 바 숨김 상태에서 하단 핫스팟으로 다시 표시
- 스타일러스 입력 무시: 펜 사용 중 의도치 않은 네비게이션 바 호출 방지
- 앱별 비활성화: 특정 앱에서는 네비게이션 바 완전 숨김

#### 홈 화면 배경 커스텀
- 자동 크롭 배경: 현재 배경화면의 하단 72px를 자동 크롭하여 네비게이션 바 배경으로 사용
- 방향별 배경: 가로/세로 모드에 각각 다른 배경 적용
- 다크 모드 지원: 다크 모드 전용 배경 이미지 사용
- 버튼 색상 선택: 자동/화이트/블랙 선택
- 미리보기 필터: 적용 전 투명도 조절

#### 블루투스 키보드 지원
- 키보드 자동 감지
- 배터리 레벨 표시: BLE GATT 기반 정확한 배터리 측정(예: LG KBA10)
- 배터리 알림: 임계치 이하 알림 및 상시 배터리 알림 제공
- 홈 화면 위젯: 키보드 배터리 표시
- 커스텀 단축키: 앱 실행, 설정 열기, 바로가기 실행
- 앱별 단축키 비활성화

#### 와콤 펜 버튼 커스텀
- 펜 버튼 A/B 동작 설정
- 지원 동작 유형: 비활성화, 앱 실행, 앱 바로가기 실행, 자동 터치(좌표 기반), 자동 터치(UI 요소 기반, 접근성 서비스 이용)
- 시스템 펜 설정과 동기화
- 특정 앱(OneNote 등)에서 제스처 간섭 방지

#### 네비바앱스 (NavbarApps)
- 네비게이션 바에 즐겨찾기 앱 패널 추가 (최대 10개, 3열 그리드)
- 앱 아이콘을 위로 드래그하여 분할화면 실행

#### 분할화면 실행 (실험적)
- 최근 앱 목록(커스텀 태스크바)에서 앱을 위로 슬라이드하여 분할화면 실행
- 네비바앱스에서 앱 아이콘 드래그로도 분할화면 실행 가능
- 이미 분할화면 상태일 경우, 오른쪽(세로 모드에서는 아래쪽) 패널 앱 교체 시도
- 일부 앱은 시스템 제한으로 왼쪽/전체화면으로 열릴 수 있음
- 분할화면 미지원 앱은 토스트로 안내

#### 시스템 통합
- 접근성 서비스 기반 UI 제어 및 상태 감지
- 배터리 최적화 제외를 통한 안정성 향상
- 블루투스 및 저장소 권한 활용

### 설치 및 사용

#### 요구 사항
- Android 12 이상
- LG UltraTab 기기(다른 태블릿에서도 동작할 수 있으나 보장하지 않음)

#### 초기 설정
1. 앱 설치 후 실행
2. 접근성 서비스 활성화
   - 설정 > 접근성 > 다운로드된 서비스 > UltraNavbar
3. 필요한 권한 허용(블루투스, 저장소 등)
4. 배터리 최적화 제외(안정성 향상)

#### 주요 설정 메뉴
- 네비게이션 바: 배경, 버튼 동작, 앱별 비활성화
- 키보드: 단축키, 배터리 알림
- 와콤 펜: 버튼 A/B 동작 설정
- 앱 설정: 권한 상태 확인

### 문제 해결

- 네비게이션 바가 표시되지 않을 때: 접근성 서비스 활성화 여부, 앱별 비활성화 목록 확인
- 키보드 배터리가 표시되지 않을 때: 블루투스 권한 확인, 키보드 설정의 새로고침 버튼 사용(BLE 캐시 초기화)
- 단축키가 동작하지 않을 때: 접근성 서비스 활성화 여부, 앱별 단축키 비활성화 여부 확인
- 분할화면 실행이 실패할 때: 분할화면 미지원 앱 여부 확인, 시스템 상태(최근앱 패널, 런처) 안정화 후 재시도

### 개발 정보
- 개발자: iMasterProX
- GitHub: https://github.com/iMasterProX/UltraNavbar
- 라이선스: MIT License

**기술 스택**
- Kotlin
- Android Accessibility Service
- Bluetooth Low Energy (BLE) GATT
- Material Design 3

---

<p align="center">
  <a href="https://imasterprox.github.io/UltraNavbar/beta-test.html#en">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80">
  </a>
</p>

## English

### Overview
LG UltraTab Extension is a comprehensive extension app for LG UltraTab devices. It enhances the tablet experience with a custom navigation bar, Bluetooth keyboard shortcuts, Wacom pen button customization, battery monitoring, and more.

### Key Features

#### Custom Navigation Bar
- Core navigation buttons: Back, Home, Recents
- Extra buttons: Screenshot, Notification Panel
- Recent Apps bar
- Button layout swap (left/right for extra buttons)
- Home button long-press customization (Assistant/App/Shortcut)
- Recall hotspot when the bar is hidden
- Ignore stylus input to prevent accidental triggers
- Per‑app disable

#### Home Screen Background Customization
- Auto-crop wallpaper (bottom 72px) as nav bar background
- Orientation-specific backgrounds (portrait/landscape)
- Dark mode background support
- Button color selection (Auto/White/Black)
- Preview opacity before applying

#### Bluetooth Keyboard Support
- Automatic keyboard detection
- Battery level display via BLE GATT (e.g., LG KBA10)
- Battery notifications and persistent status
- Home screen widget for battery level
- Custom shortcuts (launch apps/open settings/run shortcuts)
- Per‑app shortcut disable

#### Wacom Pen Button Customization
- Configure Pen Button A/B actions
- Supported actions
  - Disabled
  - Launch app
  - Run app shortcut
  - Auto touch (coordinate-based)
  - Auto touch (UI element-based via accessibility)
- Sync with system pen settings
- Reduce pen gesture interference in drawing apps

#### NavbarApps
- Favorite apps panel on the navigation bar (up to 10 apps, 3-column grid)
- Drag app icons upward to launch split-screen

#### Split-Screen Launcher (Experimental)
- Slide up an app in the custom recents taskbar to launch split‑screen
- Also launchable by dragging app icons from NavbarApps
- If split‑screen is already active, tries to replace the right (or bottom in portrait) panel
- Some apps may still open on the left or fullscreen due to system constraints
- Shows a toast for apps that do not support split‑screen

#### System Integration
- Accessibility service for UI control and state detection
- Battery optimization exemption for stability
- Bluetooth and storage permissions when needed

### Installation & Usage

#### Requirements
- Android 12 or higher
- LG UltraTab device (may work on other tablets, not guaranteed)

#### Initial Setup
1. Install and launch the app
2. Enable accessibility service
   - Settings > Accessibility > Downloaded Services > UltraNavbar
3. Grant required permissions (Bluetooth, Storage, etc.)
4. Exempt from battery optimization for stability

#### Main Settings
- Navigation Bar: backgrounds, button actions, per‑app disable
- Keyboard: shortcuts and battery notifications
- Wacom Pen: button A/B actions
- App Settings: permission status

### Troubleshooting

- Navigation bar does not appear
  - Verify accessibility service is enabled
  - Check disabled apps list

- Keyboard battery not shown
  - Verify Bluetooth permission
  - Use refresh in keyboard settings (clears BLE cache)

- Shortcuts not working
  - Verify accessibility service is enabled
  - Check per‑app shortcut disable list

- Split‑screen fails to launch
  - Confirm the target app supports split‑screen
  - Retry after the system UI/recents stabilizes

### Development
- Developer: iMasterProX
- GitHub: https://github.com/iMasterProX/UltraNavbar
- License: MIT License

**Tech Stack**
- Kotlin
- Android Accessibility Service
- Bluetooth Low Energy (BLE) GATT
- Material Design 3

---

## Credits

Created by **iMasterProX** with AI-assisted development

Special thanks to the Android open‑source community and all contributors.

---

## License

```
MIT License

Copyright (c) 2025 iMasterProX

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Trademark / 상표권 안내

"LG" 및 LG 로고는 LG Electronics Inc.의 상표입니다. 이 앱은 LG Electronics와 무관하며, 후원/제휴 관계가 아닙니다.

"LG" and the LG logo are trademarks of LG Electronics Inc. This app is not made by, affiliated with, or endorsed by LG Electronics.

---

## Third-Party Licenses / 서드파티 라이선스

이 앱은 다음 오픈소스 라이브러리를 사용합니다 / This app uses the following open-source libraries:

| Library | Copyright | License |
|---|---|---|
| AndroidX Libraries (core-ktx, appcompat, constraintlayout, preference, recyclerview, lifecycle) | The Android Open Source Project | Apache License 2.0 |
| Material Components for Android | Google LLC | Apache License 2.0 |
| Kotlin Coroutines | JetBrains s.r.o. | Apache License 2.0 |

Apache License 2.0 전문: https://www.apache.org/licenses/LICENSE-2.0
