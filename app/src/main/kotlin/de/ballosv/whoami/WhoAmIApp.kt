package de.ballosv.whoami

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import de.ballosv.whoami.content.FaceCropper

class WhoAmIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Modell der Gesichtserkennung schon beim Start über die Play-Dienste anfordern
        FaceCropper.prepare(this)
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
