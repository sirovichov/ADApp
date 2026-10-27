package ru.mchs.uavalert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.mchs.uavalert.R
import ru.mchs.uavalert.data.MchsRepository
import ru.mchs.uavalert.data.UavAlert
import ru.mchs.uavalert.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Фоновая задача (WorkManager), которая периодически опрашивает сайт МЧС,
 * находит новые предупреждения об угрозе БПЛА и показывает срочное уведомление.
 */
class AlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "mchs_uav_polling"
        private const val CHANNEL_ID = "uav_alerts"

        /** Запускает периодический опрос сайта МЧС каждые 15 минут. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH // звук + heads-up уведомление
                ).apply {
                    description = context.getString(R.string.channel_alerts_desc)
                    enableVibration(true)
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
        }
    }

    private val prefs = applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        return try {
            val alerts = MchsRepository().fetchUavAlerts()
            val seen = prefs.getStringSet("seen_ids", emptySet()) ?: emptySet()

            val fresh = alerts.filter { it.id !in seen }
            fresh.forEach { showNotification(it) }

            // запоминаем показанные id (с ограничением размера множества)
            val updated = (seen + fresh.map { it.id }).takeLast(300).toSet()
            prefs.edit().putStringSet("seen_ids", updated).apply()
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()

            Result.success()
        } catch (e: Exception) {
            // Сеть недоступна или сайт изменился — WorkManager повторит попытку сам
            Result.retry()
        }
    }

    /** Показывает срочное уведомление об угрозе БПЛА. */
    private fun showNotification(alert: UavAlert) {
        createChannel(applicationContext)

        val detailsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(alert.url))
        val contentPending = PendingIntent.getActivity(
            applicationContext, alert.id.hashCode(), detailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openApp = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = applicationContext.getString(
            R.string.notification_title_template,
            alert.region.ifBlank { "РФ" }
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentPending)
            .addAction(R.drawable.ic_launcher_foreground, "Открыть сайт МЧС", openApp)
            .build()

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(alert.id.hashCode(), notification)
    }
}
