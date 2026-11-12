package com.sparsh.dialer.data

import android.telecom.PhoneAccountHandle
import androidx.compose.runtime.Immutable

@Immutable
data class PhoneEntry(val number: String, val key: String, val label: String, val normalizedNumber: String? = null)

@Immutable
data class Contact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val photoUri: String?,
    val starred: Boolean,
    val numbers: List<PhoneEntry>,
    /** T9 digit string for every word of the name, space separated — used by keypad search. */
    val t9: String,
    val letter: String,
)

@Immutable
data class ContactsState(
    val list: List<Contact> = emptyList(),
    val byKey: Map<String, Contact> = emptyMap(),
    val loaded: Boolean = false,
)

@Immutable
data class SimInfo(
    val handle: PhoneAccountHandle,
    val id: String,
    /** 1-based SIM slot, 0 when unknown. */
    val slot: Int,
    val label: String,
    val number: String?,
    val color: Int,
)

/** A run of consecutive calls with the same number, like "Sparsh Voda (8)". */
@Immutable
data class CallGroup(
    val id: Long,
    val key: String,
    val number: String,
    val name: String?,
    val photoUri: String?,
    val numberLabel: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val accountId: String?,
    val features: Int,
    val ids: List<Long>,
) {
    val count get() = ids.size
}

@Immutable
sealed interface RecentItem {
    val stableKey: Any

    @Immutable
    data class Header(val title: String, val day: Long) : RecentItem {
        override val stableKey: Any get() = "h$day"
    }

    @Immutable
    data class Row(val group: CallGroup, val first: Boolean, val last: Boolean) : RecentItem {
        override val stableKey: Any get() = group.id
    }
}

object CallType {
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val VOICEMAIL = 4
    const val REJECTED = 5
    const val BLOCKED = 6

    fun label(type: Int): String = when (type) {
        INCOMING -> "Incoming call"
        OUTGOING -> "Outgoing call"
        MISSED -> "Missed call"
        VOICEMAIL -> "Voicemail"
        REJECTED -> "Declined call"
        BLOCKED -> "Blocked call"
        else -> "Call"
    }
}
