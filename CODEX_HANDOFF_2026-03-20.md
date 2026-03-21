# UltraNavbar Handoff

작성 시각: 2026-03-20 21:19 KST

## 목적

사용자가 요청한 원래 요청은 다음과 같습니다.
'이제 다시, UltraNavBar쪽 기능 작업인데요, 이번엔 커스텀 네비바를, 이란 3버튼 형태 네비바에 맞춘게 아닌, 제스쳐 형태의 네비바에 맞추어 만들어야합니다.
먼저 제스쳐 형태 네비바는 기존 커스텀 네비바랑 많이 달라서, 아예 별도 메뉴를 만들어야할거같습니다. UltraNavBar의 앱 설정에서, 왼쪽 '네비게이션 바' 메뉴를 '3버튼 네비바'로 이름 바꾸고(안에있는 커스텀 네비게이션 바 토글도 이름을 3버튼 네비바 토글로 바꿉니다), 그 메뉴 아래에 신규로 '제스쳐 네비바'를 신설합니다. 두 메뉴의 커스텀바는 서로 동시에 활성화될 수 없으며, 하나가 켜지면 하나는 전체적으로 무조건 꺼지도록 합니다. 두 모드가 서로 공유할 수 있는 설정항목은 그대로 공유해둡니다(작업 표시줄 모드 모듈, 작업표시줄 아이콘 개수, 재호출 설정, 작업표시줄/앱 즐겨찾기 아이콘 모양, 버튼배치 반전, 외부 아이콘 팩, 앱 아이콘 커스텀 까지). 
테스트기기의 2000x1200 해상도 기준으로, 하단 24픽셀을 점유하고 있는 기존 제스쳐 네비바를 커스텀하려는 계획은 다음과 같습니다. 우선 이것은 기존의 제스쳐 네비바를 그대로 쓰긴 쓸것입니다. 이에 따라 기존 3버튼 형태의 커스텀 네비바에서 쓰이는 거의 모든 레이아웃은 사용하지 않을 것이나, 단 몇가지는 그대로 가져와 쓸 것입니다. 네비바 배경은 가져와 쓸것이지만, 기존과 달리 오로지 홈화면에서만 쓰일 것이며, 기존과 달리 다른앱, 알림패널, 앱목록을 오가는 과정에서 페이드 애니메이션으로 등장 및 퇴장할 것이며, 홈화면에서 120px 크기로 놓아지는건 동일합니다. shadow.png를 적용하는것도 동일합니다. 기존 3버튼 형태 커스텀 네비바의 있던 조작 버튼중 알림패널/앱 즐겨찾기/캡쳐 조작버튼은 가져와서 왼쪽에 달아놓을 것이며, 이것은 왼쪽 하단 끄트머리에서 오른쪽상단 방향으로 제스쳐를 취하면 슬라이드 애니메이션으로 등장하도록 하여, 5초간 유지가되게 하다 다시 슬라이드 애니메이션으로 숨겨지도록합니다.  앱 작업표시줄은 기존 커스텀네비바에서의 위치와 완전히 똑같은 위치에 두되, 홈화면에서는 기존 3버튼 네비바처럼 큰 아이콘 상태로 두고, 그외 모든 상황(다른앱을 쓰거나, 앱목록 상태거나, 알림패널 내림 등등)에서는 앱 작업표시줄은 슬라이드 애니메이션 형태로 평소엔 숨겨저있다가, 제스쳐 네비바를 위로 슬라이드하여 사용하려할때 잠시 등장하여(단 이때는 3버튼 시절과 동일한 위치와 크기의 작은 아이콘 사용) 약 5초간 유지(유지된 상태에서 홈화면으로 갈 경우 큰아이콘으로 커지는 애니메이션 실행)되고 이후 다시 슬라이드로 아래로 내려가 대기합니다. 둘다 만약 잠시 활성화 된 상태에서 사용자가 해당 부분에 내리는 제스쳐를 각자 방향에 맞게(알림패널/앱 즐겨찾기/캡쳐는 왼쪽 하단으로 제스쳐, 앱 작업표시줄은 그냥 바로 아래로 제스쳐) 취하면 5초가 되지않은 상태여도 바로 애니메이션 진행되며 숨겨지도록합니다.
커스텀 네비바의 알림패널/앱 즐겨찾기/캡쳐 조작버튼들은 기본적으로 모두 오른쪽에 있을것이나, 3버튼 커스텀 네비바의 버튼배치 반전처럼 왼쪽 오른쪽 위치가 바뀌게 할 수 있도록 합니다. 제스쳐랑 애니메이션도 그방향에 맞게 작동할수있게하고요, 그리고 이 버튼들도 홈화면에서는 상시 등장하게 하고, 등장애니메이션의 시작과 끝 타이밍을 앱 작업표시줄의 크기조절 애니메이션과 동일하게 맞춥니다.
기존 분할화면 사용방식은 3버튼 네비바와 완전히 동일하며, 홈화면에서는 분할화면 사용불가인 조건까지 완전히 같음을 알립니다.'

