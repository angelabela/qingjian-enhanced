package app.qingjian.inputmethod.service

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import kotlin.concurrent.thread

/** 输入法内的轻量实时信息候选。网络与定位读取均放在后台线程，不阻塞按键。 */
class SmartInfoProvider(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun resolve(trigger: String, callback: (String) -> Unit) {
        thread(name = "qingjian-smart-info") {
            val value = runCatching { resolveBlocking(trigger) }
                .getOrElse { "暂时无法获取信息，请稍后重试" }
            main.post { callback(value) }
        }
    }

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun resolveBlocking(trigger: String): String = when (trigger) {
        "地点", "位置", "地址" -> currentAddress()
        "天气" -> currentWeather()
        "内存" -> "${memoryStatus()}；${storageStatus()}"
        "存储" -> storageStatus()
        "电量", "电池" -> batteryStatus()
        "网络" -> networkStatus()
        "时间" -> java.text.SimpleDateFormat("现在 HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        "日期" -> java.text.SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault()).format(java.util.Date())
        else -> ""
    }

    private fun currentAddress(): String {
        if (!hasLocationPermission()) return LOCATION_PERMISSION_HINT
        val location = currentLocation() ?: return "暂时无法获取当前位置，请确认系统定位已开启后重试"
        val address = reverseAddress(location)
        return address ?: "已获取定位（%.5f, %.5f），暂时无法解析详细地址".format(
            Locale.getDefault(), location.latitude, location.longitude
        )
    }

    private fun reverseAddress(location: Location): String? = runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()?.getAddressLine(0)
    }.getOrNull()

    private fun currentWeather(): String {
        if (!hasLocationPermission()) return LOCATION_PERMISSION_HINT
        val location = currentLocation() ?: return "暂时无法获取定位，无法查询当前位置天气"
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 4500
            requestMethod = "GET"
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                return when (status) {
                    401, 403 -> "天气服务暂时拒绝访问，请稍后重试"
                    429 -> "天气查询过于频繁，请稍后再试"
                    else -> "天气服务暂时不可用（HTTP $status）"
                }
            }
            if (body.isBlank()) return "天气服务没有返回数据，请检查网络后重试"
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val feels = current.optDouble("apparent_temperature", Double.NaN)
            val humidity = current.optInt("relative_humidity_2m", -1)
            val wind = current.optDouble("wind_speed_10m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val desc = weatherDescription(code)
            val address = reverseAddress(location)
            return buildString {
                append("天气")
                if (!address.isNullOrBlank()) append("（").append(address).append("）")
                append("：").append(desc)
                if (!temp.isNaN()) append("，气温 ").append(temp.toInt()).append("℃")
                if (!feels.isNaN()) append("，体感 ").append(feels.toInt()).append("℃")
                if (humidity >= 0) append("，湿度 ").append(humidity).append("%")
                if (!wind.isNaN()) append("，风速 ").append(wind.toInt()).append(" km/h")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun currentLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        // 优先使用两分钟内的定位；若无法取得新定位，仍允许用最近一次缓存定位查询天气，
        // 避免室内 GPS 首次定位失败时直接中断天气服务。
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60 * 1000L) return last

        val result = AtomicReference<Location?>()
        val latch = CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result.set(location)
                latch.countDown()
            }
            @Deprecated("Deprecated by Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        providers.forEach { provider ->
            runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
        }
        runCatching { latch.await(5, TimeUnit.SECONDS) }
        runCatching { manager.removeUpdates(listener) }
        return result.get() ?: last
    }

    private fun memoryStatus(): String {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return "运行内存：已用 ${formatBytes(info.totalMem - info.availMem)} / 总计 ${formatBytes(info.totalMem)}，可用 ${formatBytes(info.availMem)}"
    }

    private fun storageStatus(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        return "内部存储：已用 ${formatBytes(total - free)} / 总计 ${formatBytes(total)}，剩余 ${formatBytes(free)}"
    }

    private fun batteryStatus(): String {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "暂时无法读取电池状态"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "电量：${(level * 100 / scale).coerceIn(0, 100)}%${if (charging) "，正在充电" else ""}"
    }

    private fun networkStatus(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return "网络：未连接"
        val caps = manager.getNetworkCapabilities(network) ?: return "网络：连接状态未知"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "其他网络"
        }
        return "网络：已连接（$kind）"
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "晴间多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67 -> "雨"
        71, 73, 75, 77 -> "降雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷雨"
        else -> "天气状况未知"
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1.0) "%.2f GB".format(Locale.getDefault(), gb)
        else "%.0f MB".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)
    }

    companion object {
        const val LOCATION_PERMISSION_HINT = "点击授权定位权限后即可显示当前位置"
        val TRIGGERS = listOf("地点", "位置", "地址", "天气", "内存", "存储", "电量", "电池", "网络", "时间", "日期")
    }
}
