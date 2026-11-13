package com.yahaeng.android.night

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yahaeng.android.MainActivity
import com.yahaeng.android.R

/**
 * 야행 화면이 보이지 않을 때 점멸 교차로에 다가가면 내비 위에 짧은 알림 팝업을 띄운다.
 * Pleos 창 정책에는 내비 위에 떠 있는 창이 없어서, 알림으로만 잠깐 띄우고 [TIMEOUT_MS] 뒤 닫는다.
 */
class FlashPopup(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, appContext.getString(R.string.popup_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    fun show(title: String, text: String) {
        val open = PendingIntent.getActivity(
            appContext, 1,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { manager.notify(ID, n) }.onFailure { Log.w("YahaengPopup", "notify failed", it) }
    }

    fun dismiss() {
        runCatching { manager.cancel(ID) }
    }

    companion object {
        private const val CHANNEL = "flash"
        private const val ID = 2
        const val TIMEOUT_MS = 6_000L
    }
}
