package com.sparsh.dialer.call

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sparsh.dialer.ui.theme.DialerTheme

/** Full-screen call UI. Shows over the lock screen and wakes the display for incoming calls. */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        handle(intent)
        setContent {
            DialerTheme(forceDark = true) { InCallScreen(onFinish = { finishAndRemoveTask() }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        if (isFinishing) CallManager.dismissEnded()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == CallNotifications.ACTION_ANSWER) {
            CallManager.answer(intent.getIntExtra(CallNotifications.EXTRA_ID, -1))
            intent.action = null
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        fun launch(context: Context) {
            runCatching { context.startActivity(intent(context)) }
        }
    }
}
