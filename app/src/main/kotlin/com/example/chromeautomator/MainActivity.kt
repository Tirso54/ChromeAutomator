package com.example.chromeautomator

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity
 *
 * UI mínima para:
 *  1. Comprobar si el AccessibilityService está activo
 *  2. Redirigir al usuario a Ajustes si no lo está
 *  3. Lanzar la automatización con el texto introducido
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private lateinit var btnEnable: Button
    private lateinit var btnRun: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText  = findViewById(R.id.statusText)
        searchInput = findViewById(R.id.searchInput)
        btnEnable   = findViewById(R.id.btnEnable)
        btnRun      = findViewById(R.id.btnRun)

        btnEnable.setOnClickListener {
            // Lleva al usuario a Ajustes > Accesibilidad
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnRun.setOnClickListener {
            val text = searchInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Escribe algo para buscar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Activa primero el servicio de accesibilidad", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val service = ChromeAutomationService.instance
            if (service == null) {
                Toast.makeText(this, "Servicio no disponible. Actívalo en Ajustes.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "🚀 Lanzando automatización...", Toast.LENGTH_SHORT).show()
            service.startAutomation(text)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }

    private fun updateStatusUI() {
        if (isAccessibilityServiceEnabled()) {
            statusText.text = "✅ Servicio de accesibilidad: ACTIVO"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
            btnEnable.text = "Abrir Ajustes de Accesibilidad"
            btnRun.isEnabled = true
        } else {
            statusText.text = "❌ Servicio de accesibilidad: INACTIVO\nVe a Ajustes → Accesibilidad → ChromeAutomator"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            btnEnable.text = "Activar en Ajustes"
            btnRun.isEnabled = false
        }
    }

    /**
     * Comprueba si nuestro AccessibilityService está habilitado
     * consultando el ajuste del sistema ENABLED_ACCESSIBILITY_SERVICES.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/${ChromeAutomationService::class.java.name}"
        val enabledServicesSetting = try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            return false
        }
        if (enabledServicesSetting.isNullOrEmpty()) return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
