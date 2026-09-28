package com.junior.tradutortela

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.junior.tradutortela.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshState() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            binding.statusText.text = "Captura não autorizada"
            binding.statusDetail.text = "Autorize a captura para o app conseguir ler a tela."
            return@registerForActivityResult
        }

        val intent = Intent(this, ScreenTranslateService::class.java).apply {
            action = ScreenTranslateService.ACTION_START
            putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, result.data)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.statusText.text = "Tradução iniciada"
        binding.statusDetail.text = "Abra agora o jogo, site ou aplicativo em inglês."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.overlayPermissionButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                refreshState()
            } else {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        binding.startButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                binding.statusText.text = "Falta uma permissão"
                binding.statusDetail.text = "Primeiro autorize a sobreposição."
                return@setOnClickListener
            }
            requestNotificationPermission()
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.stopButton.setOnClickListener {
            startService(Intent(this, ScreenTranslateService::class.java).apply {
                action = ScreenTranslateService.ACTION_STOP
            })
            binding.statusText.text = "Tradução parada"
            binding.statusDetail.text = "Toque em Iniciar tradução para usar novamente."
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        if (Settings.canDrawOverlays(this)) {
            binding.overlayPermissionButton.text = "✓ Sobreposição autorizada"
            binding.statusText.text = "Pronto para traduzir"
            binding.statusDetail.text = "Toque em Iniciar tradução e autorize a captura da tela."
        } else {
            binding.overlayPermissionButton.text = "1. Autorizar sobreposição"
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
