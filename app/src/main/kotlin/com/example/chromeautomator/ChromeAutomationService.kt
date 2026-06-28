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

/**
 * ChromeAutomationService
 *
 * Servicio de accesibilidad que automatiza interacciones en Chrome.
 *
 * FLUJO PRINCIPAL:
 *   1. MainActivity lanza Chrome con un Intent
 *   2. Este servicio detecta que Chrome está activo (onAccessibilityEvent)
 *   3. Busca la barra de URL/búsqueda y escribe el texto
 *   4. Espera a que carguen los resultados de Google
 *   5. Pulsa el primer enlace
 *
 * ESTADOS de la máquina de estados (automationState):
 *   IDLE         → esperando instrucción
 *   OPEN_CHROME  → Chrome lanzado, esperando que aparezca
 *   TYPE_SEARCH  → Chrome visible, escribir en la barra de búsqueda
 *   WAIT_RESULTS → búsqueda enviada, esperando resultados
 *   CLICK_FIRST  → resultados visibles, pulsar el primer enlace
 *   DONE         → tarea completada
 */
class ChromeAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "ChromeAutomator"

        // Acción que MainActivity envía para arrancar la automatización
        const val ACTION_START_AUTOMATION = "com.example.chromeautomator.START_AUTOMATION"
        const val EXTRA_SEARCH_TEXT = "search_text"

        // Paquetes de Chrome (puede variar según versión instalada)
        private val CHROME_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
        )

        // Resource IDs de Chrome (pueden cambiar entre versiones)
        // Para encontrar IDs actuales: usa uiautomatorviewer o Layout Inspector via ADB
        private const val CHROME_URL_BAR_ID = "com.android.chrome:id/url_bar"
        private const val CHROME_SEARCH_BOX_ID = "com.android.chrome:id/search_box_text"

        // Instancia estática para que MainActivity pueda comunicarse
        var instance: ChromeAutomationService? = null
    }

    // ── Máquina de estados ────────────────────────────────────────────────────
    enum class AutomationState {
        IDLE, OPEN_CHROME, TYPE_SEARCH, WAIT_RESULTS, CLICK_FIRST, DONE
    }

    private var automationState = AutomationState.IDLE
    private var searchText: String = ""
    private val handler = Handler(Looper.getMainLooper())

    // ── Ciclo de vida del servicio ────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Servicio de accesibilidad conectado")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "🛑 Servicio destruido")
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Servicio interrumpido")
    }

    // ── Punto de entrada desde MainActivity ──────────────────────────────────

    /**
     * Llama a este método desde MainActivity para iniciar la automatización.
     * Ejemplo: ChromeAutomationService.instance?.startAutomation("kotlin tutorial")
     */
    fun startAutomation(text: String) {
        searchText = text
        automationState = AutomationState.OPEN_CHROME
        Log.d(TAG, "🚀 Iniciando automatización: buscar '$searchText'")
        launchChrome()
    }

    // ── Evento principal: aquí llega CADA cambio en pantalla ─────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        val eventType = event.eventType

        Log.v(TAG, "Event: type=${AccessibilityEvent.eventTypeToString(eventType)} pkg=$pkg state=$automationState")

        // Ignorar eventos si no estamos en una tarea activa
        if (automationState == AutomationState.IDLE || automationState == AutomationState.DONE) return

        when (automationState) {

            AutomationState.OPEN_CHROME -> {
                // Esperamos a que Chrome sea el paquete activo
                if (pkg in CHROME_PACKAGES &&
                    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    Log.d(TAG, "📱 Chrome detectado")
                    automationState = AutomationState.TYPE_SEARCH
                    // Pequeña pausa para que Chrome termine de renderizar
                    handler.postDelayed({ typeInSearchBar() }, 1500)
                }
            }

            AutomationState.TYPE_SEARCH -> {
                // Ya manejado por typeInSearchBar(), no necesitamos reaccionar aquí
            }

            AutomationState.WAIT_RESULTS -> {
                // Esperamos que la página de resultados de Google cargue
                if (pkg in CHROME_PACKAGES &&
                    (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                     eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
                    handler.postDelayed({ tryClickFirstResult() }, 500)
                }
            }

            else -> { /* CLICK_FIRST y DONE se gestionan en sus propias funciones */ }
        }
    }

    // ── PASO 1: Lanzar Chrome ─────────────────────────────────────────────────

    private fun launchChrome() {
        // Opción A: Intent directo a Chrome con URL de búsqueda Google
        // Esto es más confiable que buscar la barra de URL manualmente
        val searchUrl = "https://www.google.com/search?q=${Uri.encode(searchText)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
            setPackage("com.android.chrome")      // Fuerza Chrome (no otro navegador)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            applicationContext.startActivity(intent)
            Log.d(TAG, "🌐 Chrome lanzado con URL: $searchUrl")
        } catch (e: Exception) {
            // Chrome no instalado: abrir con el navegador por defecto
            Log.w(TAG, "Chrome no disponible, usando navegador por defecto: ${e.message}")
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(fallback)

            // En el caso sin Chrome específico, aún así esperamos resultados
            automationState = AutomationState.WAIT_RESULTS
        }
    }

    // ── PASO 2: Escribir en la barra de búsqueda ─────────────────────────────

    /**
     * Busca la barra de URL de Chrome y escribe el texto de búsqueda.
     *
     * Como ya lanzamos Chrome con la URL de Google directamente,
     * esto sirve como alternativa si preferimos escribir manualmente.
     *
     * Para la búsqueda directa de Google, saltamos al paso WAIT_RESULTS.
     */
    private fun typeInSearchBar() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "No hay ventana activa, reintentando...")
            handler.postDelayed({ typeInSearchBar() }, 1000)
            return
        }

        // Estrategia 1: buscar por resource-id conocido
        var urlBar = root.findNodeByViewId(CHROME_URL_BAR_ID)

        // Estrategia 2: buscar por className (EditText visible y enfocable)
        if (urlBar == null) {
            urlBar = root.findFirstNodeByClass("android.widget.EditText")
        }

        if (urlBar != null) {
            Log.d(TAG, "✏️ Barra de URL encontrada: ${urlBar.viewIdResourceName}")
            urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Inyectar texto
            val args = Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, searchText)
            }
            urlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Simular ENTER para enviar la búsqueda
            handler.postDelayed({
                urlBar.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
                automationState = AutomationState.WAIT_RESULTS
                Log.d(TAG, "🔍 Búsqueda enviada: '$searchText'")
                // Esperar 3s a que carguen los resultados
                handler.postDelayed({ tryClickFirstResult() }, 3000)
            }, 500)

        } else {
            Log.d(TAG, "ℹ️ Barra de URL no encontrada (normal si usamos URL directa)")
            // Si lanzamos Chrome con la URL completa, ya estamos en resultados
            automationState = AutomationState.WAIT_RESULTS
            handler.postDelayed({ tryClickFirstResult() }, 3000)
        }

        root.recycle()
    }

    // ── PASO 3: Pulsar el primer resultado ────────────────────────────────────

    /**
     * Busca el primer enlace orgánico de los resultados de Google y lo pulsa.
     *
     * Google renderiza los resultados como WebView dentro de Chrome.
     * Los nodos accesibles del WebView aparecen como elementos de texto
     * con className "android.view.View" o similares.
     *
     * IMPORTANTE: La estructura del DOM de Google cambia frecuentemente.
     * Esta función usa varias estrategias de fallback.
     */
    private fun tryClickFirstResult() {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "Ventana no disponible aún para resultados")
            return
        }

        Log.d(TAG, "🔎 Buscando primer resultado en el árbol de vistas...")

        // Estrategia 1: buscar nodos con contentDescription que parezcan resultados
        // Google suele marcar los títulos de resultado con roles de encabezado
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        root.collectClickableNodes(candidates)

        Log.d(TAG, "Nodos clickables encontrados: ${candidates.size}")

        // Filtramos candidatos que parezcan resultados reales:
        // - tienen texto (no son iconos vacíos)
        // - no son la barra de búsqueda (su texto no contiene nuestra query exacta como único contenido)
        // - no son elementos de navegación de Chrome (tabs, botones de toolbar)
        val firstResult = candidates.firstOrNull { node ->
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val combined = (text + desc).lowercase()
            combined.isNotBlank() &&
            !combined.contains("search") &&
            combined.length > 10 &&
            // Excluir elementos de Chrome UI
            node.viewIdResourceName?.contains("com.android.chrome") != true
        }

        if (firstResult != null) {
            Log.d(TAG, "🖱️ Pulsando resultado: '${firstResult.text ?: firstResult.contentDescription}'")

            // Método 1: ACTION_CLICK (más fiable para elementos accesibles)
            val clicked = firstResult.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            if (!clicked) {
                // Método 2: Gesto de toque en las coordenadas del elemento
                Log.d(TAG, "ACTION_CLICK falló, intentando gesto de toque...")
                val bounds = Rect()
                firstResult.getBoundsInScreen(bounds)
                performTapGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            }

            automationState = AutomationState.DONE
            Log.d(TAG, "✅ Automatización completada")

        } else {
            Log.w(TAG, "⏳ No se encontró resultado todavía, reintentando en 2s...")
            handler.postDelayed({ tryClickFirstResult() }, 2000)
        }

        root.recycle()
    }

    // ── Utilidades de gestos ──────────────────────────────────────────────────

    /**
     * Simula un toque en coordenadas absolutas de pantalla.
     * Útil cuando ACTION_CLICK no funciona (WebViews, canvas, etc.)
     */
    private fun performTapGesture(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,       // startTime (ms desde inicio del gesto)
                    100      // duration (ms) — toque corto
                )
            )
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "✅ Gesto de toque completado en ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "❌ Gesto cancelado")
            }
        }, null)
    }

    /**
     * Simula un swipe (deslizamiento) — útil para scroll.
     */
    private fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
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

// ── Extensiones de AccessibilityNodeInfo ─────────────────────────────────────

/**
 * Busca un nodo por su viewIdResourceName (resource-id en Layout Inspector).
 * Recuerda llamar a recycle() en el nodo cuando termines de usarlo.
 */
fun AccessibilityNodeInfo.findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
    val nodes = findAccessibilityNodeInfosByViewId(viewId)
    return nodes?.firstOrNull()
}

/**
 * Busca el primer nodo por className que sea clickable.
 */
fun AccessibilityNodeInfo.findFirstNodeByClass(className: String): AccessibilityNodeInfo? {
    if (this.className?.toString() == className && this.isEnabled && this.isFocusable) {
        return this
    }
    for (i in 0 until childCount) {
        val child = getChild(i) ?: continue
        val result = child.findFirstNodeByClass(className)
        if (result != null) return result
        child.recycle()
    }
    return null
}

/**
 * Recorre todo el árbol de vistas y recolecta nodos clickables.
 * Limite de profundidad para evitar bucles infinitos en WebViews complejos.
 */
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
        getChild(i)?.collectClickableNodes(result, depth + 1, maxDepth)
    }
}
