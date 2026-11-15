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
import java.util.Calendar
import java.util.Date
import android.icu.util.ChineseCalendar
import android.icu.util.Calendar as IcuCalendar
import kotlin.random.Random
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
        "时间" -> varied(listOf(
            java.text.SimpleDateFormat("现在 HH:mm:ss", Locale.getDefault()).format(Date()),
            java.text.SimpleDateFormat("⏱ HH:mm:ss · 今天也要顺顺利利", Locale.getDefault()).format(Date()),
            java.text.SimpleDateFormat("此刻是 HH:mm:ss", Locale.getDefault()).format(Date()),
        ))
        "日期", "农历", "节日" -> currentDateInfo()
        "生肖" -> currentZodiac()
        "星期" -> java.text.SimpleDateFormat("EEEE · yyyy年M月d日", Locale.getDefault()).format(Date())
        "星座" -> currentZodiacSign()
        else -> ""
    }


    /** 公历、农历、生肖与节日均按设备当前日期计算；无需联网，离线可用。 */
    private fun currentDateInfo(): String {
        val now = Date()
        val solar = Calendar.getInstance()
        solar.time = now
        val month = solar.get(Calendar.MONTH) + 1
        val day = solar.get(Calendar.DAY_OF_MONTH)
        val lunar = ChineseCalendar().apply { time = now }
        val lunarMonth = lunar.get(IcuCalendar.MONTH) + 1
        val lunarDay = lunar.get(IcuCalendar.DAY_OF_MONTH)
        val lunarMonths = arrayOf("正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月")
        val lunarDays = arrayOf(
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
        )
        val lunarText = (lunarMonths.getOrNull(lunarMonth - 1) ?: "${lunarMonth}月") +
            (lunarDays.getOrNull(lunarDay - 1) ?: "${lunarDay}日")
        val holiday = solarHoliday(month, day) ?: lunarHoliday(lunarMonth, lunarDay)
        val solarText = java.text.SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now)
        val extras = listOfNotNull(holiday?.let { "🎉 $it" }, "农历$lunarText")
        return varied(listOf(
            "$solarText · ${extras.joinToString(" · ")}",
            "今天是$solarText，$lunarText，${holiday?.let { "今天是$it" } ?: "愿你今天有好心情"}",
            "📅 $solarText  |  农历$lunarText${holiday?.let { "  |  $it" } ?: ""}",
        ))
    }

    private fun currentZodiac(): String {
        val lunar = ChineseCalendar().apply { time = Date() }
        val cycleYear = lunar.get(IcuCalendar.YEAR)
        val animals = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")
        val animal = animals[(cycleYear - 1).mod(12)]
        return varied(listOf(
            "今年农历生肖是$animal，属于${animal}年",
            "🐉 农历生肖：$animal（${animal}年）",
            "按当前农历年份计算：${animal}年",
        ))
    }

    private fun currentZodiacSign(): String {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val sign = when {
            month == 1 && day >= 20 || month == 2 && day <= 18 -> "水瓶座"
            month == 2 && day >= 19 || month == 3 && day <= 20 -> "双鱼座"
            month == 3 && day >= 21 || month == 4 && day <= 19 -> "白羊座"
            month == 4 && day >= 20 || month == 5 && day <= 20 -> "金牛座"
            month == 5 && day >= 21 || month == 6 && day <= 21 -> "双子座"
            month == 6 && day >= 22 || month == 7 && day <= 22 -> "巨蟹座"
            month == 7 && day >= 23 || month == 8 && day <= 22 -> "狮子座"
            month == 8 && day >= 23 || month == 9 && day <= 22 -> "处女座"
            month == 9 && day >= 23 || month == 10 && day <= 23 -> "天秤座"
            month == 10 && day >= 24 || month == 11 && day <= 22 -> "天蝎座"
            month == 11 && day >= 23 || month == 12 && day <= 21 -> "射手座"
            else -> "摩羯座"
        }
        return varied(listOf("♈ 当前日期对应的星座：$sign", "星座小提示：$sign", "按今天的公历生日区间，属于$sign"))
    }

    private fun solarHoliday(month: Int, day: Int): String? = when (month to day) {
        1 to 1 -> "元旦"
        2 to 14 -> "情人节"
        3 to 8 -> "国际妇女节"
        3 to 12 -> "植树节"
        4 to 1 -> "愚人节"
        5 to 1 -> "劳动节"
        5 to 4 -> "五四青年节"
        6 to 1 -> "儿童节"
        9 to 10 -> "教师节"
        10 to 1 -> "国庆节"
        12 to 24 -> "平安夜"
        12 to 25 -> "圣诞节"
        else -> null
    }

    private fun lunarHoliday(month: Int, day: Int): String? = when {
        month == 1 && day == 1 -> "春节"
        month == 1 && day == 15 -> "元宵节"
        month == 2 && day == 2 -> "龙抬头"
        month == 5 && day == 5 -> "端午节"
        month == 7 && day == 7 -> "七夕节"
        month == 7 && day == 15 -> "中元节"
        month == 8 && day == 15 -> "中秋节"
        month == 9 && day == 9 -> "重阳节"
        month == 12 && day == 8 -> "腊八节"
        month == 12 && day in 29..30 -> "除夕"
        else -> null
    }

    /** 同一信息准备几种自然表达，避免每次都像固定模板播报。 */
    private fun varied(options: List<String>): String = options[Random.nextInt(options.size)]

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
        val TRIGGERS = listOf("地点", "位置", "地址", "天气", "内存", "存储", "电量", "电池", "网络", "日期", "农历", "生肖", "节日", "星期", "星座", "时间")
    }
}
