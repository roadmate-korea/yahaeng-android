package com.yahaeng.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yahaeng.android.night.FlashPopup
import com.yahaeng.android.ui.DrivePanel
import com.yahaeng.android.ui.NightTheme
import com.yahaeng.android.ui.OnboardingScreen
import com.yahaeng.android.ui.ParkedScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as YahaengApplication
    private val popup by lazy { FlashPopup(this) }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[YahaengApplication.CAR_SPEED] == true) app.speedPermissionGranted.value = true
        if (result[YahaengApplication.FUSED_LOCATION] == true) app.locationPermissionGranted.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = app.controller
        setContent {
            NightTheme {
                val ui by controller.ui.collectAsStateWithLifecycle()
                val caption by controller.caption.collectAsStateWithLifecycle()
                val settings by app.settings.state.collectAsStateWithLifecycle()
                when {
                    !settings.onboarded -> OnboardingScreen(
                        parked = !ui.vehicle.locked,
                        dataRegions = ui.dataRegions,
                        dataSize = ui.dataSize,
                        onStart = {
                            app.settings.update { it.copy(onboarded = true) }
                            askPermissions()
                        },
                    )
                    ui.vehicle.locked -> DrivePanel(ui, caption)
                    else -> ParkedScreen(ui, caption, settings, app.settings::update)
                }
            }
        }
        askMissingPermissionsWhenParked()
    }

    override fun onStart() {
        super.onStart()
        app.screenVisible = true
        popup.dismiss()
        // 화면이 보이면 안내 서비스를 켜 둔다. 내비로 넘어가도 음성 안내가 계속된다
        NightService.start(this, "screen")
    }

    override fun onStop() {
        app.screenVisible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (app.hasPermission(YahaengApplication.CAR_SPEED)) app.speedPermissionGranted.value = true
        if (app.hasPermission(YahaengApplication.FUSED_LOCATION)) app.locationPermissionGranted.value = true
    }

    private fun missingPermissions(): List<String> = buildList {
        if (!app.hasPermission(YahaengApplication.FUSED_LOCATION)) add(YahaengApplication.FUSED_LOCATION)
        if (!app.hasPermission(YahaengApplication.CAR_SPEED)) add(YahaengApplication.CAR_SPEED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun askPermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty() || app.permissionsAsked) return
        app.permissionsAsked = true
        requestPermissions.launch(missing.toTypedArray())
    }

    /** 처음 설정을 마친 뒤 권한이 빠져 있으면, 정차(P)가 확인되고 화면이 앞에 있을 때 한 번만 다시 묻는다. */
    private fun askMissingPermissionsWhenParked() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!app.settings.state.value.onboarded || missingPermissions().isEmpty() || app.permissionsAsked) return@repeatOnLifecycle
                app.signalHub.vehicle.first { !it.locked }
                askPermissions()
            }
        }
    }
}
