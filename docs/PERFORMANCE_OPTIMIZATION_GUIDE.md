# 성능 최적화 기능 구현 가이드

## 개요
UltraNavbar 앱에 성능 최적화 기능을 추가합니다. 루트 권한이나 Shizuku 없이 구현 가능한 기능들입니다.

## 구현할 기능

### 1단계: 시스템 상태 모니터링

#### 1.1 메모리 모니터링
```kotlin
// ActivityManager를 사용한 메모리 정보
val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
val memoryInfo = ActivityManager.MemoryInfo()
activityManager.getMemoryInfo(memoryInfo)

val totalMemory = memoryInfo.totalMem / (1024 * 1024)  // MB
val availableMemory = memoryInfo.availMem / (1024 * 1024)  // MB
val usedMemory = totalMemory - availableMemory
val usagePercent = (usedMemory * 100 / totalMemory).toInt()
```

#### 1.2 CPU 모니터링
```kotlin
// /proc/stat에서 CPU 사용률 계산
fun getCpuUsage(): Float {
    try {
        val reader = RandomAccessFile("/proc/stat", "r")
        val load = reader.readLine()
        reader.close()

        val toks = load.split(" +".toRegex())
        val idle1 = toks[4].toLong()
        val cpu1 = toks[1].toLong() + toks[2].toLong() + toks[3].toLong() +
                   toks[5].toLong() + toks[6].toLong() + toks[7].toLong()

        Thread.sleep(360)

        val reader2 = RandomAccessFile("/proc/stat", "r")
        val load2 = reader2.readLine()
        reader2.close()

        val toks2 = load2.split(" +".toRegex())
        val idle2 = toks2[4].toLong()
        val cpu2 = toks2[1].toLong() + toks2[2].toLong() + toks2[3].toLong() +
                   toks2[5].toLong() + toks2[6].toLong() + toks2[7].toLong()

        return ((cpu2 - cpu1).toFloat() / ((cpu2 + idle2) - (cpu1 + idle1))) * 100f
    } catch (e: Exception) {
        return -1f
    }
}
```

#### 1.3 CPU/배터리 온도
```kotlin
// /sys/class/thermal에서 온도 읽기
fun getTemperatures(): Map<String, Float> {
    val temps = mutableMapOf<String, Float>()
    val thermalDir = File("/sys/class/thermal")

    thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
        try {
            val type = File(zone, "type").readText().trim()
            val temp = File(zone, "temp").readText().trim().toFloat() / 1000f

            // CPU, 배터리 관련 온도만 필터링
            if (type.contains("cpu", ignoreCase = true) ||
                type.contains("battery", ignoreCase = true) ||
                type.contains("tsens", ignoreCase = true)) {
                temps[type] = temp
            }
        } catch (e: Exception) { }
    }
    return temps
}
```

#### 1.4 배터리 상태
```kotlin
// BatteryManager 사용
val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
val isCharging = batteryManager.isCharging

// 배터리 온도, 전압은 BroadcastReceiver로
val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
val batteryStatus = registerReceiver(null, intentFilter)
val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f)
val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
```

#### 1.5 스토리지 정보
```kotlin
// StatFs 사용
fun getStorageInfo(): Pair<Long, Long> {
    val stat = StatFs(Environment.getDataDirectory().path)
    val totalBytes = stat.blockSizeLong * stat.blockCountLong
    val availableBytes = stat.blockSizeLong * stat.availableBlocksLong
    return Pair(totalBytes, availableBytes)
}
```

---

### 2단계: 최적화 설정 바로가기

#### 2.1 개발자 옵션 (애니메이션 비활성화)
```kotlin
// 개발자 옵션으로 이동
fun openDeveloperOptions(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        context.startActivity(intent)
    } catch (e: Exception) {
        // 일부 기기에서 다른 액션 필요
        val intent = Intent().apply {
            setClassName("com.android.settings",
                "com.android.settings.DevelopmentSettings")
        }
        context.startActivity(intent)
    }
}

// 현재 애니메이션 스케일 확인 (읽기만 가능, 수정은 ADB 필요)
fun getAnimationScale(context: Context): Float {
    return Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1.0f
    )
}
```

#### 2.2 배터리 최적화 관리
```kotlin
// 배터리 최적화 설정으로 이동
fun openBatteryOptimization(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    context.startActivity(intent)
}

// 특정 앱의 배터리 최적화 상태 확인
fun isIgnoringBatteryOptimizations(context: Context, packageName: String): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(packageName)
}
```

#### 2.3 백그라운드 프로세스 제한 안내
```kotlin
// 개발자 옵션 > 백그라운드 프로세스 제한으로 안내
// 직접 설정 변경은 불가, 안내 다이얼로그 표시
fun showBackgroundProcessLimitGuide(context: Context) {
    MaterialAlertDialogBuilder(context)
        .setTitle("백그라운드 프로세스 제한")
        .setMessage("""
            백그라운드 앱을 제한하여 성능을 향상시킬 수 있습니다.

            설정 방법:
            1. 개발자 옵션 열기
            2. '백그라운드 프로세스 제한' 찾기
            3. 원하는 제한 수준 선택

            주의: 일부 앱 알림이 지연될 수 있습니다.
        """.trimIndent())
        .setPositiveButton("개발자 옵션 열기") { _, _ ->
            openDeveloperOptions(context)
        }
        .setNegativeButton("취소", null)
        .show()
}
```