그리고 이렇게 요청한 뒤, 사용자가 다시 요청한 사항들은 다음과 같습니다.

'현재 상태 알려드립니다, 여러개 고쳐야하는데, 가장 먼저, 홈화면용 배경이, 원래 3버튼 시절에 의도한거보다 좀더 위로 올라와있어요, 제스쳐 네비바 사용 상태에서는 네비바 높이가 24px정도되는데 이거랑 관련있는거같은데, 여하는 네비바 높이 하단에서 120px내로 처리하세요'
'고쳐야할게 너무 많긴한데.. 우선 앱 작업표시줄 위치부터 확인해보세요, 너무 아래로 가있는데, 네비바 배경 120px 높이의 한가운데에 있어야해요, 그리고 shadow.png 개선 전혀없었고요, 무엇보다 홈화면 벗어나있을때는, 기본 제스쳐 바만 남기고 나머진 다 아래로 슬라이드되어 숨겨저야하는데, 딱 제스쳐바 영역 높이 안으로 모두 들어가있더라고요.. 그리고 다른 앱 실행하려하면 ANR이슈인지 그거조차도 모두 꺼저버리고요(원래 3버튼 시절에는 기기 성능 상태에따라 ANR 이슈를 일으키는 애니메이션 프레임 조절이 들어갔는데 이번꺼는 없는건가요?), 무엇보다 앱 작업표시줄과 조작버튼을 제스쳐로 꺼내려한것이, 기본 네비바에 이미 그 제스쳐 영역이 할당되있어서, 그거 빼야할거같습니다. 제스쳐를 밀어올려 최근앱패널이 열린상태에서 72px 높이로 기존 조작버튼 네비바, 앱작업표시줄(작은아이콘)이 나오게하고, 거기서 분할화면을 시도하게하든 해야할거같아요'
'자 확인하였고요, 문제가 있습니다, 가장 먼저, 지금 제스쳐 네비바 모드인데 왜 뒤로/홈/최근앱 조작버튼이 나오고있죠? 여기선 없어야하는데... 그리고 앱 작업표시줄의 경우, 최종적인 위치는 이제 네비바 배경 중앙에 오긴했지만, 그 위치로 오기까지의 애니메이션이 많이 불안정합니다, 중앙보다 위로갔다가 바로 중앙으로 이동하는데 이거 자연스럽게 나오도록 조절해야할거같고요, 그리고 알림패널/앱즐겨찾기/캡처 이 버튼들도 그 위치에서 네비바 높이 중앙에 와야하는데, 네비바 높이만큼 끄트머리에 올라와있어서요, 그거도 고쳐야하고, 그리고 shadow 넣으신거 오히려 더이상하게, 전혀 그라데이션이라 할 수 없을 정도로 많이 이상합니다, 차라리 제스쳐 네비바에서는 shaodw 전면적으로 빼죠, 그리고,  홈화면에서 앱 목록으로 갔을때, 아까 지적한 '앱 작업표시줄이 24px 영역내 제스쳐바 영역 높이 안으로 모두 들어가있더라고요' 이 문제 전혀 안고쳐젔고요, 그나마 다행인건 다른 앱 실행중엔 의도대로 잘숨겨지긴 했는데, 요청한 최근앱 패널에서는, 앱작업표시줄만 작은아이콘 정상적으로 나오지만 알림패널/앱 즐겨찾기/캡처 버튼들과 뒤에 네비바 배경이 제스쳐 네비바의 높이인 24px만큼만 나오는데, 여기서는 네비바 높이가 72px이여야하고 알림패널/앱 즐겨찾기/캡처 버튼들도 그 네비바 높이의 중간에 있어야합니다. 그리고 이 상태에의 앱작업표시줄에서, 분할화면 기능을 쓰기위해 앱아이콘 길게 눌러 드래그 시도하려하니, 드래그 시도하자마자 그게 취소되버립니다, 이거까지 모두 싹 제대로 고쳐주세요 '
' 아까 지적한 '앱 작업표시줄이 24px 영역내 제스쳐바 영역 높이 안으로 모두 들어가있더라고요' 이 문제 안고쳐젔습니다, 상황을 구체적으로 이야기드릴게요, 이 현상이 일어나는건 홈화면에 있을때 알림패널을 내렸거나, 홈화면에서 앱목록으로 갔을때 그러고요, 다만 다른 앱 사용중에는 무사히 완전히 숨겨지는데, 그거처럼 알림패널 내린 상태랑 앱목록 연상태에서도 완전히 숨겨지면되겠습니다. 그리고, 최근앱패널에서 분할화면 사용하기 위한 앱아이콘 드래그 앤 드롭 여전히 안되고있습니다, 이거도 고쳐주세요'
'고쳐진게 단 하나도 없습니다. 이번엔 앱목록에서도 알림패널 내린상태에서도 커스텀네비바 부분이 숨겨지는거 그거 자체가 아예 안되고 홈화면용 120px가 그대로 모두 유지되버리고 있고요, 최근앱패널에서 분할화면 사용하기 위한 앱아이콘 드래그 앤 드롭 여전히 안되고있습니다, 이 2개라도 제대로 고쳐보세요, 필요하면 adb로그라도 보세요'
'앞으로는 홈화면으로 돌아오며 120px짜리 네비바가 다시 생길때 그때도 네비바 전체가 통쨰로 슬라이드형태로 올라왔으면 좋겠고요, 그리고 문제가 있는데, 앱목록과 알림패널에서 홈화면으로 돌아오면, 조작버튼과 네비바배경은 생기는데, 앱작업표시줄이 안생겨요, 그리고 지금 3번째 말하는데, 최근앱패널에서 앱작업표시줄에서 앱아이콘 길게 눌러 드래그앤드롭으로 분할화면 영역에 갖다대는 이 과정, 지금 앱아이콘 길게 눌러 드래그하는거부터 안되고있다고요, 고치라고요'
'고쳐진게 하나도 없는데, 다른 문제가 생겼어요, 멀쩡했던 3버튼 네비바가 완전히 망가져있더라고요, 이거랑 제스쳐랑 별개여야하는데, 보니까 3버튼 네비바까지 건드리신거같은데.. 더이상 작업을 맡길 수 없을거같아서, 현재까지의 작업내역을 정리하고, 해결하지못한 문제들을 정리해서 별도의 .md파일로 저장하세요, 다른AI에게 작업을 맡기도록 하겠습니다'

