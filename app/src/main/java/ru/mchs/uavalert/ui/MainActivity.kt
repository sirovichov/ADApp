package ru.mchs.uavalert.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ru.mchs.uavalert.R
import ru.mchs.uavalert.data.AlertCache
import ru.mchs.uavalert.data.MchsRepository
import ru.mchs.uavalert.data.UavAlert
import ru.mchs.uavalert.databinding.ActivityMainBinding
import ru.mchs.uavalert.service.AlertWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран: список актуальных предупреждений МЧС об угрозе БПЛА.
 * Обновление — вручную (кнопка/свайп) и автоматически каждые 15 минут (WorkManager).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository = MchsRepository()
    private lateinit var cache: AlertCache
    private val adapter = AlertAdapter()
    private var allAlerts: List<UavAlert> = emptyList()
    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* результат не критичен */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = AlertCache(this)
        AlertWorker.createChannel(this)
        requestNotificationPermissionIfNeeded()

        binding.recyclerAlerts.layoutManager = LinearLayoutManager(this)
        binding.recyclerAlerts.adapter = adapter

        adapter.itemClickListener = { alert -> openInBrowser(alert.url) }

        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.fabRefresh.setOnClickListener { refresh() }

        // Запускаем фоновый опрос сайта МЧС
        AlertWorker.schedule(this)

        refresh()
    }

    private fun refresh() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                allAlerts = repository.fetchUavAlerts()
                cache.save(allAlerts)
                adapter.submitList(allAlerts)
                binding.textEmpty.visibility = if (allAlerts.isEmpty()) View.VISIBLE else View.GONE
                binding.textLastUpdate.text =
                    getString(R.string.last_update, timeFormat.format(Date()))
            } catch (e: Exception) {
                // сеть недоступна — показываем последний кэш
                val cached = cache.load()
                if (cached.isNotEmpty()) {
                    allAlerts = cached
                    adapter.submitList(cached)
                    binding.textEmpty.visibility = View.GONE
                }
                binding.textLastUpdate.text =
                    getString(R.string.error_load, e.message ?: "unknown")
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openInBrowser(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // нет браузера — игнорируем
        }
    }
}
