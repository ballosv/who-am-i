package de.ballosv.whoami.invite

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import de.ballosv.whoami.MainActivity
import de.ballosv.whoami.R
import de.ballosv.whoami.WhoAmIApp
import de.ballosv.whoami.net.NearbyConfig

/**
 * Sucht im Hintergrund nach Spielen in der Nähe, deren Host gerade einlädt, und zeigt dafür
 * eine Benachrichtigung. Tippt man sie an, startet die App direkt im Beitreten-Modus.
 */
class InviteListenerService : Service() {

    private val notified = HashSet<String>()

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val ad = NearbyConfig.decodeAd(info.endpointName) ?: return
            if (ad.inviting && notified.add(ad.code)) showInvite(ad)
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NearbyConfig.hasPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, ongoingNotification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "Vordergrunddienst nicht erlaubt", e)
            stopSelf()
            return START_NOT_STICKY
        }
        NearbyConfig.client(this).startDiscovery(
            NearbyConfig.SERVICE_ID,
            discovery,
            DiscoveryOptions.Builder().setStrategy(NearbyConfig.STRATEGY).build(),
        ).addOnFailureListener { Log.w(TAG, "Suche fehlgeschlagen", it) }
        return START_STICKY
    }

    override fun onDestroy() {
        NearbyConfig.client(this).stopDiscovery()
        super.onDestroy()
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WhoAmIApp.CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Bereit für Einladungen")
            .setContentText("Who Am I? sucht nach Spielen in der Nähe")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun showInvite(ad: NearbyConfig.GameAd) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(Intent.ACTION_VIEW, joinUri(ad.code), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, ad.code.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, WhoAmIApp.CHANNEL_INVITES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Einladung von ${ad.hostName}")
            .setContentText("${ad.hostName} lädt dich zu „Who Am I?“ ein. Tippen zum Beitreten.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(this).notify(ad.code.hashCode(), notification)
    }

    companion object {
        private const val TAG = "WhoAmI.Invites"
        private const val ONGOING_ID = 1

        fun joinUri(code: String): Uri = Uri.parse("whoami://join?code=$code")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, InviteListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InviteListenerService::class.java))
        }
    }
}
