# Shizuku 기반 자동 터치 구현 가이드

## 개요

현재 자동 터치 기능은 `AccessibilityService.dispatchGesture()` API를 사용하는데, 이 API는 다음과 같은 한계가 있습니다:
- 다른 제스처/입력과 충돌 시 취소됨
- 펜 버튼 이벤트 처리 중에는 제스처가 거부됨
- 재시도 메커니즘이 필요하여 응답 지연 발생

Shizuku를 사용하면 `input tap` 셸 명령어로 직접 터치를 주입할 수 있어 훨씬 안정적입니다.

## 구현 목표

1. Shizuku 사용 가능 여부 감지
2. Shizuku 권한 요청 UI
3. Shizuku 기반 터치 주입 구현
4. 기존 dispatchGesture 방식과 Shizuku 방식 자동 선택

---

## 1단계: Shizuku 상태 확인 UI 추가

### 파일: `PenButtonConfigActivity.kt` 또는 관련 설정 화면

자동 터치 설정 화면에 Shizuku 상태를 표시합니다:

```kotlin
// Shizuku 상태 확인
private fun checkShizukuStatus(): ShizukuStatus {
    return when {
        !ShizukuHelper.isShizukuAvailable() -> ShizukuStatus.NOT_INSTALLED
        !ShizukuHelper.hasShizukuPermission() -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }
}

enum class ShizukuStatus {
    NOT_INSTALLED,  // Shizuku 앱 미설치
    NO_PERMISSION,  // 권한 없음
    READY           // 사용 가능
}
```

### UI 요소 추가

자동 터치 설정 화면에 다음 정보 표시:
- Shizuku 상태 (설치됨/미설치, 권한 있음/없음)
- "Shizuku 권한 요청" 버튼 (권한 없을 때)
- Shizuku 미설치 시 설치 안내 링크

---

## 2단계: ShizukuHelper에 터치 주입 함수 추가

### 파일: `util/ShizukuHelper.kt`

```kotlin
/**
 * Shizuku를 통한 터치 이벤트 주입
 * @param x X 좌표
 * @param y Y 좌표
 * @return 성공 여부
 */
fun injectTap(x: Float, y: Float): Boolean {
    if (!hasShizukuPermission()) {
        Log.w(TAG, "Shizuku permission not granted")
        return false
    }

    val command = "input tap ${x.toInt()} ${y.toInt()}"
    val (exitCode, output) = executeShellCommand(command)

    Log.d(TAG, "injectTap($x, $y): exitCode=$exitCode")
    return exitCode == 0
}

/**
 * Shizuku를 통한 스와이프 이벤트 주입 (필요시)
 */
fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): Boolean {
    if (!hasShizukuPermission()) return false

    val command = "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs"
    val (exitCode, _) = executeShellCommand(command)
    return exitCode == 0
}
```

---

## 3단계: Bridge Activity 수정

### 파일: `PenButtonABridgeActivity.kt`, `PenButtonBBridgeActivity.kt`

Shizuku 사용 가능 시 우선 사용, 아니면 기존 방식 fallback:

```kotlin
private fun performAutoTouch(x: Float, y: Float) {
    // Shizuku 사용 가능하면 바로 실행 (딜레이 없음)
    if (ShizukuHelper.hasShizukuPermission()) {
        // 약간의 딜레이 후 실행 (펜 버튼 이벤트 완료 대기)
        sharedHandler.postDelayed({
            val success = ShizukuHelper.injectTap(x, y)
            Log.d(TAG, "Shizuku tap result: $success at ($x, $y)")
        }, 50L)  // 50ms면 충분
        return
    }

    // Shizuku 없으면 기존 dispatchGesture 방식 사용
    performTouchWithRetry(x, y, 0, mySessionId)
}
```

### onCreate에서 호출 변경

```kotlin
"TOUCH_POINT" -> {
    val x = settings.penATouchX
    val y = settings.penATouchY
    Log.d(TAG, "Touch point: ($x, $y)")
    if (x >= 0 && y >= 0) {
        performAutoTouch(x, y)  // 변경된 함수 호출
    }
}
```

---

## 4단계: 설정 화면 UI 업데이트

### 문자열 리소스 추가

**values/strings.xml:**
```xml
<!-- Shizuku Auto Touch -->
<string name="shizuku_status_title">Shizuku Status</string>
<string name="shizuku_status_ready">Ready (Fast mode enabled)</string>
<string name="shizuku_status_no_permission">Permission required</string>
<string name="shizuku_status_not_installed">Not installed</string>
<string name="shizuku_request_permission">Request Shizuku Permission</string>
<string name="shizuku_install_guide">Install Shizuku for reliable auto touch</string>
<string name="shizuku_install_link">https://shizuku.rikka.app/</string>
```

**values-ko/strings.xml:**
```xml
<!-- Shizuku 자동 터치 -->
<string name="shizuku_status_title">Shizuku 상태</string>
<string name="shizuku_status_ready">사용 가능 (빠른 모드 활성화)</string>
<string name="shizuku_status_no_permission">권한 필요</string>
<string name="shizuku_status_not_installed">미설치</string>
<string name="shizuku_request_permission">Shizuku 권한 요청</string>
<string name="shizuku_install_guide">안정적인 자동 터치를 위해 Shizuku를 설치하세요</string>
<string name="shizuku_install_link">https://shizuku.rikka.app/</string>
```

---

## 5단계: 자동 터치 가이드 메시지 업데이트

### 파일: strings.xml

기존 `touch_point_guide_message`를 업데이트하여 Shizuku 안내 추가:

```xml
<string name="touch_point_guide_message">How to set up auto touch:\n\n1. A floating button will appear\n2. Navigate to the app where you want to use auto touch\n3. Tap the floating button\n4. Tap the location you want to assign\n5. Tap \"Activate Auto Touch\" to confirm\n\nFor best results:\n• Install Shizuku app for fast and reliable operation\n• Without Shizuku, auto touch may be slow or inconsistent\n\nUsage tips:\n• Set Button A to Undo and Button B to Redo in drawing apps</string>
```

---

## 6단계: 테스트 체크리스트

### Shizuku 설치 상태별 테스트

1. **Shizuku 미설치**
   - [ ] 자동 터치 설정 가능
   - [ ] 경고 메시지 표시됨
   - [ ] dispatchGesture 방식으로 동작 (느림)

2. **Shizuku 설치됨, 권한 없음**
   - [ ] 권한 요청 버튼 표시
   - [ ] 권한 요청 다이얼로그 표시
   - [ ] 권한 거부 시 기존 방식 사용

3. **Shizuku 권한 있음**
   - [ ] "빠른 모드 활성화" 상태 표시
   - [ ] 자동 터치 즉시 실행됨 (50ms 이내)
   - [ ] 펜 버튼 연타 시에도 안정적 동작

---

## 구현 우선순위

1. **필수**: ShizukuHelper에 `injectTap()` 함수 추가
2. **필수**: Bridge Activity에서 Shizuku 우선 사용 로직
3. **권장**: 설정 화면에 Shizuku 상태 표시
4. **선택**: Shizuku 설치 안내 링크

---

## 참고: 기존 ShizukuHelper 구조

현재 `ShizukuHelper.kt`에 이미 다음 기능이 구현되어 있음:
- `isShizukuAvailable()`: Shizuku 실행 중인지 확인
- `hasShizukuPermission()`: 권한 있는지 확인
- `requestShizukuPermission()`: 권한 요청
- `executeShellCommand(command)`: 셸 명령어 실행

`injectTap()` 함수만 추가하면 바로 사용 가능.
