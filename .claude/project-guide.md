# UltraNavbar Project Guide for Claude

이 문서는 Claude가 UltraNavbar 프로젝트에서 효과적으로 작업하기 위한 가이드입니다.

## 프로젝트 개요

- **앱 이름**: LG UltraTab Extension (UltraNavbar)
- **패키지**: `com.minsoo.ultranavbar`
- **목적**: LG UltraTab 태블릿을 위한 커스텀 네비게이션 바 및 키보드 기능 확장 앱
- **최소 SDK**: 31 (Android 12)

## 프로젝트 구조

```
app/src/main/java/com/minsoo/ultranavbar/
├── core/           # 핵심 로직 (ButtonManager, Constants, etc.)
├── model/          # 데이터 모델 (NavAction, KeyShortcut)
├── overlay/        # 오버레이 UI (NavBarOverlay)
├── service/        # 서비스 (NavBarAccessibilityService, KeyboardBatteryMonitor)
├── settings/       # 설정 관리 (SettingsManager, KeyShortcutManager)
├── ui/             # UI 컴포넌트 (Fragments, Activities, Adapters)
└── util/           # 유틸리티 (BluetoothUtils, ImageCropUtil, etc.)
```

## 작업 시 핵심 원칙

### 1. 파일 먼저 읽기
- 수정 전에 **반드시** 관련 파일을 먼저 읽어서 현재 구조 파악
- 특히 SettingsManager, NavBarAccessibilityService는 자주 수정되므로 최신 상태 확인 필수

### 2. 다국어 지원 (필수)
- **모든 UI 문자열**은 strings.xml에 추가
- 영어: `app/src/main/res/values/strings.xml`
- 한국어: `app/src/main/res/values-ko/strings.xml`
- 두 파일 모두 업데이트 필수!

### 3. 설정 추가 시 체크리스트
1. `SettingsManager.kt`에 KEY 상수 추가
2. `SettingsManager.kt`에 프로퍼티 추가
3. 관련 서비스/Fragment에서 설정 사용
4. 레이아웃 XML에 UI 추가
5. strings.xml (영어/한국어) 모두 업데이트
6. Fragment에서 스위치/컨트롤 초기화 및 리스너 설정

### 4. 브로드캐스트 액션
설정 변경 시 서비스에 알리는 방법:
- `Constants.Action.SETTINGS_CHANGED`: 일반 설정 변경
- `Constants.Action.RELOAD_BACKGROUND`: 배경 이미지 변경
- `Constants.Action.UPDATE_BUTTON_COLORS`: 버튼 색상 변경

### 5. 빌드 오류 대응
- 오류 발생 시 즉시 수정
- 존재하지 않는 API 상수 사용 주의 (예: GLOBAL_ACTION_ASSIST는 없음)
- Android API 레벨 호환성 확인

## 주요 컴포넌트 역할

### NavBarAccessibilityService
- 앱의 핵심 서비스
- 오버레이 표시/숨김 로직
- 윈도우 상태 감지
- 키보드 이벤트 처리

### SettingsManager
- 모든 앱 설정을 SharedPreferences로 관리
- 싱글톤 패턴 사용

### NavBarOverlay
- 실제 네비게이션 바 오버레이 UI
- ButtonManager를 통해 버튼 관리

### ButtonManager
- 네비게이션 버튼 생성 및 스타일 관리
- 알림 깜빡임 애니메이션

## 커뮤니케이션 스타일

1. **간결하게**: 불필요한 설명 없이 핵심만
2. **구조화**: 변경사항은 목록으로 정리
3. **확인 요청**: 불확실한 요구사항은 먼저 질문
4. **즉시 대응**: 피드백 받으면 바로 수정

## 자주 사용되는 패턴

### 새 기능 토글 추가
```kotlin
// SettingsManager.kt
private const val KEY_FEATURE_ENABLED = "feature_enabled"

var featureEnabled: Boolean
    get() = prefs.getBoolean(KEY_FEATURE_ENABLED, false)
    set(value) = prefs.edit().putBoolean(KEY_FEATURE_ENABLED, value).apply()
```

### 설정 변경 알림
```kotlin
// Fragment에서
private fun notifySettingsChanged() {
    requireContext().sendBroadcast(Intent(Constants.Action.SETTINGS_CHANGED))
}
```

### 스위치 리스너
```kotlin
switchFeature.isChecked = settings.featureEnabled
switchFeature.setOnCheckedChangeListener { _, isChecked ->
    settings.featureEnabled = isChecked
    notifySettingsChanged()
}
```

## 버전 관리

- 버전 파일: `app/build.gradle`
- versionCode: 정수 (매 릴리즈마다 증가)
- versionName: "x.y.z" 형식
- 변경사항: `CHANGELOG.md`에 기록

## 주의사항

1. **접근성 서비스 권한**: 이 앱은 AccessibilityService를 사용하므로 민감한 권한 처리 주의
2. **오버레이 권한**: WindowManager를 통한 오버레이 사용
3. **블루투스 권한**: 키보드 배터리 모니터링에 필요
4. **태블릿 전용**: DeviceProfile.isTablet() 체크로 태블릿에서만 동작

---

이 가이드를 참고하여 프로젝트의 일관성을 유지하며 작업해주세요.
