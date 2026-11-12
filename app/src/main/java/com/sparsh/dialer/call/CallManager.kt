package com.sparsh.dialer.call

import android.content.Context
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.compose.runtime.Immutable
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

@Immutable
data class CallUi(
    val id: Int,
    val state: Int,
    val number: String,
    val incoming: Boolean,
    /** Wall-clock millis when the call connected, 0 until then. */
    val connectTime: Long,
    val accountId: String?,
    val canHold: Boolean,
    val conference: Boolean,
    val canMerge: Boolean,
    val accounts: List<PhoneAccountHandle> = emptyList(),
    val wifi: Boolean = false,
    val hd: Boolean = false,
    val callerDisplayName: String? = null,
    val callerVerification: Int = Connection.VERIFICATION_STATUS_NOT_VERIFIED,
    val canCallBack: Boolean = false,
    val endedAt: Long = 0,
    /** We asked Telecom to answer; the UI switches right away instead of waiting for ACTIVE. */
    val answering: Boolean = false,
    /** We asked Telecom to hang up / decline; the UI shows the ended look right away. */
    val ending: Boolean = false,
) {
    val key: String get() = Fmt.key(number)
    val ringing get() = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING
    val dialing get() = state == Call.STATE_DIALING || state == Call.STATE_CONNECTING ||
        state == Call.STATE_NEW || state == Call.STATE_PULLING_CALL
    val active get() = state == Call.STATE_ACTIVE
    val held get() = state == Call.STATE_HOLDING
    val ended get() = state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING
    val selectingAccount get() = state == Call.STATE_SELECT_PHONE_ACCOUNT
}

@Immutable
class BtDevice(val name: String, val address: String, val device: android.bluetooth.BluetoothDevice) {
    override fun equals(other: Any?) = other is BtDevice && other.address == address && other.name == name
    override fun hashCode() = address.hashCode()
}

@Immutable
data class AudioUi(
    val muted: Boolean = false,
    val route: Int = CallAudioState.ROUTE_EARPIECE,
    val supported: Int = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER,
    val bluetoothName: String? = null,
    val devices: List<BtDevice> = emptyList(),
    val activeAddress: String? = null,
) {
    val wired get() = supported and CallAudioState.ROUTE_WIRED_HEADSET != 0
    /** Where audio goes when neither speaker nor Bluetooth is selected. */
    val phoneRoute get() = if (wired) CallAudioState.ROUTE_WIRED_HEADSET else CallAudioState.ROUTE_EARPIECE
    val speaker get() = route == CallAudioState.ROUTE_SPEAKER
    val bluetooth get() = route == CallAudioState.ROUTE_BLUETOOTH
    val hasBluetooth get() = supported and CallAudioState.ROUTE_BLUETOOTH != 0
}

