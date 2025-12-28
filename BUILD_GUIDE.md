# UltraNavbar 빌드 및 테스트 가이드

## 프로젝트 개요

**UltraNavbar**는 Android 12+ 기기에서 화면 하단에 커스텀 네비게이션 오버레이 바를 표시하는 앱입니다.

### 주요 기능
- 하단 고정 오버레이 바 (Back, Home, Recents, 알림 패널 버튼)
- AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY 사용
- 영상 앱 전체화면 시 자동 숨김
- 앱별 숨김/표시 설정 (블랙리스트/화이트리스트)
- 숨김 시 재호출 핫스팟
- **홈 화면 투명 배경 효과** (스크린샷 하단 72px 크롭)

---

## 프로젝트 구조

```
UltraNavbar/
├── app/
│   ├── src/main/
│   │   ├── java/com/minsoo/ultranavbar/
│   │   │   ├── MainActivity.kt           # 설정 화면
│   │   │   ├── BootReceiver.kt           # 부팅 리시버
│   │   │   ├── model/
│   │   │   │   └── NavAction.kt          # 네비게이션 액션 정의
│   │   │   ├── service/
│   │   │   │   └── NavBarAccessibilityService.kt  # 핵심 서비스
│   │   │   ├── overlay/
│   │   │   │   └── NavBarOverlay.kt      # 오버레이 UI
│   │   │   ├── settings/
│   │   │   │   └── SettingsManager.kt    # 설정 관리
│   │   │   └── ui/
│   │   │       ├── AppListActivity.kt    # 앱 목록 화면
│   │   │       └── AppListAdapter.kt     # 앱 목록 어댑터
│   │   │   └── util/
│   │   │       └── ImageCropUtil.kt      # 이미지 크롭 유틸리티
│   │   ├── res/
│   │   │   ├── drawable-xxhdpi/          # 버튼 이미지
│   │   │   ├── layout/                   # 레이아웃 XML
│   │   │   ├── values/                   # 문자열, 색상, 테마
│   │   │   └── xml/
│   │   │       └── accessibility_service_config.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 빌드 방법

### 1. Android Studio에서 빌드

1. Android Studio에서 프로젝트 폴더 열기
2. Gradle Sync 완료 대기
3. Build > Build Bundle(s) / APK(s) > Build APK(s)
4. `app/build/outputs/apk/debug/app-debug.apk` 생성됨

### 2. 명령줄에서 빌드

```bash
# Windows
gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

### 3. ADB로 설치

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 테스트 방법

### 접근성 서비스 활성화 (ADB)

```bash
# 접근성 서비스 활성화
adb shell settings put secure enabled_accessibility_services com.minsoo.ultranavbar/com.minsoo.ultranavbar.service.NavBarAccessibilityService

# 접근성 활성화 확인
adb shell settings get secure enabled_accessibility_services

# 접근성 서비스 비활성화
adb shell settings put secure enabled_accessibility_services ""
```

### 로그 확인

```bash
# 전체 로그 (NavBar 관련)
adb logcat -s NavBarAccessibility NavBarOverlay

# 포그라운드 앱 변경 확인
adb logcat | grep "Foreground app changed"

# 전체화면/영상 상태 확인
adb logcat | grep -E "(Fullscreen|Video playing)"
```

### 디버그용 ADB 명령어

```bash
# 현재 포그라운드 앱 확인
adb shell dumpsys activity activities | grep mResumedActivity

# 오버레이 윈도우 목록
adb shell dumpsys window windows | grep -E "Window #|mOwnerPackage"

# 접근성 서비스 상태
adb shell dumpsys accessibility
```

---

## 상태 전환 로직

