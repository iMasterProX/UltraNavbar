package com.minsoo.ultranavbar.util

import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku를 통한 입력 이벤트 주입 헬퍼
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    /**
     * Shizuku가 설치되어 있고 실행 중인지 확인
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    /**
     * Shizuku 권한이 있는지 확인
     */
    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku permission", e)
            false
        }
    }

    /**
     * Shizuku 권한 요청
     */
    fun requestShizukuPermission() {
        try {
            if (isShizukuAvailable()) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting Shizuku permission", e)
        }
    }

    /**
     * Shizuku를 통해 권한 있는 셸 명령어 실행
     * @return Pair(exitCode, output)
     */
    fun executeShellCommand(command: String): Pair<Int, String> {
        // Shizuku 권한이 있으면 Shizuku로 실행 시도
        if (hasShizukuPermission()) {
            val result = executeViaShizuku(command)
            if (result.first != -999) { // -999는 Shizuku 실패 표시
                return result
            }
            Log.d(TAG, "Shizuku execution failed, falling back to Runtime")
        }

        // Fallback: 일반 Runtime 실행
        return executeViaRuntime(command)
    }

    /**
     * Shizuku를 통한 셸 명령어 실행 (reflection 사용)
     */
    private fun executeViaShizuku(command: String): Pair<Int, String> {
        return try {
            Log.d(TAG, "Executing via Shizuku: $command")

            // Shizuku.newProcess는 private이므로 reflection으로 접근
            val shizukuClass = Shizuku::class.java
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val result = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            Log.d(TAG, "Shizuku shell result: exitCode=$exitCode, output=${result.toString().trim()}")

            Pair(exitCode, result.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku shell command failed", e)
            Pair(-999, e.message ?: "Shizuku error") // -999: Shizuku 실패 표시
        }
    }

    /**
     * Runtime을 통한 일반 셸 명령어 실행
     */
    private fun executeViaRuntime(command: String): Pair<Int, String> {
        return try {
            Log.d(TAG, "Executing via Runtime: $command")

            val result = StringBuilder()
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                result.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            Log.d(TAG, "Runtime shell result: exitCode=$exitCode, output=${result.toString().trim()}")

            Pair(exitCode, result.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Runtime shell command failed", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * 키 조합 주입 (여러 방법 시도)
     * @param keyCode 메인 키코드
     * @param metaState 메타 상태 (Ctrl, Shift, Alt)
     * @return 성공 여부
     */
    fun injectKeyEvent(keyCode: Int, metaState: Int): Boolean {
        // 방법 1: sendevent를 사용한 낮은 레벨 주입 시도
        val sendEventSuccess = injectViaSendEvent(keyCode, metaState)
        if (sendEventSuccess) {
            Log.d(TAG, "sendevent injection succeeded")
            return true
        }

        // 방법 2: keyboard 소스를 명시적으로 지정
        val keyboardSourceSuccess = injectViaInputCommand(keyCode, metaState, "keyboard")
        if (keyboardSourceSuccess) {
            Log.d(TAG, "keyboard source injection succeeded")
            return true
        }

        // 방법 3: 기본 input 명령어
        return injectViaInputCommand(keyCode, metaState, null)
    }

    /**
     * input 명령어로 키 주입
     */
    private fun injectViaInputCommand(keyCode: Int, metaState: Int, source: String?): Boolean {
        val modifierKeys = mutableListOf<Int>()
        if (metaState and KeyEvent.META_CTRL_ON != 0) modifierKeys.add(113) // CTRL_LEFT
        if (metaState and KeyEvent.META_SHIFT_ON != 0) modifierKeys.add(59) // SHIFT_LEFT
        if (metaState and KeyEvent.META_ALT_ON != 0) modifierKeys.add(57)   // ALT_LEFT

        val sourceFlag = if (source != null) "-s $source " else ""

        val command = if (modifierKeys.isNotEmpty()) {
            val keyCodes = modifierKeys + keyCode
            "input ${sourceFlag}keycombination ${keyCodes.joinToString(" ")}"
        } else {
            "input ${sourceFlag}keyevent $keyCode"
        }

        Log.d(TAG, "Injecting key via input: $command")
        val (exitCode, _) = executeShellCommand(command)
        return exitCode == 0
    }

    /**
     * sendevent를 사용한 낮은 레벨 키 주입
     * 물리적 키보드 입력처럼 보이게 하기 위함
     */
    private fun injectViaSendEvent(keyCode: Int, metaState: Int): Boolean {
        // 키보드 디바이스 찾기
        val keyboardDevice = findKeyboardDevice() ?: return false
        Log.d(TAG, "Found keyboard device: $keyboardDevice")

        // Linux 키코드로 변환 (Android와 약간 다름)
        val linuxKeyCode = androidKeyCodeToLinux(keyCode)
        val ctrlCode = 29  // KEY_LEFTCTRL in Linux
        val shiftCode = 42 // KEY_LEFTSHIFT in Linux
        val altCode = 56   // KEY_LEFTALT in Linux

        val hasCtrl = metaState and KeyEvent.META_CTRL_ON != 0
        val hasShift = metaState and KeyEvent.META_SHIFT_ON != 0
        val hasAlt = metaState and KeyEvent.META_ALT_ON != 0

        // EV_KEY = 1, EV_SYN = 0
        // KEY_DOWN = 1, KEY_UP = 0
        val commands = StringBuilder()

        // Modifier key down
        if (hasCtrl) commands.append("sendevent $keyboardDevice 1 $ctrlCode 1 && ")
        if (hasShift) commands.append("sendevent $keyboardDevice 1 $shiftCode 1 && ")
        if (hasAlt) commands.append("sendevent $keyboardDevice 1 $altCode 1 && ")

        // Sync
        commands.append("sendevent $keyboardDevice 0 0 0 && ")

        // Main key down
        commands.append("sendevent $keyboardDevice 1 $linuxKeyCode 1 && ")
        commands.append("sendevent $keyboardDevice 0 0 0 && ")

        // Main key up
        commands.append("sendevent $keyboardDevice 1 $linuxKeyCode 0 && ")
        commands.append("sendevent $keyboardDevice 0 0 0 && ")

        // Modifier key up (reverse order)
        if (hasAlt) commands.append("sendevent $keyboardDevice 1 $altCode 0 && ")
        if (hasShift) commands.append("sendevent $keyboardDevice 1 $shiftCode 0 && ")
        if (hasCtrl) commands.append("sendevent $keyboardDevice 1 $ctrlCode 0 && ")

        // Final sync
        commands.append("sendevent $keyboardDevice 0 0 0")

        Log.d(TAG, "Injecting via sendevent")
        val (exitCode, output) = executeShellCommand(commands.toString())
        Log.d(TAG, "sendevent result: exitCode=$exitCode, output=$output")
        return exitCode == 0
    }

    /**
     * 시스템에서 키보드 입력 디바이스 찾기
     */
    private fun findKeyboardDevice(): String? {
        // /dev/input/eventX 디바이스 중 키보드 찾기
        val (exitCode, output) = executeShellCommand("cat /proc/bus/input/devices")
        if (exitCode != 0) return null

        // 키보드 디바이스 파싱 - "Handlers=...kbd...eventX" 패턴 찾기
        val lines = output.split("\n")
        var currentName = ""
        for (line in lines) {
            when {
                line.startsWith("N: Name=") -> {
                    currentName = line.substringAfter("N: Name=").trim('"')
                }
                line.startsWith("H: Handlers=") && line.contains("kbd") -> {
                    // eventX 추출
                    val match = Regex("event(\\d+)").find(line)
                    if (match != null) {
                        val eventNum = match.groupValues[1]
                        Log.d(TAG, "Found keyboard: $currentName -> /dev/input/event$eventNum")
                        return "/dev/input/event$eventNum"
                    }
                }
            }
        }

        // 키보드를 못 찾으면 가상 키보드 디바이스 시도
        Log.d(TAG, "No physical keyboard found, trying virtual input")
        return null
    }

    /**
     * Android KeyCode를 Linux scancode로 변환
     */
    private fun androidKeyCodeToLinux(androidKeyCode: Int): Int {
        // 주요 키 매핑 (Android KeyCode -> Linux scancode)
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_Z -> 44  // KEY_Z
            KeyEvent.KEYCODE_Y -> 21  // KEY_Y
            KeyEvent.KEYCODE_E -> 18  // KEY_E
            KeyEvent.KEYCODE_A -> 30  // KEY_A
            KeyEvent.KEYCODE_B -> 48  // KEY_B
            KeyEvent.KEYCODE_C -> 46  // KEY_C
            KeyEvent.KEYCODE_D -> 32  // KEY_D
            KeyEvent.KEYCODE_S -> 31  // KEY_S
            KeyEvent.KEYCODE_V -> 47  // KEY_V
            KeyEvent.KEYCODE_X -> 45  // KEY_X
            KeyEvent.KEYCODE_DEL -> 14 // KEY_BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> 111 // KEY_DELETE
            KeyEvent.KEYCODE_ENTER -> 28 // KEY_ENTER
            KeyEvent.KEYCODE_ESCAPE -> 1 // KEY_ESC
            KeyEvent.KEYCODE_TAB -> 15 // KEY_TAB
            KeyEvent.KEYCODE_SPACE -> 57 // KEY_SPACE
            else -> androidKeyCode // 변환 없이 사용
        }
    }

    /**
     * Shizuku 권한 결과 리스너 등록
     */
    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding permission listener", e)
        }
    }

    /**
     * Shizuku 권한 결과 리스너 제거
     */
    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing permission listener", e)
        }
    }

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

        val xi = x.toInt()
        val yi = y.toInt()

        // input tap 명령 실행
        val command = "input tap $xi $yi"
        val (exitCode, _) = executeShellCommand(command)

        Log.d(TAG, "injectTap($x, $y): exitCode=$exitCode")
        return exitCode == 0
    }

    /**
     * Shizuku를 통한 스와이프 이벤트 주입 (필요시)
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): Boolean {
        if (!hasShizukuPermission()) {
            Log.w(TAG, "Shizuku permission not granted for swipe")
            return false
        }

        val command = "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs"
        val (exitCode, _) = executeShellCommand(command)

        Log.d(TAG, "injectSwipe: exitCode=$exitCode")
        return exitCode == 0
    }
}
