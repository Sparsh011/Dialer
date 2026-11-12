package com.sparsh.dialer.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.sparsh.dialer.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Mirrors the system call log into Room. Room is the source of truth for the UI, and — unlike
 * the system log, which the OS truncates — it keeps history forever.
 */
object CallLogRepo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var observing = false

    private val PROJECTION = arrayOf(
        CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
        CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.FEATURES,
    )

    fun start(context: Context) {
        val app = context.applicationContext
        if (!app.granted(Manifest.permission.READ_CALL_LOG)) return
        if (!observing) {
            observing = true
            app.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = requestSync(app, 250)
                },
            )
        }
        requestSync(app, 0)
    }

    fun requestSync(context: Context, debounceMs: Long = 0) {
        job?.cancel()
        job = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            runCatching { sync(context.applicationContext) }
        }
    }

    private suspend fun sync(context: Context) = mutex.withLock {
        if (!context.granted(Manifest.permission.READ_CALL_LOG)) return@withLock
        val dao = AppDb.get(context).calls()
        val known = dao.idsSince(0).toHashSet()
        val recentCutoff = System.currentTimeMillis() - 86_400_000L
        val systemIds = HashSet<Long>()
        val changed = ArrayList<CallEntity>()
        var oldest = Long.MAX_VALUE
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, PROJECTION, null, null, CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val date = c.getLong(4)
                systemIds.add(id)
                if (date < oldest) oldest = date
                // Old rows never change; only (re)write new ones and anything from the last day.
                if (id in known && date < recentCutoff) continue
                val number = c.getString(1).orEmpty()
                changed.add(
                    CallEntity(
                        id = id, number = number, numKey = Fmt.key(number),
                        cachedName = c.getString(2)?.takeIf { it.isNotBlank() },
                        type = c.getInt(3), date = date, duration = c.getLong(5),
                        accountId = c.getString(6), features = c.getInt(7),
                    ),
                )
            }
        } ?: return@withLock
        changed.chunked(400).forEach { dao.upsert(it) }
        if (oldest != Long.MAX_VALUE) {
            // Rows inside the system log's time range that vanished were deleted elsewhere.
            val gone = dao.idsSince(oldest).filter { it !in systemIds }
            gone.chunked(400).forEach { dao.delete(it) }
        }
    }

    suspend fun delete(context: Context, ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val dao = AppDb.get(context).calls()
        ids.chunked(400).forEach { chunk ->
            dao.delete(chunk)
            runCatching {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${chunk.joinToString(",")})", null,
                )
            }
        }
    }

    /** Clears the "new missed call" flags + the system missed-call notification. */
    fun markMissedSeen(context: Context) {
        scope.launch {
            runCatching {
                val v = ContentValues().apply { put(CallLog.Calls.NEW, 0); put(CallLog.Calls.IS_READ, 1) }
                context.contentResolver.update(
                    CallLog.Calls.CONTENT_URI, v,
                    "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}", null,
                )
                context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
            }
        }
    }
}

