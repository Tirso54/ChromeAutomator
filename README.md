# ChromeAutomator

App Android que automatiza acciones en Chrome usando el **AccessibilityService** de Android — sin root, sin ADB, sin Android Studio.

## ¿Qué hace?

- Abre Chrome automáticamente
- Busca en Google el texto que introduzcas
- Pulsa el primer resultado de la página

## Requisitos

- Android 8.0 (API 26) o superior
- Google Chrome instalado
- El servicio de accesibilidad activado manualmente (ver instrucciones)

## Cómo compilar

El APK se compila automáticamente con GitHub Actions en cada push.

1. Ve a la pestaña **Actions** del repositorio
2. Entra en el último workflow completado
3. Descarga el artefacto **ChromeAutomator-debug**

## Cómo instalar

1. Descarga el APK desde Actions
2. En tu Android: **Ajustes → Aplicaciones → Instalar apps desconocidas** → activa el permiso para tu navegador
3. Abre el APK descargado e instala

## Cómo activar el servicio de accesibilidad

Este paso es **obligatorio** y debe hacerse manualmente:

1. Abre la app **Chrome Automator**
2. Pulsa **"Activar en Ajustes"**
3. En la lista de servicios, busca **Chrome Automator** y actívalo
4. Vuelve a la app — el estado debería mostrar ✅ ACTIVO

> En Xiaomi/MIUI puede aparecer en **Ajustes → Accesibilidad → Servicios instalados**

## Cómo usar

1. Abre **Chrome Automator**
2. Escribe lo que quieres buscar en Google
3. Pulsa **🚀 Buscar en Chrome**
4. La app abrirá Chrome, buscará y pulsará el primer resultado automáticamente

## Estructura del proyecto

```
ChromeAutomator/
├── .github/workflows/build.yml              # GitHub Actions — compila el APK
├── app/src/main/
│   ├── AndroidManifest.xml                  # Permisos y registro del servicio
│   ├── kotlin/com/example/chromeautomator/
│   │   ├── ChromeAutomationService.kt       # Núcleo: AccessibilityService
│   │   └── MainActivity.kt                  # UI de control
│   └── res/
│       └── xml/accessibility_service_config.xml  # Configuración del servicio
└── gradle/wrapper/gradle-wrapper.properties
```

## Cómo extender a otras apps

1. Añade el paquete de la app en `accessibility_service_config.xml`:
```xml
android:packageNames="com.android.chrome,com.tuapp.nueva"
```
2. Añade nuevos estados a la máquina de estados en `ChromeAutomationService.kt`
3. Detecta los eventos con `onAccessibilityEvent()` y actúa con `performAction()`

## Tecnología

- **Kotlin** puro
- **AccessibilityService** de Android
- **GitHub Actions** para compilación en la nube
- Sin root · Sin librerías externas · Sin Android Studio