/** Process-wide bridge between the Telecom [Call] objects and the Compose UI. */
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = IdentityHashMap<Call, Int>()
    private val lastState = HashMap<Int, Int>()
    private val answering = HashSet<Int>()
    private val ending = HashSet<Int>()
    private var nextId = 1

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls: StateFlow<List<CallUi>> = _calls

    /** Retained for the end screen until it is dismissed or another call arrives. */
    private val _ended = MutableStateFlow<CallUi?>(null)
    val ended: StateFlow<CallUi?> = _ended

    val audio = MutableStateFlow(AudioUi())

    @Volatile var service: DialerInCallService? = null

    fun nameFor(call: CallUi): String =
        ContactsRepo.state.value.byKey[call.key]?.name ?: call.callerDisplayName ?: Fmt.number(call.number)

    fun dismissEnded() { _ended.value = null }

    /** Ringing first, then the live call, then anything else. */
    fun primary(list: List<CallUi>): CallUi? =
        list.firstOrNull { it.ringing } ?: list.firstOrNull { it.active } ?: list.firstOrNull { it.dialing }
            ?: list.firstOrNull { it.selectingAccount } ?: list.firstOrNull { !it.ended } ?: list.firstOrNull()

    private fun find(id: Int): Call? = ids.entries.firstOrNull { it.value == id }?.key

    private fun snapshot(call: Call, id: Int): CallUi {
        val d = call.details
        val incoming = d.callDirection == Call.Details.DIRECTION_INCOMING
        val number = d.handle?.schemeSpecificPart.orEmpty()
            .takeIf { d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED }.orEmpty()
        @Suppress("DEPRECATION")
        val accounts: List<PhoneAccountHandle> = if (d.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
            d.intentExtras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS).orEmpty()
        } else emptyList()
        return CallUi(
            id = id,
            state = d.state,
            number = number,
            incoming = incoming,
            connectTime = d.connectTimeMillis,
            accountId = d.accountHandle?.id,
            canHold = d.can(Call.Details.CAPABILITY_HOLD),
            conference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            canMerge = d.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || call.conferenceableCalls.isNotEmpty(),
            accounts = accounts,
            wifi = d.hasProperty(Call.Details.PROPERTY_WIFI),
            hd = d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO),
            callerDisplayName = d.callerDisplayName?.trim()?.takeIf {
                incoming && d.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED && it.isNotBlank()
            },
            callerVerification = if (incoming) d.callerNumberVerificationStatus else Connection.VERIFICATION_STATUS_NOT_VERIFIED,
            canCallBack = d.handle?.scheme == "tel" && number.any { it.isDigit() } &&
                !d.hasProperty(Call.Details.PROPERTY_CONFERENCE) && !d.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE),
            answering = id in answering,
            ending = id in ending,
        )
    }

    private fun publish() {
        _calls.value = ids.entries.filter { it.key.parent == null }.sortedBy { it.value }.map { snapshot(it.key, it.value) }
    }

    fun add(call: Call) {
        val id = nextId++
        ids[call] = id
        lastState[id] = call.details.state
        _ended.value = null
        publish()
    }

    fun changed(context: Context, call: Call) {
        val id = ids[call] ?: return
        val prev = lastState[id]
        val now = call.details.state
        lastState[id] = now
        if (now != Call.STATE_RINGING && now != Call.STATE_SIMULATED_RINGING) answering.remove(id)
        publish()
        if (prev == now) return
        if (now == Call.STATE_ACTIVE && prev != Call.STATE_HOLDING) {
            // The other side picked up (or we answered): tactile confirmation.
            Haptics.callAnswered(context)
        }
        if (now == Call.STATE_DISCONNECTED) {
            Haptics.callEnded(context)
        }
    }

    fun remove(call: Call) {
        val id = ids.remove(call) ?: return
        val ui = snapshot(call, id).copy(state = Call.STATE_DISCONNECTED, endedAt = System.currentTimeMillis())
        lastState.remove(id)
        answering.remove(id)
        ending.remove(id)
        if (call.parent == null && ids.keys.none { it.parent == null }) _ended.value = ui
        publish()
    }

    fun answer(id: Int) {
        val call = find(id) ?: return
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        answering.add(id)
        publish()
    }
    fun reject(id: Int, message: String? = null) {
        val call = find(id) ?: return
        if (message != null) call.reject(true, message) else call.reject(Call.REJECT_REASON_DECLINED)
        ending.add(id)
        publish()
    }
    fun hangup(id: Int) {
        val call = find(id) ?: return
        ending.add(id)
        publish()
        val s = call.details.state
        if (s == Call.STATE_RINGING || s == Call.STATE_SIMULATED_RINGING) call.reject(Call.REJECT_REASON_DECLINED) else call.disconnect()
    }
    fun hold(id: Int, on: Boolean) { find(id)?.let { if (on) it.hold() else it.unhold() } }
    fun dtmf(id: Int, c: Char) {
        val call = find(id) ?: return
        call.playDtmfTone(c)
        scope.launch { delay(120); call.stopDtmfTone() }
    }
    fun selectAccount(id: Int, handle: PhoneAccountHandle) { find(id)?.phoneAccountSelected(handle, false) }
    fun merge(id: Int) {
        val call = find(id) ?: return
        val other = call.conferenceableCalls.firstOrNull()
        if (other != null) call.conference(other)
        else if (call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) call.mergeConference()
    }

    @Suppress("DEPRECATION")
    fun setMuted(muted: Boolean) { service?.setMuted(muted) }
    @Suppress("DEPRECATION")
    fun setRoute(route: Int) { service?.setAudioRoute(route) }
    fun setBluetooth(device: BtDevice) { runCatching { service?.requestBluetoothAudio(device.device) } }
}
