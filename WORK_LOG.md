# UltraNavbar 개발 작업 내역

**작성일:** 2025-12-28
**세션:** 1차 개발 세션

---

## 프로젝트 개요

LG 울트라탭 (2000x1200, Android 12)용 커스텀 하단 네비게이션 오버레이 바 앱

### 핵심 요구사항
- 시스템 네비바 위에 덮어씌우는 오버레이 바
- AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY 사용
- 왼쪽: Back, Home, Recents / 오른쪽: 알림 패널
- 영상 앱 전체화면 시 자동 숨김
- 앱별 블랙리스트/화이트리스트 숨김
- 홈 화면에서 투명 배경 효과 (스크린샷 하단 72px 크롭)

---

## 완료된 작업

### 1. 프로젝트 기본 구조 생성
- [x] Gradle 설정 (build.gradle, settings.gradle, gradle.properties)
- [x] AndroidManifest.xml (접근성 서비스, 권한)
- [x] 리소스 파일 (strings.xml, colors.xml, themes.xml)
- [x] 접근성 서비스 설정 (accessibility_service_config.xml)
- [x] local.properties (SDK 경로)

### 2. 핵심 클래스 구현
- [x] `NavAction.kt` - 네비게이션 액션 enum (Back, Home, Recents, Notifications 등)
- [x] `SettingsManager.kt` - SharedPreferences 기반 설정 관리
- [x] `NavBarAccessibilityService.kt` - 접근성 서비스 (앱 감지, 전체화면 감지)
- [x] `NavBarOverlay.kt` - TYPE_ACCESSIBILITY_OVERLAY 기반 UI
- [x] `BootReceiver.kt` - 부팅 시 자동 시작

### 3. 설정 화면 구현
- [x] `MainActivity.kt` - 설정 UI (SeekBar, Switch, RadioButton)
- [x] `activity_main.xml` - Material Design 카드 레이아웃
- [x] `AppListActivity.kt` - 앱 목록 선택 화면
- [x] `AppListAdapter.kt` - RecyclerView 어댑터

### 4. 홈 화면 투명 배경 기능 (추가 요청)
- [x] `ImageCropUtil.kt` - 스크린샷 하단 72px 크롭 유틸리티
- [x] 런처 패키지 자동 감지 로직
- [x] 홈 화면일 때 배경 이미지 적용, 그 외 검정색
- [x] MainActivity에 이미지 선택 UI 추가

### 5. 문서화
- [x] `BUILD_GUIDE.md` - 빌드/테스트 가이드
- [x] 상태 전환 다이어그램
- [x] ADB 명령어 모음

---

## 파일 목록

```
UltraNavbar/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/minsoo/ultranavbar/
│       │   ├── MainActivity.kt
│       │   ├── BootReceiver.kt
│       │   ├── model/NavAction.kt
│       │   ├── service/NavBarAccessibilityService.kt
│       │   ├── overlay/NavBarOverlay.kt
│       │   ├── settings/SettingsManager.kt
│       │   ├── ui/AppListActivity.kt
│       │   ├── ui/AppListAdapter.kt
│       │   └── util/ImageCropUtil.kt
│       └── res/
│           ├── drawable/
│           │   ├── ic_back.xml
│           │   ├── ic_launcher_background.xml
│           │   ├── ic_launcher_foreground.xml
│           │   ├── status_indicator.xml
│           │   └── status_indicator_active.xml
│           ├── drawable-xxhdpi/
│           │   ├── ic_sysbar_back_default.png
│           │   ├── ic_sysbar_back_pressed.png
│           │   ├── ic_sysbar_home_default.png
│           │   ├── ic_sysbar_home_pressed.png
│           │   ├── ic_sysbar_recent_default.png
│           │   ├── ic_sysbar_recent_pressed.png
│           │   ├── ic_sysbar_menu_default.png
│           │   └── ic_sysbar_menu_pressed.png
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_app_list.xml
│           │   └── item_app.xml
│           ├── mipmap-anydpi-v26/
│           │   ├── ic_launcher.xml
│           │   └── ic_launcher_round.xml
│           ├── values/
│           │   ├── colors.xml
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── xml/
│               └── accessibility_service_config.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
├── local.properties
├── gradle/wrapper/gradle-wrapper.properties
├── BUILD_GUIDE.md
└── WORK_LOG.md (이 파일)
```

---

## 미완료/다음 세션에서 할 작업

### 1. APK 빌드
- [ ] Android Studio에서 프로젝트 열기
- [ ] Gradle Sync 실행 (gradle wrapper 자동 생성)
- [ ] Debug APK 빌드
- [ ] (선택) Release APK 빌드 + 자체 서명

### 2. 실기기 테스트
- [ ] LG 울트라탭에서 APK 설치
- [ ] 접근성 서비스 활성화
- [ ] 버튼 동작 확인 (Back, Home, Recents, Notifications)
- [ ] 영상 앱 자동 숨김 테스트
- [ ] 홈 화면 배경 이미지 테스트

### 3. 잠재적 개선사항
- [ ] 버튼 롱프레스 액션 추가
- [ ] 바 위치 조정 (하단 마진)
- [ ] 진동 피드백 추가
- [ ] 버튼 아이콘 커스터마이징

---

## 참고 리소스

### 참고한 오픈소스 (ref- 폴더에 압축 해제됨)
- `ref-overlay/` - android-accessibility-overlay (TYPE_ACCESSIBILITY_OVERLAY 예제)
- `ref-backbutton/` - BackButton (performGlobalAction 예제)

### 버튼 이미지 원본
- `ic_sysbar_*.png` 파일들 - 루트 폴더에 원본 있음

### ADB 테스트 명령어
```bash
# 접근성 서비스 활성화
adb shell settings put secure enabled_accessibility_services \
  com.minsoo.ultranavbar/com.minsoo.ultranavbar.service.NavBarAccessibilityService

# 로그 확인
adb logcat -s NavBarAccessibility NavBarOverlay ImageCropUtil

# 접근성 서비스 비활성화
adb shell settings put secure enabled_accessibility_services ""
```

---

## 기술 스택

- **언어:** Kotlin
- **최소 SDK:** 31 (Android 12)
- **타겟 SDK:** 34
- **주요 라이브러리:**
  - AndroidX Core KTX
  - Material Components
  - AndroidX Preference
  - AndroidX RecyclerView
  - Kotlin Coroutines

---

## 메모

1. **gradle wrapper 없음**: Android Studio에서 열면 자동 생성됨
2. **SDK 경로**: `C:\Users\minsi\AppData\Local\Android\Sdk`
3. **Android Studio 위치**: `C:\Program Files\Android\Android Studio`
4. **해상도 기준**: 2000x1200 (LG 울트라탭 10.4")
5. **크롭 높이**: 72px (홈 화면 배경용)

---

*이 문서는 다음 Claude 세션에서 작업을 이어갈 때 참고용입니다.*