```
[앱 시작]
    ↓
[접근성 서비스 연결] → onServiceConnected()
    ↓
[오버레이 생성] → NavBarOverlay.create()
    ↓
[이벤트 감지 루프]
    ↓
┌─────────────────────────────────────────┐
│  TYPE_WINDOW_STATE_CHANGED 이벤트       │
│    ↓                                    │
│  포그라운드 앱 변경 감지                │
│    ↓                                    │
│  shouldHideOverlay() 판단               │
│    ├─ 영상 앱 전체화면? → hide()        │
│    ├─ 블랙리스트 앱?    → hide()        │
│    └─ 그 외            → show()         │
└─────────────────────────────────────────┘
    ↓
[숨김 상태]
    ↓
[재호출 핫스팟 터치] → show()
```

---

## LG 울트라탭 최적화 값

| 설정 | 권장값 | 설명 |
|------|--------|------|
| 바 높이 | 48dp | 시스템 네비바와 유사 |
| 버튼 크기 | 40dp | 터치하기 좋은 크기 |
| 버튼 간격 | 24dp | 오터치 방지 |
| 투명도 | 85% | 콘텐츠 가림 최소화 |
| 핫스팟 높이 | 8dp | 하단 가장자리 터치 영역 |

2000x1200 해상도 기준:
- 가로 모드: 바가 화면 전체 너비, 왼쪽에 Back/Home/Recents, 오른쪽에 알림
- 세로 모드: 동일 레이아웃 (자동 조정)

---

## 영상 앱 감지 목록

자동 숨김이 적용되는 앱 패키지:
- com.google.android.youtube
- com.netflix.mediaclient
- com.amazon.avod.thirdpartyclient
- com.disney.disneyplus
- tv.twitch.android.app
- com.wavve.android
- com.coupang.play
- org.videolan.vlc
- com.android.chrome (전체화면 영상)

---

## 홈 화면 투명 배경 기능

### 작동 원리
1. 사용자가 홈 화면 스크린샷(가로/세로)을 설정 화면에서 선택
2. 앱이 스크린샷 하단 72px를 크롭하여 내부 저장소에 저장
3. 접근성 서비스가 현재 앱이 런처(홈)인지 감지
4. 홈 화면에서는 크롭된 이미지를 네비바 배경으로 사용
5. 다른 앱에서는 기본 검정색 배경 표시

### 사용법
1. 먼저 **시스템 네비바를 숨긴 상태**에서 홈 화면 스크린샷 촬영
2. 설정 화면 > "홈 화면 배경 이미지" 섹션
3. "투명 배경 효과" 스위치 활성화
4. 가로 모드/세로 모드별로 해당 스크린샷 선택

### 주의사항
- 스크린샷의 하단 72px만 사용되므로, 배경이 비슷해야 자연스러움
- 홈 화면이 변경되면 스크린샷을 다시 설정해야 함
- 런처 앱이 자동 감지됨 (LG, Samsung, Google 등)

---

## 트러블슈팅

### 오버레이가 표시되지 않음
1. 접근성 서비스가 활성화되어 있는지 확인
2. `adb logcat -s NavBarAccessibility`로 로그 확인
3. 앱 재설치 후 재시도

### 특정 앱에서 버튼이 동작하지 않음
- 일부 앱은 `performGlobalAction()`을 차단할 수 있음
- 이 경우 해당 앱을 블랙리스트에 추가하여 시스템 네비바 사용

### 배터리 소모
- 접근성 서비스는 항상 실행되므로 약간의 배터리 소모 발생
- 영상 시청 시 자동 숨김으로 리소스 절약

---

## 확장 가능성

### 추가 버튼 액션
`NavAction.kt`에서 정의된 액션:
- POWER_DIALOG: 전원 메뉴
- LOCK_SCREEN: 화면 잠금
- TAKE_SCREENSHOT: 스크린샷
- QUICK_SETTINGS: 빠른 설정

### 커스텀 제스처
`NavBarOverlay.kt`에서 GestureDetector 추가 가능:
- 길게 누르기
- 스와이프
- 더블 탭

---

## 라이선스

이 프로젝트는 개인 사용 목적으로 작성되었습니다.

참고한 오픈소스:
- https://github.com/thbecker/android-accessibility-overlay
- https://github.com/snoopy112/BackButton