object ContactsRepo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ContactsState())
    val state: StateFlow<ContactsState> = _state
    private var job: Job? = null
    private var observing = false

    private val PROJECTION = arrayOf(
        Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER,
        Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED, Phone.NORMALIZED_NUMBER,
    )

    fun start(context: Context) {
        val app = context.applicationContext
        if (!app.granted(Manifest.permission.READ_CONTACTS)) return
        if (!observing) {
            observing = true
            app.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = reload(app, 400)
                },
            )
        }
        reload(app, 0)
    }

    fun reload(context: Context, debounceMs: Long) {
        job?.cancel()
        job = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            runCatching { load(context) }
        }
    }

    private class Building(
        val id: Long, val lookup: String, val name: String, val photo: String?, val starred: Boolean,
    ) {
        val numbers = ArrayList<PhoneEntry>(2)
    }

    private fun load(context: Context) {
        val map = LinkedHashMap<Long, Building>()
        context.contentResolver.query(
            Phone.CONTENT_URI, PROJECTION, null, null,
            Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            val res = context.resources
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(3) ?: continue
                val key = Fmt.key(number)
                if (key.isEmpty()) continue
                val b = map.getOrPut(id) {
                    Building(id, c.getString(1).orEmpty(), c.getString(2) ?: number, c.getString(6), c.getInt(7) == 1)
                }
                if (b.numbers.none { it.key == key }) {
                    val label = Phone.getTypeLabel(res, c.getInt(4), c.getString(5)).toString()
                    b.numbers.add(PhoneEntry(number, key, label, c.getString(8)))
                }
            }
        } ?: return
        val list = ArrayList<Contact>(map.size)
        val byKey = HashMap<String, Contact>(map.size * 2)
        for (b in map.values) {
            val first = b.name.trim().firstOrNull()?.uppercaseChar()
            val contact = Contact(
                id = b.id, lookupKey = b.lookup, name = b.name, photoUri = b.photo, starred = b.starred,
                numbers = b.numbers, t9 = Fmt.t9(b.name),
                letter = if (first != null && first.isLetter()) first.toString() else "#",
            )
            list.add(contact)
            for (n in b.numbers) byKey.putIfAbsent(n.key, contact)
        }
        _state.value = ContactsState(list, byKey, true)
    }

    fun setStarred(context: Context, contactId: Long, starred: Boolean) {
        scope.launch {
            runCatching {
                val v = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0) }
                context.contentResolver.update(
                    ContactsContract.Contacts.CONTENT_URI, v, "${ContactsContract.Contacts._ID} = ?",
                    arrayOf(contactId.toString()),
                )
            }
            reload(context.applicationContext, 0)
        }
    }
}

object Sims {
    private val _sims = MutableStateFlow<List<SimInfo>>(emptyList())
    val sims: StateFlow<List<SimInfo>> = _sims

    fun refresh(context: Context) {
        if (!context.granted(Manifest.permission.READ_PHONE_STATE)) return
        runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val subs = context.getSystemService(SubscriptionManager::class.java)
            val infos = runCatching { subs.activeSubscriptionInfoList }.getOrNull().orEmpty()
            val result = telecom.callCapablePhoneAccounts.mapIndexed { index, handle ->
                val account = telecom.getPhoneAccount(handle)
                val subId = runCatching { telephony.getSubscriptionId(handle) }.getOrDefault(-1)
                val info = infos.firstOrNull { it.subscriptionId == subId }
                val number = runCatching { subs.getPhoneNumber(subId) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: account?.address?.schemeSpecificPart?.takeIf { it.isNotBlank() }
                SimInfo(
                    handle = handle,
                    id = handle.id,
                    slot = info?.simSlotIndex?.plus(1) ?: (index + 1),
                    label = info?.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: account?.label?.toString() ?: "SIM ${index + 1}",
                    number = number,
                    color = info?.iconTint ?: account?.highlightColor ?: 0,
                )
            }.sortedBy { it.slot }
            _sims.value = result
        }
    }

    fun slotFor(accountId: String?): Int {
        if (accountId == null) return 0
        val list = _sims.value
        if (list.size < 2) return 0
        return list.firstOrNull { it.id == accountId }?.slot ?: 0
    }
}

object Blocking {
    fun isBlocked(context: Context, number: String): Boolean = runCatching {
        BlockedNumberContract.canCurrentUserBlockNumbers(context) && BlockedNumberContract.isBlocked(context, number)
    }.getOrDefault(false)

    fun setBlocked(context: Context, number: String, blocked: Boolean): Boolean = runCatching {
        if (blocked) {
            val v = ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
            context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, v)
        } else {
            BlockedNumberContract.unblock(context, number)
        }
        true
    }.getOrDefault(false)
}

/** The only place calls are placed from. */
object Dial {
    fun needsSimChoice(context: Context): Boolean {
        if (Sims.sims.value.size < 2) return false
        return runCatching {
            context.getSystemService(TelecomManager::class.java)
                .getDefaultOutgoingPhoneAccount("tel") == null
        }.getOrDefault(false)
    }

    fun call(context: Context, number: String, sim: SimInfo? = null): Boolean {
        if (number.isBlank() || !context.granted(Manifest.permission.CALL_PHONE)) return false
        return runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val extras = android.os.Bundle()
            if (sim != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
            telecom.placeCall(Uri.fromParts("tel", number.trim(), null), extras)
            true
        }.getOrDefault(false)
    }

    fun voicemail(context: Context): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts("voicemail", "", null), android.os.Bundle())
        true
    }.getOrDefault(false)
}
