# LG UltraTab Extension

![Guide](guide.png)

[한국어](#한국어) | [English](#english)

---

## 한국어

### 개요
LG UltraTab Extension은 LG UltraTab 기기를 위한 종합 확장 기능 앱입니다. 커스텀 네비게이션 바, 키보드 단축키, 배터리 모니터링 등 태블릿 사용 경험을 향상시키는 다양한 기능을 제공합니다.

### 주요 기능

#### 🎯 커스텀 네비게이션 바
- **기본 네비게이션 버튼**: 뒤로가기, 홈, 최근 앱, 알림 패널
- **홈 버튼 커스터마이징**: 길게 누르기 동작 설정 (Google 어시스턴트/앱 실행/바로가기)
- **재호출 핫스팟**: 네비게이션 바가 숨겨져도 하단 핫스팟으로 다시 불러오기
- **스타일러스 입력 무시**: 펜 사용 시 네비게이션 바 오작동 방지
- **앱별 비활성화**: 특정 앱에서 네비게이션 바 완전히 숨기기

#### 🎨 홈 화면 배경 커스터마이징
- **배경화면 자동 크롭**: 현재 배경화면을 수동으로 캡처해 적용해주면 하단 72px를 자동으로 잘라 네비게이션 바 배경으로 사용
- **화면 방향별 배경**: 가로/세로 모드에 각각 다른 배경 이미지 설정
- **다크 모드 지원**: 다크 모드 활성화 시 별도의 배경 이미지 사용 가능
- **버튼 색상 선택**: 홈 배경에서 버튼 색상 자동/흰색/검정 선택
- **미리보기 필터**: 배경 적용 전 불투명도 조절하여 미리보기

#### ⌨️ 블루투스 키보드 지원
- **키보드 연결 감지**: 연결된 블루투스 키보드 자동 인식
- **배터리 잔량 표시**: BLE GATT를 통한 정확한 배터리 레벨 읽기 (LG KBA10 지원)
- **배터리 알림**: 키보드 배터리가 설정값 이하로 떨어지면 알림, 상시 배터리 잔량 알림 기능도 제공
- **홈 화면 위젯**: 키보드 배터리 잔량을 홈 화면에 표시
- **커스텀 단축키**: 키 조합으로 앱 실행, 설정 열기, 바로가기 실행
  - 예: Ctrl + 1 → Chrome 실행
  - Ctrl + Shift + W → Wi-Fi 설정 열기
- **앱별 단축키 비활성화**: 특정 앱(게임 등)에서 키보드 단축키 비활성화

#### 🖊️ 와콤 펜 설정
- **시스템 펜 설정 바로가기**: 편리한 기능 메뉴(Extensions Settings)로 바로 이동
- 펜 감도, 버튼 동작 등 와콤 펜 기능 설정

#### ⚙️ 시스템 통합
- **접근성 서비스**: 네비게이션 바 표시 및 윈도우 상태 감지
- **배터리 최적화 제외**: 서비스 안정성을 위한 백그라운드 실행 보장
- **블루투스 권한**: 키보드 감지 및 배터리 정보 읽기
- **저장소 접근**: 배경화면 자동 생성

### 설치 및 사용

#### 요구사항
- Android 12 이상
- LG UltraTab 기기 (다른 태블릿에서도 작동 가능하지만 보장은 안합니다)

#### 초기 설정
1. 앱 설치 후 실행
2. 접근성 서비스 활성화 (설정 > 접근성 > 다운로드한 서비스 > UltraNavbar)
3. 필요한 권한 허용 (블루투스, 저장소 등)
4. 배터리 최적화에서 제외 (안정성 향상)

#### 주요 설정

**네비게이션 바**
- `네비게이션 바` 탭에서 홈 배경, 버튼 동작, 비활성화 앱 설정

**키보드**
- `키보드` 탭에서 연결된 키보드 확인, 단축키 관리, 배터리 알림 설정

**와콤 펜**
- `와콤 펜` 탭에서 시스템 펜 설정으로 바로 이동

**앱 설정**
- `앱 설정` 탭에서 권한 상태 확인

### 문제 해결

**네비게이션 바가 표시되지 않을 때**
- 접근성 서비스가 활성화되어 있는지 확인
- 현재 앱이 비활성화 앱 목록에 있는지 확인

**키보드 배터리가 표시되지 않을 때**
- 블루투스 권한이 허용되어 있는지 확인
- 키보드 설정에서 "새로고침" 버튼 클릭 (BLE 캐시 클리어)
- BLE 전용 키보드(LG KBA10)는 GATT 연결에 시간이 걸릴 수 있음

**키보드 단축키가 작동하지 않을 때**
- 접근성 서비스가 활성화되어 있는지 확인
- 현재 앱이 단축키 비활성화 앱 목록에 있는지 확인
- 키 조합이 중복되지 않았는지 확인

### 개발 정보

**개발자**: iMasterProX
**GitHub**: https://github.com/iMasterProX/UltraNavbar
**라이선스**: MIT License

**기술 스택**
- Kotlin
- Android Accessibility Service
- Bluetooth Low Energy (BLE) GATT
- Material Design 3

**기여하기**
버그 리포트, 기능 제안, 코드 기여를 환영합니다! GitHub Issues를 통해 참여해주세요.

---

## English

### Overview
LG UltraTab Extension is a comprehensive extension app for LG UltraTab devices. It enhances the tablet experience with features like custom navigation bar, keyboard shortcuts, battery monitoring, and more.

### Key Features

#### 🎯 Custom Navigation Bar
- **Core Navigation Buttons**: Back, Home, Recents, Notification Panel
- **Home Button Customization**: Configure long-press action (Google Assistant/Launch App/Shortcut)
- **Recall Hotspot**: Bring back the navigation bar using a bottom hotspot when hidden
- **Ignore Stylus Input**: Prevent accidental navigation bar triggers when using the pen
- **Per-App Disable**: Completely hide the navigation bar in specific apps

#### 🎨 Home Screen Background Customization
- **Auto-Crop Wallpaper**: Automatically crop the bottom 72px of your current wallpaper as navigation bar background
- **Orientation-Specific Backgrounds**: Set different background images for landscape/portrait modes
- **Dark Mode Support**: Use separate background images when dark mode is enabled
- **Button Color Selection**: Choose button color for home background (Auto/White/Black)
- **Preview Filter**: Adjust opacity before applying background

#### ⌨️ Bluetooth Keyboard Support
- **Keyboard Detection**: Automatically recognize connected Bluetooth keyboards
- **Battery Level Display**: Accurate battery reading via BLE GATT (supports LG KBA10)
- **Battery Notifications**: Alert when keyboard battery drops below threshold
- **Home Screen Widget**: Display keyboard battery level on home screen
- **Custom Shortcuts**: Execute apps, open settings, or run shortcuts with key combinations
  - Example: Ctrl + 1 → Launch Chrome
  - Ctrl + Shift + W → Open Wi-Fi settings
- **Per-App Shortcut Disable**: Disable keyboard shortcuts in specific apps (e.g., games)

#### 🖊️ Wacom Pen Settings
- **System Pen Settings Shortcut**: Direct access to Extensions Settings menu
- button actions, and other Wacom pen features

#### ⚙️ System Integration
- **Accessibility Service**: Display navigation bar and detect window states
- **Battery Optimization Exemption**: Ensure service reliability with background execution
- **Bluetooth Permission**: Detect keyboards and read battery information
- **Storage Access**: Auto-generate wallpaper backgrounds

### Installation & Usage

#### Requirements
- Android 12 or higher
- LG UltraTab device (maybe works on other tablets too)

#### Initial Setup
1. Install and launch the app
2. Enable accessibility service (Settings > Accessibility > Downloaded Services > UltraNavbar)
3. Grant required permissions (Bluetooth, Storage, etc.)
4. Exclude from battery optimization (improves stability)

#### Main Settings

**Navigation Bar**
- Configure home background, button actions, and disabled apps in the `Navigation Bar` tab

**Keyboard**
- Check connected keyboards, manage shortcuts, and configure battery notifications in the `Keyboard` tab

**Wacom Pen**
- Quick access to system pen settings in the `Wacom Pen` tab

**App Settings**
- Check permission status in the `App Settings` tab

### Troubleshooting

**When the navigation bar doesn't appear**
- Verify that accessibility service is enabled
- Check if the current app is in the disabled apps list

**When keyboard battery doesn't show**
- Verify that Bluetooth permission is granted
- Click the "Refresh" button in keyboard settings (clears BLE cache)
- BLE-only keyboards (like LG KBA10) may take time to establish GATT connection

**When keyboard shortcuts don't work**
- Verify that accessibility service is enabled
- Check if the current app is in the shortcut disabled apps list
- Ensure there are no duplicate key combinations

### Development

**Developer**: iMasterProX
**GitHub**: https://github.com/iMasterProX/UltraNavbar
**License**: MIT License

**Tech Stack**
- Kotlin
- Android Accessibility Service
- Bluetooth Low Energy (BLE) GATT
- Material Design 3

**Contributing**
Bug reports, feature suggestions, and code contributions are welcome! Please participate through GitHub Issues.

---

## Credits

Created by **iMasterProX** with **Vibe Coding** (AI-assisted development)

Special thanks to the Android open-source community and all contributors.

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
