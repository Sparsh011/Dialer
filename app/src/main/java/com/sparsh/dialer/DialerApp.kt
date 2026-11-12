package com.sparsh.dialer

import android.app.Application
import com.sparsh.dialer.call.CallNotifications
import com.sparsh.dialer.data.CallLogRepo
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.data.Prefs
import com.sparsh.dialer.data.Sims

class DialerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        CallNotifications.createChannels(this)
        startData()
    }

    /** Safe to call repeatedly; each piece no-ops until its permission is granted. */
    fun startData() {
        CallLogRepo.start(this)
        ContactsRepo.start(this)
        Sims.refresh(this)
    }
}
