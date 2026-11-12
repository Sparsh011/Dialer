package com.sparsh.dialer.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.sparsh.dialer.R
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.data.Prefs
import com.sparsh.dialer.data.Sims
import com.sparsh.dialer.data.granted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DialerInCallService : InCallService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var proximity: PowerManager.WakeLock? = null
    private var lastNotificationKey: String? = null
    private var foregroundType = 0

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = CallManager.changed(this@DialerInCallService, call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = CallManager.changed(this@DialerInCallService, call)
        override fun onParentChanged(call: Call, parent: Call?) = CallManager.changed(this@DialerInCallService, call)
        override fun onChildrenChanged(call: Call, children: MutableList<Call>?) = CallManager.changed(this@DialerInCallService, call)
        override fun onConferenceableCallsChanged(call: Call, calls: MutableList<Call>?) = CallManager.changed(this@DialerInCallService, call)
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        Sims.refresh(this)
        proximity = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "dialer:proximity")
                .apply { setReferenceCounted(false) }
        }.getOrNull()
        scope.launch {
            combine(CallManager.calls, CallManager.audio, ContactsRepo.state, Prefs.accent) { _, _, _, _ -> }.collect { refresh() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        CallManager.service = this
        return super.onBind(intent)
    }

    override fun onCallAdded(call: Call) {
        CallManager.add(call)
        call.registerCallback(callback)
        val state = call.details.state
        // Incoming calls are surfaced by the full-screen notification; everything else opens the UI directly.
        if (state != Call.STATE_RINGING && state != Call.STATE_SIMULATED_RINGING) InCallActivity.launch(this)
        refresh()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        CallManager.remove(call)
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        state ?: return
        val canName = granted(Manifest.permission.BLUETOOTH_CONNECT)
        @Suppress("DEPRECATION", "MissingPermission")
        val devices = runCatching {
            state.supportedBluetoothDevices.orEmpty().mapIndexed { i, d ->
                val name = if (canName) runCatching { d.alias ?: d.name }.getOrNull() else null
                BtDevice(name?.takeIf { it.isNotBlank() } ?: "Bluetooth device ${i + 1}", d.address.orEmpty(), d)
            }
        }.getOrDefault(emptyList())
        @Suppress("DEPRECATION")
        val active = runCatching { state.activeBluetoothDevice?.address }.getOrNull()
        CallManager.audio.value = AudioUi(
            muted = state.isMuted, route = state.route, supported = state.supportedRouteMask,
            bluetoothName = devices.firstOrNull { it.address == active }?.name ?: devices.firstOrNull()?.name,
            devices = devices, activeAddress = active,
        )
    }

    /** Re-evaluates the notification + proximity lock for the current call set. */
    fun refresh() {
        val calls = CallManager.calls.value
        val primary = CallManager.primary(calls)
        val audio = CallManager.audio.value

        val nearEar = primary != null && !primary.ringing && audio.route == CallAudioState.ROUTE_EARPIECE
        runCatching {
            val lock = proximity
            if (lock != null) {
                if (nearEar && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
                else if (!nearEar && lock.isHeld) lock.release()
            }
        }

        if (primary == null) {
            lastNotificationKey = null
            if (foregroundType != 0) { stopForeground(STOP_FOREGROUND_REMOVE); foregroundType = 0 }
            getSystemService(NotificationManager::class.java).cancel(CallNotifications.ID)
            return
        }
        updateForeground(primary)
    }

    private fun updateForeground(primary: CallUi) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val name = CallManager.nameFor(primary)
        val key = "${primary.id}|${primary.state}|${primary.connectTime}|$name|${Prefs.accent.value}"
        if (type == foregroundType && key == lastNotificationKey) return
        val notification = CallNotifications.build(this, primary, name)
        val shown = runCatching {
            if (type != foregroundType) {
                startForeground(CallNotifications.ID, notification, type)
                foregroundType = type
            } else {
                getSystemService(NotificationManager::class.java).notify(CallNotifications.ID, notification)
            }
            lastNotificationKey = key
        }.onFailure { Log.w("DialerInCallService", "Couldn't update call foreground service type $type", it) }.isSuccess
        if (!shown) runCatching {
            getSystemService(NotificationManager::class.java).notify(CallNotifications.ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (proximity?.isHeld == true) proximity?.release() }
        if (CallManager.service === this) CallManager.service = null
        getSystemService(NotificationManager::class.java).cancel(CallNotifications.ID)
        super.onDestroy()
    }
}

object CallNotifications {
    const val ID = 4201
    private const val CH_INCOMING = "incoming_calls"
    private const val CH_ONGOING = "ongoing_calls"
    const val ACTION_DECLINE = "com.sparsh.dialer.DECLINE"
    const val ACTION_HANGUP = "com.sparsh.dialer.HANGUP"
    const val ACTION_ANSWER = "com.sparsh.dialer.ANSWER"
    const val EXTRA_ID = "call_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen alert when someone calls"
                setSound(null, null) // the system plays the ringtone
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the call in progress"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun broadcast(context: Context, action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id * 10 + action.hashCode() % 7,
        Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun build(context: Context, call: CallUi, name: String): Notification {
        val open = PendingIntent.getActivity(
            context, 1, InCallActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val person = Person.Builder().setName(name).setImportant(true).build()
        val slot = Sims.slotFor(call.accountId)
        val sim = if (slot > 0) " · SIM $slot" else ""
        return if (call.ringing) {
            val answer = PendingIntent.getActivity(
                context, 2, InCallActivity.intent(context).setAction(ACTION_ANSWER).putExtra(EXTRA_ID, call.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            Notification.Builder(context, CH_INCOMING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(name)
                .setContentText("Incoming call$sim")
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Prefs.accent.value)
                .setStyle(Notification.CallStyle.forIncomingCall(person, broadcast(context, ACTION_DECLINE, call.id), answer))
                .build()
        } else {
            val text = when {
                call.held -> "On hold"
                call.active -> "Ongoing call$sim"
                else -> "Calling…"
            }
            Notification.Builder(context, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(name)
                .setContentText(text)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(Prefs.accent.value)
                .setUsesChronometer(call.connectTime > 0)
                .setWhen(if (call.connectTime > 0) call.connectTime else System.currentTimeMillis())
                .setShowWhen(call.connectTime > 0)
                .setStyle(Notification.CallStyle.forOngoingCall(person, broadcast(context, ACTION_HANGUP, call.id)))
                .build()
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(CallNotifications.EXTRA_ID, -1)
        when (intent.action) {
            CallNotifications.ACTION_DECLINE, CallNotifications.ACTION_HANGUP -> CallManager.hangup(id)
        }
    }
}
