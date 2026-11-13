package com.yahaeng.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 화면 없이 밤 안내를 돌리는 포그라운드 서비스. 판단 로직은 없고, 안내 세션([NightController])을 시작해 두고
 * 프로세스를 붙잡는 일만 한다. Pleos Connect는 화면에서 사라진 앱을 얼릴 수 있어서 이 서비스가 필요하다.
 *
 * 시작하는 곳은 두 군데다. 차량 화면 시스템이 켜질 때([BootReceiver])와, 야행 화면이 보일 때(MainActivity).
 */
class NightService : Service() {

    override fun onCreate() {
        super.onCreate()
        (application as YahaengApplication).controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType()) }
        if (started.isFailure) {
            Log.w(TAG, "startForeground failed", started.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "night service started (${intent?.getStringExtra(EXTRA_REASON) ?: "unknown"})")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            // NAVIGATION으로 두면 Pleos가 닫히지 않는 팝업으로 띄운다(로드메이트에서 확인). 서비스 알림은 SERVICE
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "YahaengService"
        private const val CHANNEL_ID = "night"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_REASON = "reason"

        fun start(context: Context, reason: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, NightService::class.java).putExtra(EXTRA_REASON, reason))
            }.onFailure { Log.w(TAG, "service start failed ($reason)", it) }
        }
    }
}

/**
 * 차량 화면 시스템이 켜지면(부팅 완료) 안내 서비스를 시작한다. 사용자가 처음 설정을 마쳤고
 * "차가 켜지면 저절로 시작"을 켜 둔 경우에만. 앱을 새로 설치하거나 업데이트한 뒤에도 같은 조건으로 시작한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as YahaengApplication
        val s = app.settings.state.value
        Log.i("YahaengBoot", "$action onboarded=${s.onboarded} autoStart=${s.autoStart}")
        if (s.onboarded && s.autoStart) NightService.start(context, if (action == Intent.ACTION_BOOT_COMPLETED) "boot" else "update")
    }
}