#### 2.4 절전 모드 안내
```kotlin
// 절전 모드 설정으로 이동
fun openBatterySaverSettings(context: Context) {
    val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
    context.startActivity(intent)
}

// 현재 절전 모드 상태 확인
fun isPowerSaveMode(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isPowerSaveMode
}
```

---

### 5단계: 고급 정보 (모니터링 전용)

#### 5.1 실행 중인 프로세스 목록
```kotlin
// 실행 중인 앱 프로세스 (제한적)
fun getRunningAppProcesses(context: Context): List<AppProcessInfo> {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val processes = activityManager.runningAppProcesses ?: return emptyList()

    return processes.map { process ->
        AppProcessInfo(
            processName = process.processName,
            pid = process.pid,
            importance = process.importance,
            importanceLabel = getImportanceLabel(process.importance)
        )
    }.sortedBy { it.importance }
}

fun getImportanceLabel(importance: Int): String {
    return when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "포그라운드"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "포그라운드 서비스"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "표시됨"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "서비스"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "캐시됨"
        else -> "백그라운드"
    }
}

data class AppProcessInfo(
    val processName: String,
    val pid: Int,
    val importance: Int,
    val importanceLabel: String
)
```

#### 5.2 네트워크 사용량 (Android 6.0+)
```kotlin
// 권한 필요: <uses-permission android:name="android.permission.READ_PHONE_STATE" />
// Android 10+에서는 제한적

fun getNetworkUsage(context: Context, uid: Int): NetworkUsage? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

    try {
        val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE)
            as NetworkStatsManager

        // 모바일 데이터 사용량
        val mobileStats = networkStatsManager.queryDetailsForUid(
            ConnectivityManager.TYPE_MOBILE,
            null,  // subscriberId - null for all
            0,     // startTime
            System.currentTimeMillis(),
            uid
        )

        var mobileRx = 0L
        var mobileTx = 0L
        val bucket = NetworkStats.Bucket()
        while (mobileStats.hasNextBucket()) {
            mobileStats.getNextBucket(bucket)
            mobileRx += bucket.rxBytes
            mobileTx += bucket.txBytes
        }
        mobileStats.close()

        // WiFi 사용량
        val wifiStats = networkStatsManager.queryDetailsForUid(
            ConnectivityManager.TYPE_WIFI,
            null,
            0,
            System.currentTimeMillis(),
            uid
        )

        var wifiRx = 0L
        var wifiTx = 0L
        while (wifiStats.hasNextBucket()) {
            wifiStats.getNextBucket(bucket)
            wifiRx += bucket.rxBytes
            wifiTx += bucket.txBytes
        }
        wifiStats.close()

        return NetworkUsage(mobileRx, mobileTx, wifiRx, wifiTx)
    } catch (e: Exception) {
        return null
    }
}

data class NetworkUsage(
    val mobileRx: Long,
    val mobileTx: Long,
    val wifiRx: Long,
    val wifiTx: Long
) {
    val totalMobile get() = mobileRx + mobileTx
    val totalWifi get() = wifiRx + wifiTx
    val total get() = totalMobile + totalWifi
}
```

#### 5.3 Wake Lock 모니터 (dumpsys 사용)
```kotlin
// dumpsys power에서 Wake Lock 정보 추출
fun getWakeLockInfo(): List<WakeLockInfo> {
    val wakeLocks = mutableListOf<WakeLockInfo>()

    try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys power 2>/dev/null"))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var inWakeLockSection = false
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            if (line?.contains("Wake Locks:") == true) {
                inWakeLockSection = true
                continue
            }

            if (inWakeLockSection) {
                if (line?.trim()?.isEmpty() == true || line?.startsWith("  ") == false) {
                    inWakeLockSection = false
                    continue
                }

                // Wake Lock 파싱
                // 형식: "  PARTIAL_WAKE_LOCK 'tag' pkg=com.example.app..."
                val match = Regex("(\\w+_WAKE_LOCK)\\s+'([^']+)'.*?pkg=([\\w.]+)").find(line ?: "")
                if (match != null) {
                    wakeLocks.add(WakeLockInfo(
                        type = match.groupValues[1],
                        tag = match.groupValues[2],
                        packageName = match.groupValues[3]
                    ))
                }
            }
        }
        reader.close()
        process.waitFor()
    } catch (e: Exception) {
        // 권한 없음 또는 파싱 실패
    }

    return wakeLocks
}

data class WakeLockInfo(
    val type: String,
    val tag: String,
    val packageName: String
)
```

---

## UI 구조

