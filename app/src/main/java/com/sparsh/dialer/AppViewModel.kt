package com.sparsh.dialer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sparsh.dialer.data.AppDb
import com.sparsh.dialer.data.CallEntity
import com.sparsh.dialer.data.CallGroup
import com.sparsh.dialer.data.CallLogRepo
import com.sparsh.dialer.data.CallType
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.data.ContactsState
import com.sparsh.dialer.data.RecentItem
import com.sparsh.dialer.data.RecordingEntity
import com.sparsh.dialer.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).calls()

    val contacts: StateFlow<ContactsState> = ContactsRepo.state
    val missedOnly = MutableStateFlow(false)
    val recentsQuery = MutableStateFlow("")

    /** null while the first load is in flight. */
    val recents: StateFlow<List<RecentItem>?> =
        combine(dao.observeRecent(3000), contacts, missedOnly, recentsQuery) { calls, c, missed, q -> build(calls, c, missed, q.trim()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recordings: StateFlow<List<RecordingEntity>> =
        dao.observeRecordings().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var dialInput by mutableStateOf("")

    fun history(keys: List<String>): Flow<List<CallEntity>> = dao.observeFor(keys)

    suspend fun lastDialed(): String? = dao.lastOutgoing()?.number

    suspend fun lastAccountFor(key: String): String? = dao.lastAccountFor(key)

    fun delete(ids: List<Long>) {
        viewModelScope.launch { CallLogRepo.delete(getApplication(), ids) }
    }

    fun deleteRecording(r: RecordingEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(r.path).delete() }
            dao.deleteRecording(r.id)
        }
    }

    private fun build(calls: List<CallEntity>, contacts: ContactsState, missedOnly: Boolean, query: String): List<RecentItem> {
        val out = ArrayList<RecentItem>(calls.size + 32)
        val now = System.currentTimeMillis()
        var day = Long.MAX_VALUE
        var i = 0
        var firstOfDay = true
        val digits = query.filter { it.isDigit() }
        val internationalKey = digits.takeIf { it.length > 10 }?.let(Fmt::key)

        fun matches(call: CallEntity): Boolean {
            if (missedOnly && call.type != CallType.MISSED && call.type != CallType.REJECTED) return false
            if (query.isEmpty()) return true
            val name = contacts.byKey[call.numKey]?.name ?: call.cachedName
            return name?.contains(query, ignoreCase = true) == true || call.numKey == internationalKey ||
                (digits.length >= 2 && (call.numKey.contains(digits) || call.number.filter { it.isDigit() }.contains(digits)))
        }

        while (i < calls.size) {
            val first = calls[i]
            val contact = contacts.byKey[first.numKey]
            val runDay = if (first.date >= day) day else Fmt.startOfDay(first.date)
            var end = i + 1
            while (end < calls.size && calls[end].date >= runDay && first.numKey.isNotEmpty()) {
                val next = calls[end]
                val samePerson = if (contact == null) next.numKey == first.numKey
                    else contacts.byKey[next.numKey]?.id == contact.id
                if (!samePerson) break
                end++
            }

            // Filter inside each run so hidden callers can't join two separate groups.
            var head: CallEntity? = null
            val ids = ArrayList<Long>(end - i)
            for (index in i until end) {
                val call = calls[index]
                if (matches(call)) {
                    if (head == null) head = call
                    ids.add(call.id)
                }
            }
            i = end
            if (head == null) continue
            if (head.date < day) {
                day = runDay
                markLast(out)
                out.add(RecentItem.Header(Fmt.dayTitle(day, now), day))
                firstOfDay = true
            }
            out.add(
                RecentItem.Row(
                    CallGroup(
                        id = head.id, key = head.numKey, number = head.number,
                        name = contact?.name ?: head.cachedName,
                        photoUri = contact?.photoUri,
                        numberLabel = contact?.numbers?.firstOrNull { it.key == head.numKey }?.label,
                        type = head.type, date = head.date, duration = head.duration,
                        accountId = head.accountId, features = head.features, ids = ids,
                    ),
                    first = firstOfDay, last = false,
                ),
            )
            firstOfDay = false
        }
        markLast(out)
        return out
    }

    private fun markLast(out: ArrayList<RecentItem>) {
        val last = out.lastOrNull()
        if (last is RecentItem.Row) out[out.size - 1] = last.copy(last = true)
    }
}