이 문서는 이 작업을 Codex가 수행한 최근 작업 내역, 현재 워크트리 상태, adb 기준 확인 사항, 그리고 해결하지 못한 문제를 다음 작업자에게 넘기기 위한 handoff 문서입니다.

사용자 최종 요청:

- 현재까지 작업 내역 정리
- 해결하지 못한 문제 정리
- 별도 `.md` 파일로 저장

## 현재 설치/실행 상태

- 패키지: `com.minsoo.ultranavbar`
- 현재 설치 버전: `versionName=0.2.14`, `versionCode=26`
- adb 기준 최신 설치 시각: `2026-03-20 21:18:13`
- 최신 설치는 `.\gradlew.bat installDebug`로 직접 수행됨

## 빌드/설치 환경 관련 변경

기존 문제:

- 시스템 기본 Java가 `11`이라 CLI에서 Gradle 빌드/설치가 불안정하거나 실패할 수 있었음

적용한 변경:

- `C:\Users\minsi\AndroidStudioProjects\UltraNavbar\gradle.properties`
  - `org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr`

효과:

- 기본 셸이 Java 11이어도 이 프로젝트는 Android Studio JBR로 `assembleDebug`, `installDebug` 수행 가능

## 최근 주 작업 내역

### 1. Quickstep/gesture home UI 관련 작업 누적

