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

        // Check touch panel IC from input devices
        val touchInfo = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec("cat /proc/bus/input/devices")
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("N: Name=")) {
                        val name = line.substringAfter("N: Name=").trim('"')
                        val lower = name.lowercase()
                        // Detect display/touch related components
                        when {
                            lower.contains("himax") -> touchInfo.add("Himax: $name")
                            lower.contains("goodix") -> touchInfo.add("Goodix: $name")
                            lower.contains("synaptics") -> touchInfo.add("Synaptics: $name")
                            lower.contains("focaltech") -> touchInfo.add("FocalTech: $name")
                            lower.contains("atmel") -> touchInfo.add("Atmel: $name")
                            lower.contains("novatek") -> touchInfo.add("Novatek: $name")
                            lower.contains("ilitek") -> touchInfo.add("Ilitek: $name")
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        if (touchInfo.isNotEmpty()) {
            info["Touch IC"] = touchInfo.joinToString("\n")
        }

        // Note about panel detection
        info["Panel Manufacturer"] = "※ BOE/LGD 패널 구분은 Root 권한 필요"

        return info
    }

    private fun getInputDevices(): List<String> {
        val devices = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec("cat /proc/bus/input/devices")
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                var currentName = ""
                lines.forEach { line ->
                    when {
                        line.startsWith("N: Name=") -> {
                            currentName = line.substringAfter("N: Name=").trim('"')
                        }
                        line.startsWith("H: Handlers=") && currentName.isNotEmpty() -> {
                            // Add type indicator
                            val lower = currentName.lowercase()
                            val type = when {
                                lower.contains("stylus") -> "[Stylus]"
                                lower.contains("touch") -> "[Touch]"
                                lower.contains("keyboard") -> "[Keyboard]"
                                lower.contains("gpio") || lower.contains("volume") -> "[Button]"
                                else -> ""
                            }
                            devices.add("$type $currentName".trim())
                            currentName = ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            devices.add("Unable to read input devices")
        }
        return devices.distinct()
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
