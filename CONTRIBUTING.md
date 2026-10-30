# Guía para Contribuir a Spotifree 🎵

¡Gracias por tu interés en contribuir a Spotifree! Este proyecto es de código abierto bajo licencia GNU General Public License v3.0 (GPL-3.0), enfocado en ofrecer un reproductor de música libre, sin anuncios, sin telemetría y con la mejor experiencia de usuario en Android.

---

## 📌 ¿Cómo puedes colaborar?

Hay muchas formas de colaborar con Spotifree, no solo escribiendo código:

1. **Reportar errores (*bugs*)**: Si encuentras un fallo o comportamiento inesperado, revisa los [Issues abiertos](https://github.com/EduardoGTxx/Spotifree/issues) para verificar si ya fue reportado. Si no, crea uno nuevo usando nuestra plantilla de [Reporte de error](https://github.com/EduardoGTxx/Spotifree/issues/new?template=bug_report.yml).
2. **Proponer mejoras o funciones**: Comparte tus sugerencias abriendo una [Propuesta de función](https://github.com/EduardoGTxx/Spotifree/issues/new?template=feature_request.yml).
3. **Traducciones y localización**: Ayúdanos a mantener sincronizados los idiomas disponibles (`es`, `en`, `pt`, `fr`) editando los archivos en `spotifree-android/app/src/main/assets/i18n/`.
4. **Mejorar la documentación**: Corregir erratas, agregar guías de uso o clarificar la arquitectura técnica.
5. **Código**: Resolver incidencias abiertas, optimizar rendimiento o implementar nuevas características.

---

## 🛠️ Entorno de desarrollo y compilación

### Requisitos previos

* **JDK 21** configurado en tu entorno (`JAVA_HOME`).
* **Android SDK** (API 35/36 recomendada, `compileSdk 36`, `minSdk 29`).
* Git y un IDE como **Android Studio** o editor con soporte para Kotlin.

### Pasos para compilar localmente

1. Clona el repositorio:
   ```bash
   git clone https://github.com/EduardoGTxx/Spotifree.git
   cd Spotifree/spotifree-android
   ```

2. Compila el APK en modo depuración (*Debug*):
   ```bash
   ./gradlew assembleDebug
   ```
   El archivo generado se ubicará en `app/build/outputs/apk/debug/app-debug.apk`.

3. Ejecuta las pruebas unitarias:
   ```bash
   ./gradlew testDebugUnitTest
   ```

---

## 📐 Pautas de código y arquitectura

Para mantener el proyecto coherente, mantenible y eficiente:

* **Arquitectura moderna**: La aplicación utiliza Jetpack Compose para la interfaz, Media3 / ExoPlayer para el servicio de reproducción en segundo plano (`PlaybackService`), Room para la base de datos local y NewPipe Extractor para la integración con YouTube / YouTube Music.
* **Textos y localización**: Nunca codifiques textos fijos en la interfaz. Agrega las claves correspondientes en `spotifree-android/app/src/main/assets/i18n/es.json` y mantenlas sincronizadas en los demás archivos de idioma (`en.json`, `pt.json`, `fr.json`).
* **Eficiencia de batería y memoria**: Evita bucles de sondeo (*polling*) o recompilaciones innecesarias en Compose. Utiliza estados derivados (`derivedStateOf`), recolectores de flujo conscientes del ciclo de vida (`collectAsStateWithLifecycle`) y corrutinas estructuradas.
* **Pruebas unitarias**: Si agregas o modificas lógica de cálculo, parseo o repositorios, incluye pruebas en `app/src/test/java/com/spotifree/app/`.
* **Accesibilidad**: Asegúrate de que los componentes interactivos tengan `contentDescription` descriptivos en imágenes/iconos y respeten áreas de pulsación mínimas de 48x48 dp.

---

## 🔀 Proceso para enviar un Pull Request (PR)

1. Crea una rama descriptiva a partir de la rama principal:
   ```bash
   git checkout -b feature/nombre-de-la-mejora
   ```
2. Realiza cambios pequeños, modulares y bien justificados.
3. Asegúrate de que el proyecto compila y las pruebas pasan localmente:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
4. Haz *commit* con un mensaje claro y conciso en español o inglés.
5. Abre un **Pull Request** hacia la rama `main` completando la plantilla provista (`.github/pull_request_template.md`).
6. Un mantenedor revisará tu código para discutir detalles y proceder con la integración.

---

## 📜 Código de Conducta

Al participar en este proyecto, te comprometes a respetar y seguir nuestro [Código de Conducta](CODE_OF_CONDUCT.md). Mantengamos una comunidad amigable, respetuosa e inclusiva para todos.
