package com.example.chromeautomator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChromeAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "ChromeAutomator"

        private val CHROME_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )

        private const val CHROME_URL_BAR_ID = "com.android.chrome:id/url_bar"

        // BUG FIX: 'instance' debe ser @Volatile para visibilidad entre hilos
        @Volatile
        var instance: ChromeAutomationService? = null
    }

    enum class AutomationState {
        IDLE, OPEN_CHROME, WAIT_RESULTS, DONE
    }

    @Volatile
    private var automationState = AutomationState.IDLE
    private var searchText: String = ""
    private val handler = Handler(Looper.getMainLooper())

    // BUG FIX: contador de reintentos para evitar bucle infinito en tryClickFirstResult
    private var retryCount = 0
    private val maxRetries = 8

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Servicio de accesibilidad conectado")
    }

    override fun onDestroy() {
        // BUG FIX: cancelar callbacks pendientes al destruir el servicio para evitar leaks
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Servicio interrumpido")
    }

    fun startAutomation(text: String) {
        searchText = text
        retryCount = 0
        automationState = AutomationState.OPEN_CHROME
        Log.d(TAG, "Iniciando automatizacion: '$searchText'")
        launchChrome()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        val eventType = event.eventType

        if (automationState == AutomationState.IDLE || automationState == AutomationState.DONE) return

        when (automationState) {
            AutomationState.OPEN_CHROME -> {
                if (pkg in CHROME_PACKAGES &&
                    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    Log.d(TAG, "Chrome detectado")
                    automationState = AutomationState.WAIT_RESULTS
                    // BUG FIX: pausa más larga (2.5s) — Chrome en tablets tarda más en renderizar
                    handler.postDelayed({ tryClickFirstResult() }, 2500)
                }
            }

            AutomationState.WAIT_RESULTS -> {
                if (pkg in CHROME_PACKAGES &&
                    eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    // BUG FIX: remover callbacks anteriores antes de añadir uno nuevo
                    // Evita que se llame tryClickFirstResult() decenas de veces a la vez
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ tryClickFirstResult() }, 800)
                }
            }

            else -> {}
        }
    }

    private fun launchChrome() {
        val searchUrl = "https://www.google.com/search?q=${Uri.encode(searchText)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            applicationContext.startActivity(intent)
            Log.d(TAG, "Chrome lanzado: $searchUrl")
        } catch (e: Exception) {
            Log.w(TAG, "Chrome no disponible, usando navegador por defecto")
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                applicationContext.startActivity(fallback)
                automationState = AutomationState.WAIT_RESULTS
                handler.postDelayed({ tryClickFirstResult() }, 3000)
            } catch (e2: Exception) {
                Log.e(TAG, "No se pudo abrir ningun navegador: ${e2.message}")
                automationState = AutomationState.IDLE
            }
        }
    }

    private fun tryClickFirstResult() {
        if (automationState == AutomationState.DONE) return

        // BUG FIX: límite de reintentos para no quedar en bucle infinito
        if (retryCount >= maxRetries) {
            Log.e(TAG, "Maximo de reintentos alcanzado. Abortando.")
            automationState = AutomationState.IDLE
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "Ventana no disponible, reintento ${retryCount + 1}/$maxRetries")
            retryCount++
            handler.postDelayed({ tryClickFirstResult() }, 1500)
            return
        }

        Log.d(TAG, "Buscando primer resultado (intento ${retryCount + 1})...")

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        root.collectClickableNodes(candidates)
        Log.d(TAG, "Nodos clickables: ${candidates.size}")

        // BUG FIX: filtro mejorado — buscar nodos que tengan texto sustancial
        // y no sean parte de la UI de Chrome (barra de tabs, botones, etc.)
        val firstResult = candidates.firstOrNull { node ->
            val text = (node.text?.toString() ?: "").trim()
            val desc = (node.contentDescription?.toString() ?: "").trim()
            val combined = "$text $desc".lowercase().trim()
            val viewId = node.viewIdResourceName ?: ""

            combined.length > 15                              // texto suficientemente largo
            && !viewId.startsWith("com.android.chrome")      // no es UI nativa de Chrome
            && !combined.contains("tab")                     // no es un tab del navegador
            && !combined.contains("address bar")             // no es la barra de URL
            && combined != searchText.lowercase()            // no es nuestra propia búsqueda
        }

        if (firstResult != null) {
            val nodeText = firstResult.text ?: firstResult.contentDescription ?: "sin texto"
            Log.d(TAG, "Pulsando: '$nodeText'")

            val clicked = firstResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            if (!clicked) {
                Log.d(TAG, "ACTION_CLICK fallo, usando gesto de toque")
                val bounds = Rect()
                firstResult.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    performTapGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                }
            }

            automationState = AutomationState.DONE
            Log.d(TAG, "Automatizacion completada")

        } else {
            retryCount++
            Log.w(TAG, "No se encontro resultado, reintento $retryCount/$maxRetries en 2s")
            handler.postDelayed({ tryClickFirstResult() }, 2000)
        }

        // BUG FIX: recycle SIEMPRE después de usar rootInActiveWindow
        root.recycle()
    }

    private fun performTapGesture(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesto completado en ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesto cancelado")
            }
        }, null)
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}

// ── Extensiones ───────────────────────────────────────────────────────────────

fun AccessibilityNodeInfo.findNodeByViewId(viewId: String): AccessibilityNodeInfo? =
    findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()

fun AccessibilityNodeInfo.findFirstNodeByClass(className: String): AccessibilityNodeInfo? {
    if (this.className?.toString() == className && isEnabled && isFocusable) return this
    for (i in 0 until childCount) {
        val child = getChild(i) ?: continue
        val found = child.findFirstNodeByClass(className)
        // BUG FIX: solo reciclar el hijo si NO es el resultado que devolvemos
        if (found != null) return found
        child.recycle()
    }
    return null
}

fun AccessibilityNodeInfo.collectClickableNodes(
    result: MutableList<AccessibilityNodeInfo>,
    depth: Int = 0,
    maxDepth: Int = 15
) {
    if (depth > maxDepth) return
    if (isClickable && isEnabled && isVisibleToUser) {
        result.add(this)
    }
    for (i in 0 until childCount) {
        // BUG FIX: verificar null antes de llamar recursivamente
        getChild(i)?.collectClickableNodes(result, depth + 1, maxDepth)
    }
}
