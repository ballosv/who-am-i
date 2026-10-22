package de.ballosv.whoami

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import de.ballosv.whoami.platform.AndroidPlatform

class WhoAmIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Plattformzugriffe für den gemeinsamen Code; fordert auch das Modell der Gesichtserkennung an
        AndroidPlatform.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INVITES, "Spiel-Einladungen", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Benachrichtigt, wenn dich jemand in der Nähe zu einem Spiel einlädt"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENER, "Einladungsempfang", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Hinweis, solange die App nach Einladungen sucht"
            },
        )
    }

    companion object {
        const val CHANNEL_INVITES = "invites"
        const val CHANNEL_LISTENER = "listener"
    }
}