다음 공용 파일에 제스처 네비바 홈/recents/taskbar 관련 수정이 누적되어 있음:

- `app/src/main/java/com/minsoo/ultranavbar/core/Constants.kt`
- `app/src/main/java/com/minsoo/ultranavbar/core/GestureHandler.kt`
- `app/src/main/java/com/minsoo/ultranavbar/core/RecentAppsTaskbar.kt`
- `app/src/main/java/com/minsoo/ultranavbar/overlay/NavBarOverlay.kt`
- `app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt`
- `app/src/main/java/com/minsoo/ultranavbar/settings/SettingsManager.kt`
- `app/src/main/java/com/minsoo/ultranavbar/ui/NavBarSettingsFragment.kt`

주요 의도:

- 제스처 모드 홈 화면에서 `120px` 높이의 확장 navbar/taskbar UI
- 최근앱 패널에서 `72px` 높이의 recents panel UI
- 앱/패널 진입 시 제스처 모드 오버레이 슬라이드 숨김
- recent apps taskbar 아이콘 long-press drag로 split-screen 진입

### 2. 앱목록/홈 복귀 슬라이드 경로 조정

최근 수정에서 adb 로그상 다음 경로는 바뀜:

- 앱목록 복귀 시
  - `Overlay shown (fade=false, slide=true, fromGesture=false, unlock=false)`
- 홈 복귀 시
  - `Overlay shown (fade=false, slide=true, fromGesture=false, unlock=false)`

이 부분은 사용자 체감상 여전히 원하는 상태가 아니라고 보고됨.

### 3. recent apps drag 관련 수정 시도

`RecentAppsTaskbar.kt`와 `NavBarOverlay.kt`에서 다음을 시도함:

- long-press 시점에 드래그 상태 즉시 시작
- 동일 패키지 목록이면 taskbar 아이콘 뷰 재생성 회피
- gesture+recents 상태에서 inline drag 경로 추가
- `overlay drag active` 상태를 접근성 서비스에 전달

현재 결과:

- 사용자 기준으로 split drag는 여전히 동작하지 않음

## 현재 워크트리 변경 파일

`git status --short` 기준:

- `M app/src/main/java/com/minsoo/ultranavbar/MainActivity.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/core/Constants.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/core/GestureHandler.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/core/RecentAppsTaskbar.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/overlay/NavBarOverlay.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/service/NavBarAccessibilityService.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/settings/SettingsManager.kt`
- `M app/src/main/java/com/minsoo/ultranavbar/ui/NavBarSettingsFragment.kt`
- `M app/src/main/res/layout/fragment_navbar_settings.xml`
- `M app/src/main/res/menu/settings_nav.xml`
- `M app/src/main/res/values-ko/strings.xml`
- `M app/src/main/res/values/strings.xml`
- `M gradle.properties`
- `?? app/src/main/res/drawable/ic_gesture_navbar.xml`
- `?? app/src/main/res/drawable/ic_launcher_menu.xml`
- `?? app/src/main/res/drawable/quickstep_home_shadow.xml`
- `?? app/src/main/res/values-ko/strings_launcher.xml`
- `?? backup/`

주의:

- 이 워크트리는 이미 여러 차례 누적 수정이 들어간 상태임
- 제스처 관련 수정이 공용 오버레이/서비스 경로를 건드렸기 때문에 3버튼 회귀가 발생했을 가능성이 높음

## adb 기준 최근 관찰 사항

최신 설치 후 확인된 로그:

