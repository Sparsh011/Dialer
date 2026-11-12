package com.sparsh.dialer.util

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.sparsh.dialer.data.Sims
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    private val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayThisYear = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private val dayOtherYear = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
    private val shortDay = SimpleDateFormat("d MMM", Locale.getDefault())
    private val country: String get() = Locale.getDefault().country.ifEmpty { "IN" }

    /** Matching key: last 10 digits, so "+91 98765 43210" == "09876543210" == "9876543210". */
    fun key(number: String?): String {
        if (number.isNullOrEmpty()) return ""
        val sb = StringBuilder(number.length)
        for (c in number) if (c in '0'..'9' || c == '*' || c == '#') sb.append(c)
        return if (sb.length > 10) sb.substring(sb.length - 10) else sb.toString()
    }

    fun number(number: String?): String {
        if (number.isNullOrBlank()) return "Private number"
        return runCatching { PhoneNumberUtils.formatNumber(number, country) }.getOrNull() ?: number
    }

    fun internationalNumber(context: Context, number: String, normalized: String? = null, accountId: String? = null): String? {
        if (number.trimStart().startsWith('+')) {
            PhoneNumberUtils.formatNumberToE164(number, "ZZ")?.let { return it }
        }
        normalized?.takeIf { it.startsWith('+') }?.let { return it }

        val telephony = context.getSystemService(TelephonyManager::class.java)
        val sim = Sims.sims.value.firstOrNull { it.id == accountId }
        val simRegion = sim?.let { runCatching { telephony.createForPhoneAccountHandle(it.handle)?.simCountryIso }.getOrNull() }
        val region = sequenceOf(
            simRegion,
            runCatching { telephony.networkCountryIso }.getOrNull(),
            runCatching { telephony.simCountryIso }.getOrNull(),
            country,
        ).filterNotNull().first { it.isNotBlank() }.uppercase(Locale.ROOT)
        return PhoneNumberUtils.formatNumberToE164(number, region)
    }

    @Synchronized fun time(ms: Long): String = time.format(Date(ms)).lowercase()

    fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Synchronized fun dayTitle(dayStart: Long, now: Long = System.currentTimeMillis()): String {
        val today = startOfDay(now)
        val diff = ((today - dayStart) / 86_400_000L).toInt()
        if (dayStart == today) return "Today"
        if (diff == 1 || (today - dayStart) in 1..86_400_000L) return "Yesterday"
        val a = Calendar.getInstance().apply { timeInMillis = dayStart }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        return if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) dayThisYear.format(Date(dayStart))
        else dayOtherYear.format(Date(dayStart))
    }

    /** "Today, 9:57 pm" / "Yesterday, 1:37 pm" / "20 Oct, 1:37 pm" */
    @Synchronized fun relative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val day = startOfDay(ms)
        val today = startOfDay(now)
        val prefix = when {
            day == today -> "Today"
            today - day <= 86_400_000L -> "Yesterday"
            else -> shortDay.format(Date(ms))
        }
        return "$prefix, ${time(ms)}"
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun clock(totalSeconds: Long): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun initials(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val parts = name.trim().split(' ').filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        if (parts.isEmpty()) return ""
        val a = parts.first().first().uppercaseChar()
        if (!a.isLetter()) return ""
        return if (parts.size > 1 && parts.last().first().isLetter()) "$a${parts.last().first().uppercaseChar()}" else "$a"
    }

    private const val T9 = "22233344455566677778889999"
    fun t9(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name.lowercase()) {
            when (ch) {
                in 'a'..'z' -> sb.append(T9[ch - 'a'])
                in '0'..'9' -> sb.append(ch)
                ' ' -> sb.append(' ')
            }
        }
        return sb.toString()
    }
}
