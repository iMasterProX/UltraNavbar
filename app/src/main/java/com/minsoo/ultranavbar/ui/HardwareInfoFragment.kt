package com.minsoo.ultranavbar.ui

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.minsoo.ultranavbar.R
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class HardwareInfoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_hardware_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val textInfo = view.findViewById<TextView>(R.id.textHardwareInfo)
        textInfo.text = buildHardwareInfo()
    }

    private fun buildHardwareInfo(): String {
        val sb = StringBuilder()

        // Device Info
        sb.appendSection("Device")
        sb.appendItem("Model", Build.MODEL)
        sb.appendItem("Manufacturer", Build.MANUFACTURER)
        sb.appendItem("Brand", Build.BRAND)
        sb.appendItem("Device", Build.DEVICE)
        sb.appendItem("Product", Build.PRODUCT)

        // Android Info
        sb.appendSection("Android")
        sb.appendItem("Version", Build.VERSION.RELEASE)
        sb.appendItem("SDK", Build.VERSION.SDK_INT.toString())
        sb.appendItem("Build ID", Build.ID)
        sb.appendItem("Fingerprint", Build.FINGERPRINT)

        // CPU Info
        sb.appendSection("CPU / SoC")
        sb.appendItem("Hardware", Build.HARDWARE)
        sb.appendItem("Board", Build.BOARD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.appendItem("SoC Manufacturer", Build.SOC_MANUFACTURER)
            sb.appendItem("SoC Model", Build.SOC_MODEL)
        }
        getCpuInfo()?.let { sb.appendItem("CPU Info", it) }

        // Display Info
        sb.appendSection("Display")
        getDisplayPanelInfo().forEach { (key, value) ->
            sb.appendItem(key, value)
        }

        // Touch/Stylus Info
        sb.appendSection("Touch / Stylus")
        getInputDevices().forEach { sb.appendItem("Input", it) }

        // Sensors
        sb.appendSection("Sensors")
        getSensorInfo().forEach { sb.appendItem("Sensor", it) }

        return sb.toString()
    }

    private fun StringBuilder.appendSection(title: String) {
        if (isNotEmpty()) append("\n")
        append("━━━ $title ━━━\n")
    }

    private fun StringBuilder.appendItem(key: String, value: String) {
        append("$key: $value\n")
    }

    private fun getCpuInfo(): String? {
        return try {
            File("/proc/cpuinfo").bufferedReader().useLines { lines ->
                lines.filter { it.startsWith("Hardware") || it.startsWith("model name") }
                    .map { it.substringAfter(":").trim() }
                    .firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getDisplayPanelInfo(): Map<String, String> {
        val info = linkedMapOf<String, String>()

        // Get display info from SurfaceFlinger
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys SurfaceFlinger 2>/dev/null | head -30"))
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.contains("Display") && line.contains("pnpId") -> {
                            val pnpId = Regex("pnpId=(\\w+)").find(line)?.groupValues?.get(1)
                            val displayName = Regex("displayName=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                            pnpId?.let { info["PnP ID"] = it }
                            if (!displayName.isNullOrEmpty()) info["Display Name"] = displayName
                        }
                        line.contains("Current mode:") -> {
                            val mode = Regex("width=(\\d+), height=(\\d+)").find(line)
                            val fps = Regex("fps=([\\d.]+)").find(line)?.groupValues?.get(1)
                            mode?.let {
                                info["Resolution"] = "${it.groupValues[1]} x ${it.groupValues[2]}"
                            }
                            fps?.let { info["Refresh Rate"] = "${it}Hz" }
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        // Get display metrics
        try {
            val dm = requireContext().resources.displayMetrics
            info["Density"] = "${dm.densityDpi} dpi"
            info["Physical Size"] = String.format("%.1f x %.1f mm",
                dm.widthPixels / dm.xdpi * 25.4f,
                dm.heightPixels / dm.ydpi * 25.4f)
        } catch (e: Exception) { }

        // Check touch panel IC from dumpsys input (권한 문제 우회)
        val touchInfo = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec("dumpsys input")
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    if (line.trim().matches(Regex("Device \\d+: .+"))) {
                        val name = line.substringAfter(": ").trim()
                        val lower = name.lowercase()
                        // Detect touch/stylus IC manufacturer
                        when {
                            lower.contains("himax") -> {
                                if (lower.contains("stylus")) touchInfo.add("Himax (Stylus): $name")
                                else if (lower.contains("touch")) touchInfo.add("Himax (Touch): $name")
                            }
                            lower.contains("goodix") -> touchInfo.add("Goodix: $name")
                            lower.contains("synaptics") -> touchInfo.add("Synaptics: $name")
                            lower.contains("focaltech") -> touchInfo.add("FocalTech: $name")
                            lower.contains("atmel") -> touchInfo.add("Atmel: $name")
                            lower.contains("novatek") -> touchInfo.add("Novatek: $name")
                            lower.contains("ilitek") -> touchInfo.add("Ilitek: $name")
                            lower.contains("wacom") -> touchInfo.add("Wacom: $name")
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        if (touchInfo.isNotEmpty()) {
            info["Touch/Stylus IC"] = touchInfo.joinToString("\n")
        }

        // Try to get panel manufacturer from sysfs
        val panelManufacturer = tryGetPanelManufacturer()
        if (panelManufacturer != null) {
            // QCM은 디스플레이 컨트롤러이므로 별도 표시
            if (panelManufacturer.contains("QCM") || panelManufacturer.contains("Qualcomm")) {
                info["Display Controller"] = "Qualcomm (QCM)"
                info["Panel Manufacturer"] = "※ 패널 제조사 미확인 (루트 권한 필요)\n   - 일반적으로 BOE, LG Display, Samsung 등 사용"
            } else {
                info["Panel Manufacturer"] = panelManufacturer
            }
        } else {
            info["Panel Manufacturer"] = "※ 패널 제조사 확인 불가 (루트 권한 또는 특정 경로 필요)"
        }

        return info
    }

    /**
     * 디스플레이 패널 제조사 정보 가져오기
     */
    private fun tryGetPanelManufacturer(): String? {
        val detectedInfo = mutableListOf<String>()

        // 1. 시도할 sysfs 경로 목록 (확장)
        val sysfsPaths = listOf(
            // 일반 경로
            "/sys/class/graphics/fb0/panel_info",
            "/sys/class/graphics/fb0/panel_name",
            "/sys/class/graphics/fb0/msm_fb_panel_info",
            "/sys/devices/virtual/graphics/fb0/panel_info",
            "/sys/devices/virtual/graphics/fb0/msm_fb_panel_info",
            // DRM 경로
            "/sys/class/drm/card0-DSI-1/panel_info",
            "/sys/class/drm/card0-DSI-1/panel_name",
            "/sys/class/drm/card0-eDP-1/panel_info",
            "/sys/class/drm/card0-eDP-1/edid",
            // Qualcomm 경로
            "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/panel_info",
            "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/panel_name",
            "/sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/card0-DSI-1/panel_info",
            // MediaTek 경로
            "/sys/devices/platform/mtk_disp_mgr.0/panel_info",
            "/sys/devices/platform/mtkfb/panel_info",
            // LG 전용 경로
            "/sys/class/lcd/panel/panel_info",
            "/sys/class/lcd/panel/name",
            "/sys/devices/platform/soc/soc:qcom,dsi-display/panel_info",
            "/proc/display_info",
            "/proc/cmdline"  // 부트 파라미터에 패널 정보가 있을 수 있음
        )

        for (path in sysfsPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val content = file.readText().trim()
                    if (content.isNotEmpty()) {
                        val manufacturer = extractPanelManufacturer(content)
                        if (manufacturer != null) {
                            return "$manufacturer (from ${file.name})"
                        }
                        // cmdline에서 패널 정보 추출
                        if (path.contains("cmdline")) {
                            val panelMatch = Regex("mdss_mdp\\.panel=\\d+:(\\w+)").find(content)
                                ?: Regex("lge\\.panel=(\\w+)").find(content)
                                ?: Regex("panel_name=(\\w+)").find(content)
                            panelMatch?.let {
                                val panelName = it.groupValues[1]
                                extractPanelManufacturer(panelName)?.let { mfr ->
                                    return "$mfr (from cmdline: $panelName)"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        // 2. getprop으로 시스템 속성 확인
        val propKeys = listOf(
            // LG 전용
            "ro.lge.panel.type",
            "ro.lge.panel_name",
            "ro.lge.lcd_type",
            "lge.panel.name",
            "persist.lge.panel",
            // Qualcomm/일반
            "vendor.display.panel.name",
            "vendor.display.panel_name",
            "persist.vendor.display.panel",
            "ro.board.display",
            "sys.display.panel_info",
            // Samsung
            "ro.product.first_api_level",  // 참고용
            "ro.hardware.chipname"  // 참고용
            // ro.hardware.egl 제외 (GPU 정보, 패널 아님)
        )

        for (key in propKeys) {
            val value = getProp(key)
            if (!value.isNullOrEmpty()) {
                val manufacturer = extractPanelManufacturer(value)
                if (manufacturer != null) {
                    return "$manufacturer (from $key)"
                }
                detectedInfo.add("$key: $value")
            }
        }

        // 3. dumpsys display 분석
        try {
            val process = Runtime.getRuntime().exec("dumpsys display")
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    val manufacturer = extractPanelManufacturer(line)
                    if (manufacturer != null && line.contains("panel", ignoreCase = true)) {
                        return "$manufacturer (from dumpsys display)"
                    }
                }
            }
        } catch (e: Exception) { }

        // 4. dumpsys SurfaceFlinger 분석
        try {
            val process = Runtime.getRuntime().exec("dumpsys SurfaceFlinger")
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.take(100).forEach { line ->
                    // pnpId 확인
                    val pnpIdMatch = Regex("pnpId=([A-Z]{3})").find(line)
                    pnpIdMatch?.let {
                        val pnpId = it.groupValues[1]
                        // QCM은 디스플레이 컨트롤러이므로 패널 제조사로 반환하지 않고 별도 처리
                        if (pnpId != "QCM") {
                            return "${decodePnpId(pnpId)} (from pnpId: $pnpId)"
                        } else {
                            detectedInfo.add("Display Controller: Qualcomm (QCM)")
                        }
                    }
                    // 디스플레이 이름에서 제조사 확인
                    val displayNameMatch = Regex("displayName=\"([^\"]+)\"").find(line)
                    displayNameMatch?.let {
                        val name = it.groupValues[1]
                        if (name.isNotEmpty()) {
                            val mfr = extractPanelManufacturer(name)
                            if (mfr != null) return "$mfr (from SurfaceFlinger: $name)"
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        // 5. /sys/class/backlight에서 패널 정보 확인
        try {
            val backlightDir = File("/sys/class/backlight")
            if (backlightDir.exists() && backlightDir.isDirectory) {
                backlightDir.listFiles()?.forEach { dir ->
                    if (dir.name.contains("panel", ignoreCase = true)) {
                        detectedInfo.add("Backlight device: ${dir.name}")
                        // 일부 제조사 이름이 디렉토리명에 포함될 수 있음
                        val mfr = extractPanelManufacturer(dir.name)
                        if (mfr != null) return "$mfr (from backlight: ${dir.name})"
                    }
                }
            }
        } catch (e: Exception) { }

        // 6. 추가 디버그: 모든 display 관련 getprop 검색
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getprop | grep -iE 'panel|display|lcd|screen' 2>/dev/null"))
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotEmpty() && !line.contains("egl", ignoreCase = true)) {
                        val mfr = extractPanelManufacturer(line)
                        if (mfr != null) {
                            return "$mfr (from getprop: ${line.take(50)})"
                        }
                        detectedInfo.add(line.trim())
                    }
                }
            }
        } catch (e: Exception) { }

        // 7. /sys/class/drm 하위 디렉토리 탐색
        try {
            val drmDir = File("/sys/class/drm")
            if (drmDir.exists() && drmDir.isDirectory) {
                drmDir.listFiles()?.filter { it.name.startsWith("card") }?.forEach { cardDir ->
                    // panel_info, name, edid 등 파일 확인
                    listOf("panel_info", "panel_name", "name", "edid").forEach { fileName ->
                        val file = File(cardDir, fileName)
                        if (file.exists() && file.canRead()) {
                            try {
                                val content = file.readText().trim()
                                if (content.isNotEmpty()) {
                                    val mfr = extractPanelManufacturer(content)
                                    if (mfr != null) {
                                        return "$mfr (from ${cardDir.name}/$fileName)"
                                    }
                                    detectedInfo.add("${cardDir.name}/$fileName: ${content.take(50)}")
                                }
                            } catch (e: Exception) { }
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        // 수집된 정보가 있으면 반환
        if (detectedInfo.isNotEmpty()) {
            return "검색된 정보:\n${detectedInfo.take(5).joinToString("\n")}"
        }

        return null
    }

    /**
     * 문자열에서 패널 제조사 추출
     */
    private fun extractPanelManufacturer(text: String): String? {
        val upperText = text.uppercase()
        return when {
            upperText.contains("BOE") -> "BOE Technology"
            upperText.contains("LGD") || upperText.contains("LG DISPLAY") -> "LG Display"
            upperText.contains("SDC") || (upperText.contains("SAMSUNG") && upperText.contains("DISPLAY")) -> "Samsung Display"
            upperText.contains("SHARP") || upperText.contains("SHP") -> "Sharp"
            upperText.contains("TIANMA") || upperText.contains("TM") && upperText.contains("PANEL") -> "Tianma"
            upperText.contains("AUO") || upperText.contains("AU OPTRONICS") -> "AU Optronics"
            upperText.contains("INNOLUX") || upperText.contains("CMN") -> "Innolux"
            upperText.contains("JDI") || upperText.contains("JAPAN DISPLAY") -> "Japan Display Inc."
            upperText.contains("CSOT") || upperText.contains("TCL") -> "CSOT (TCL)"
            upperText.contains("VISIONOX") -> "Visionox"
            upperText.contains("EDO") || upperText.contains("EVERDISPLAY") -> "Everdisplay"
            upperText.contains("HKC") -> "HKC"
            upperText.contains("TRULY") -> "Truly"
            upperText.contains("CPT") || upperText.contains("CHUNGHWA") -> "Chunghwa Picture Tubes"
            else -> null
        }
    }

    /**
     * PnP ID를 제조사 이름으로 변환
     */
    private fun decodePnpId(pnpId: String): String {
        return when (pnpId.uppercase()) {
            "LGD" -> "LG Display"
            "SDC" -> "Samsung Display"
            "BOE" -> "BOE Technology"
            "AUO" -> "AU Optronics"
            "CMN" -> "Innolux (Chi Mei)"
            "SHP" -> "Sharp"
            "JDI" -> "Japan Display Inc."
            "CSO" -> "CSOT"
            "HKC" -> "HKC"
            "INL" -> "InnoLux"
            "QCM" -> "Qualcomm (Display Controller)"  // 디스플레이 컨트롤러, 패널 제조사 아님
            else -> "Unknown ($pnpId)"
        }
    }

    private fun getInputDevices(): List<String> {
        val devices = mutableListOf<String>()

        // 방법 1: dumpsys input 사용
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys input 2>/dev/null"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmedLine = line?.trim() ?: continue
                // "Device N: name" 패턴 또는 "N: name" 패턴 매칭
                val deviceMatch = Regex("^(?:Device\\s+)?(\\d+):\\s+(.+)$").find(trimmedLine)
                if (deviceMatch != null) {
                    val name = deviceMatch.groupValues[2].trim()
                    val lower = name.lowercase()

                    // 터치/스타일러스/키보드 관련 장치만 필터링
                    val type = when {
                        lower.contains("stylus") -> "[Stylus]"
                        lower.contains("touchscreen") || lower.contains("touch") -> "[Touch]"
                        lower.contains("keyboard") && !lower.contains("jack") -> "[Keyboard]"
                        lower.contains("gpio") || lower.contains("button") && !lower.contains("jack") -> "[Button]"
                        lower.contains("pen") && !lower.contains("pon") -> "[Pen]"
                        else -> null
                    }

                    if (type != null) {
                        // 제조사 정보 추출
                        val vendor = when {
                            lower.contains("himax") -> " (Himax)"
                            lower.contains("goodix") -> " (Goodix)"
                            lower.contains("synaptics") -> " (Synaptics)"
                            lower.contains("focaltech") -> " (FocalTech)"
                            lower.contains("wacom") -> " (Wacom)"
                            lower.contains("novatek") -> " (Novatek)"
                            lower.contains("atmel") -> " (Atmel)"
                            lower.contains("ilitek") -> " (Ilitek)"
                            else -> ""
                        }
                        devices.add("$type $name$vendor")
                    }
                }
            }
            reader.close()
            process.waitFor()
        } catch (e: Exception) {
            // dumpsys 실패
        }

        // 방법 2: getevent -p 사용 (fallback)
        if (devices.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "getevent -p 2>/dev/null"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.contains("name:") == true) {
                        val name = line!!.substringAfter("name:").trim().trim('"').trim()
                        val lower = name.lowercase()
                        val type = when {
                            lower.contains("stylus") -> "[Stylus]"
                            lower.contains("touchscreen") || lower.contains("touch") -> "[Touch]"
                            lower.contains("keyboard") && !lower.contains("jack") -> "[Keyboard]"
                            else -> null
                        }
                        if (type != null) {
                            val vendor = when {
                                lower.contains("himax") -> " (Himax)"
                                lower.contains("goodix") -> " (Goodix)"
                                lower.contains("synaptics") -> " (Synaptics)"
                                lower.contains("wacom") -> " (Wacom)"
                                else -> ""
                            }
                            devices.add("$type $name$vendor")
                        }
                    }
                }
                reader.close()
                process.waitFor()
            } catch (e: Exception) {
                // getevent 실패
            }
        }

        // 방법 3: /proc/bus/input/devices
        if (devices.isEmpty()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat /proc/bus/input/devices 2>/dev/null"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                var currentName = ""
                while (reader.readLine().also { line = it } != null) {
                    when {
                        line?.startsWith("N: Name=") == true -> {
                            currentName = line!!.substringAfter("N: Name=").trim('"')
                        }
                        line?.startsWith("H: Handlers=") == true && currentName.isNotEmpty() -> {
                            val lower = currentName.lowercase()
                            val type = when {
                                lower.contains("stylus") -> "[Stylus]"
                                lower.contains("touchscreen") || lower.contains("touch") -> "[Touch]"
                                lower.contains("keyboard") -> "[Keyboard]"
                                else -> null
                            }
                            if (type != null) {
                                val vendor = when {
                                    lower.contains("himax") -> " (Himax)"
                                    lower.contains("goodix") -> " (Goodix)"
                                    else -> ""
                                }
                                devices.add("$type $currentName$vendor")
                            }
                            currentName = ""
                        }
                    }
                }
                reader.close()
                process.waitFor()
            } catch (e: Exception) {
                // /proc 접근 실패
            }
        }

        // 방법 4: Android InputManager API 사용
        if (devices.isEmpty()) {
            try {
                val inputManager = requireContext().getSystemService(android.content.Context.INPUT_SERVICE) as? android.hardware.input.InputManager
                inputManager?.inputDeviceIds?.forEach { id ->
                    val device = inputManager.getInputDevice(id)
                    if (device != null) {
                        val name = device.name
                        val lower = name.lowercase()
                        val type = when {
                            lower.contains("stylus") -> "[Stylus]"
                            lower.contains("touchscreen") || lower.contains("touch") -> "[Touch]"
                            lower.contains("keyboard") && !lower.contains("jack") -> "[Keyboard]"
                            else -> null
                        }
                        if (type != null) {
                            val vendor = when {
                                lower.contains("himax") -> " (Himax)"
                                lower.contains("goodix") -> " (Goodix)"
                                lower.contains("wacom") -> " (Wacom)"
                                else -> ""
                            }
                            devices.add("$type $name$vendor")
                        }
                    }
                }
            } catch (e: Exception) {
                // InputManager 실패
            }
        }

        return if (devices.isEmpty()) {
            listOf("입력 장치 정보를 읽을 수 없습니다")
        } else {
            devices.distinct()
        }
    }

    private fun getSensorInfo(): List<String> {
        val sensors = mutableListOf<String>()
        try {
            val sensorManager = requireContext().getSystemService(android.content.Context.SENSOR_SERVICE)
                as android.hardware.SensorManager
            sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL).forEach { sensor ->
                sensors.add("${sensor.name} (${sensor.vendor})")
            }
        } catch (e: Exception) {
            sensors.add("Unable to read sensors")
        }
        return sensors.take(15) // Limit to avoid too long list
    }

    private fun getProp(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            BufferedReader(InputStreamReader(process.inputStream)).readLine()?.trim()
        } catch (e: Exception) {
            null
        }
    }
}