### 새 Fragment 생성: `PerformanceFragment.kt`

```
res/layout/fragment_performance.xml
├── ScrollView
│   └── LinearLayout (vertical)
│       ├── CardView: 시스템 상태 요약
│       │   ├── CPU 사용률 + 온도
│       │   ├── RAM 사용량
│       │   ├── 배터리 상태 + 온도
│       │   └── 스토리지 사용량
│       │
│       ├── CardView: 빠른 최적화
│       │   ├── Button: 애니메이션 설정 (개발자 옵션)
│       │   ├── Button: 배터리 최적화 관리
│       │   ├── Button: 백그라운드 제한 안내
│       │   └── Button: 절전 모드 설정
│       │
│       ├── CardView: 실행 중인 앱 (확장 가능)
│       │   └── RecyclerView: 프로세스 목록
│       │
│       └── CardView: 고급 정보
│           ├── Button: 네트워크 사용량 보기
│           └── Button: Wake Lock 모니터
```

### 메인 네비게이션에 탭 추가

`MainActivity.kt`의 ViewPager에 새 탭 추가:
- 탭 이름: "성능" 또는 "최적화"
- 아이콘: `ic_speed` 또는 `ic_memory`

---

## 파일 구조

```
app/src/main/java/com/minsoo/ultranavbar/
├── ui/
│   └── PerformanceFragment.kt          # 새로 생성
├── performance/                         # 새 패키지
│   ├── SystemMonitor.kt                # CPU, RAM, 온도 모니터링
│   ├── BatteryMonitor.kt               # 배터리 상태
│   ├── StorageMonitor.kt               # 스토리지 정보
│   ├── ProcessMonitor.kt               # 실행 중인 프로세스
│   ├── NetworkUsageMonitor.kt          # 네트워크 사용량
│   └── WakeLockMonitor.kt              # Wake Lock 모니터
└── res/
    ├── layout/
    │   ├── fragment_performance.xml
    │   └── item_process.xml            # 프로세스 목록 아이템
    └── values/
        └── strings.xml                 # 문자열 추가
```

---

## 필요한 권한

```xml
<!-- AndroidManifest.xml에 추가 -->

<!-- 네트워크 사용량 조회 (선택적) -->
<uses-permission android:name="android.permission.READ_PHONE_STATE" />

<!-- 패키지 사용 통계 (선택적, 미사용 앱 감지용) -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

---

## 문자열 리소스 (strings.xml)

```xml
<!-- 성능 최적화 -->
<string name="performance_title">성능 최적화</string>
<string name="system_status">시스템 상태</string>
<string name="cpu_usage">CPU 사용률</string>
<string name="cpu_temperature">CPU 온도</string>
<string name="memory_usage">메모리 사용량</string>
<string name="battery_status">배터리 상태</string>
<string name="battery_temperature">배터리 온도</string>
<string name="storage_usage">저장공간</string>

<string name="quick_optimization">빠른 최적화</string>
<string name="animation_settings">애니메이션 설정</string>
<string name="animation_settings_desc">개발자 옵션에서 애니메이션 속도 조절</string>
<string name="battery_optimization">배터리 최적화 관리</string>
<string name="battery_optimization_desc">앱별 배터리 최적화 설정</string>
<string name="background_limit">백그라운드 프로세스 제한</string>
<string name="background_limit_desc">백그라운드 앱 수 제한</string>
<string name="power_save_mode">절전 모드</string>
<string name="power_save_mode_desc">배터리 절약을 위한 절전 모드 설정</string>

<string name="running_apps">실행 중인 앱</string>
<string name="advanced_info">고급 정보</string>
<string name="network_usage">네트워크 사용량</string>
<string name="wake_lock_monitor">Wake Lock 모니터</string>

<!-- 한국어 -->
<string name="performance_title">성능 최적화</string>
```

---

## 구현 순서

1. **패키지 및 파일 생성**
   - `performance/` 패키지 생성
   - 모니터 클래스들 생성

2. **SystemMonitor 구현**
   - CPU, RAM, 온도 모니터링
   - 실시간 업데이트 (Handler + Runnable)

3. **UI 레이아웃 생성**
   - `fragment_performance.xml`
   - 카드뷰 구조

4. **PerformanceFragment 구현**
   - 모니터 클래스 연동
   - 설정 바로가기 버튼

5. **MainActivity에 탭 추가**
   - ViewPager adapter 수정

6. **테스트 및 최적화**
   - 메모리 누수 확인
   - 배터리 소모 최소화

---

## 주의사항

1. **실시간 모니터링 주기**: 너무 빈번하면 배터리 소모 증가
   - 권장: 1~2초 간격
   - 화면 꺼지면 중지

2. **권한 처리**: 일부 기능은 권한 거부 시 대체 메시지 표시

3. **기기 호환성**: 일부 경로(/sys/class/thermal 등)는 기기마다 다를 수 있음

4. **Android 버전 대응**: API 레벨에 따른 분기 처리 필요