- 홈 복귀:
  - `Home screen state: true`
  - `Overlay shown (fade=false, slide=true, fromGesture=false, unlock=false)`
- 앱목록 복귀:
  - `App Drawer state changed: false`
  - `Overlay shown (fade=false, slide=true, fromGesture=false, unlock=false)`

즉, 적어도 로그 문자열상으로는 홈/앱목록 복귀가 `slide=true`로 들어가고 있음.

하지만 사용자 체감상:

- 여전히 “아무것도 고쳐지지 않음”
- 추가로 3버튼 네비바가 망가졌다고 보고됨

이 시점부터는 “예전 APK가 설치되어 있어서 반영 안 됨” 문제는 아님. 최신 설치본에서도 문제가 계속됨.

## 해결하지 못한 문제

### 1. 제스처 모드 recent apps split drag 미동작

사용자 보고:

- recent apps 패널에서 taskbar 아이콘 long-press 후 drag-and-drop으로 split-screen 진입이 되지 않음
- 드래그 시작 자체가 안 되는 것처럼 보임

이전 adb 로그에서 확인된 패턴:

- `Overlay drag active: true`
- 직후 `Overlay drag active: false`
- `Long-press drag to split:` 로그는 사용자 기대만큼 안정적으로 재현되지 않음

추정 포인트:

- `RecentAppsTaskbar.setupTouchListener(...)`
- `NavBarOverlay.taskbarListener.onDragStart/onDragIconUpdate/onDragEnd`
- `NavBarAccessibilityService.setOverlayDragActive(...)`
- `SplitScreenHelper.launchSplitScreen(...)`
- taskbar 아이콘 뷰가 recents 상태 변화에 의해 재구성되거나 터치가 취소될 가능성

### 2. 제스처 모드에서 체감상 동작이 여전히 잘못됨

사용자 보고:

- 홈/앱목록/알림패널/최근앱 패널에서 여전히 의도와 다름
- “고쳐진 것이 하나도 없다”는 피드백 반복

중요:

- 로그상 `slide=true`가 찍히는 것과 실제 화면 체감이 일치하지 않음
- 단순 로그 확인만으로 해결 판단하면 안 됨
- 실제 화면 레벨 검증 필요

### 3. 3버튼 네비바 회귀

사용자 보고:

- 원래 멀쩡하던 3버튼 네비바가 완전히 망가짐
- 제스처 작업과 별개여야 하는데 공용 코드 변경으로 같이 깨진 것으로 의심

특히 의심되는 공유 경로:

- `NavBarOverlay.kt`
- `NavBarAccessibilityService.kt`
- `SettingsManager.kt`
- `Constants.kt`

권장 대응:

- 기존에 정상이던 3버튼 네비바 모드의 파일은 C:\Users\minsi\AndroidStudioProjects\UltraNavbar\backup에 있는 zip을 압축풀기하여 확인
- 3버튼 모드와 제스처 모드 분기를 다시 엄격히 분리
- gesture 전용 분기와 shared overlay 분기 diff 재검토

### 4. reflection 경고

로그에 반복적으로 보이는 경고:

- `NoSuchFieldException: touchableRegion in ViewTreeObserver$InternalInsetsInfo`

이건 현재 치명적 크래시 원인은 아니지만, overlay 터치 영역 관련 fallback이 불완전할 수 있음.

위치:

- `NavBarOverlay.setupNavBarTouchableRegionListener(...)`

## 다음 작업자에게 권장하는 순서

1. `NavBarOverlay.kt`와 `NavBarAccessibilityService.kt`에서 gesture-only 분기와 3-button 공용 분기를 분리 재정리
2. recent apps split drag는 UI/터치 cancel 원인부터 재현 ADB 로그로 다시 확인
3. 실제 화면 캡처/녹화 기준으로 검증
4. 로그 문자열만 보고 “수정 완료” 판단하지 말 것

## 참고 메모

- 최신 설치 자체는 성공함
- 최신 설치본에서 테스트했음에도 사용자는 결과가 여전히 잘못되었다고 보고함
- 따라서 남은 문제는 배포/설치 문제가 아니라 실제 동작 문제임

