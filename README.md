<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="112" alt="Icono de Spotifree"/>

# Spotifree

**Reproductor de música libre para Android, sin anuncios y sin cuentas obligatorias.**

[![Release](https://img.shields.io/github/v/release/EduardoGTxx/Spotifree?label=versi%C3%B3n&color=2979ff)](https://github.com/EduardoGTxx/Spotifree/releases/latest)
[![CI](https://github.com/EduardoGTxx/Spotifree/actions/workflows/ci.yml/badge.svg)](https://github.com/EduardoGTxx/Spotifree/actions/workflows/ci.yml)
[![Descargas](https://img.shields.io/github/downloads/EduardoGTxx/Spotifree/total?color=2979ff&label=descargas)](https://github.com/EduardoGTxx/Spotifree/releases)
![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
[![Licencia GPL-3.0](https://img.shields.io/github/license/EduardoGTxx/Spotifree?color=blue)](LICENSE)

[**⬇️ Descargar el APK**](https://github.com/EduardoGTxx/Spotifree/releases/latest/download/Spotifree.apk) · [Novedades](https://github.com/EduardoGTxx/Spotifree/releases) · [Integraciones](docs/INTEGRACIONES.md) · [Reportar un error](https://github.com/EduardoGTxx/Spotifree/issues/new/choose)

</div>

---

## 🎧 ¿Qué es Spotifree?

Spotifree busca, reproduce y descarga música de YouTube Music con una interfaz oscura inspirada en Spotify (acento azul eléctrico `#2979ff` y modo AMOLED). No muestra anuncios, no recopila datos y no te obliga a iniciar sesión.

Desde la **versión 2.0** es una app **nativa en Kotlin** (Jetpack Compose + Media3 + Room). Si la instalas encima de la versión anterior (React + Capacitor), migra sola tu biblioteca, Me gusta, historial, cola, cuenta de YouTube Music y canciones descargadas.

## ✨ Funciones

| | |
|---|---|
| ▶️ **Reproducción nativa** | Media3/ExoPlayer en segundo plano, notificación, pantalla de bloqueo, Bluetooth, Android Auto, Chromecast y widget. La cola se conserva al cerrar la app. |
| 🔎 **Explorar** | Canciones, álbumes, artistas y playlists de YouTube Music, con radio infinita al terminar la cola. |
| ⬇️ **Descargas** | Siguen aunque cierres la app, caché automática mientras escuchas y modo sin conexión. |
| 🎤 **Letras** | Sincronizadas, modo karaoke, traducción, romanización (coreano, japonés, cirílico, griego) y tamaño ajustable. |
| 🎚️ **Audio** | Ecualizador de 5 bandas, Slowed + Reverb, Nightcore, Vaporwave, velocidad, crossfade, normalización y temporizador de apagado. |
| 📚 **Biblioteca** | Playlists, Me gusta, artistas seguidos, contenido oculto y música guardada en el teléfono, con portadas automáticas. |
| 📥 **Importar** | Datos de Spotify ("Descargar tus datos"), CSV (Exportify, Spotlistr, TuneMyMusic, Soundiiz…) y playlists de tu cuenta de YouTube Music. |
| ☁️ **Copias de seguridad** | JSON manual o automático y Google Drive. |
| 🎶 **Extras** | ¿Qué suena?, Escuchar juntos (Jam), estadísticas y Rewind anual. |
| 🏋️ **TGym** | Modo entrenamiento: baja la música durante el descanso, avisa al terminar y lleva tu racha de días. |
| 🔄 **Actualizaciones** | Avisos desde GitHub Releases con verificación SHA-256. |
| 🌐 **Idiomas** | Español, inglés, portugués y francés. |

## 📲 Instalación

1. Descarga [`Spotifree.apk`](https://github.com/EduardoGTxx/Spotifree/releases/latest/download/Spotifree.apk) desde la última versión.
2. Ábrelo y permite "Instalar apps desconocidas" si Android lo pide.
3. Las siguientes versiones se anuncian dentro de la app.

Para comprobar el archivo, compara su huella con la de `SHA256SUMS.txt` del mismo release.

## 🗂️ Estructura

```text
Spotifree/
├── spotifree-android/        # App Android (Kotlin, Compose, Media3, Room, WorkManager)
│   └── app/src/main/java/com/spotifree/app/
│       ├── data/             # YouTube (NewPipe Extractor), base de datos, descargas, letras, importadores…
│       ├── playback/         # PlaybackService, resolución de audio, Cast y Android Auto
│       ├── ui/               # Pantallas Compose
│       ├── widget/           # Widget de pantalla de inicio (Glance)
│       └── workout/          # Puente con TGym
├── jam-server/               # Servidor WebSocket para "Escuchar juntos"
├── docs/INTEGRACIONES.md     # Drive, ¿Qué suena?, Jam, Chromecast y TGym
└── .github/workflows/        # CI y Release (APK firmado + manifiesto de actualización)
```

## 🛠️ Compilar

Requisitos: **JDK 21** y **Android SDK 36**.

```bash
cd spotifree-android
./gradlew testDebugUnitTest   # pruebas
./gradlew assembleDebug       # APK de prueba
./gradlew assembleRelease     # APK firmado (necesita la llave)
```

La llave de firma **no está en el repositorio**. Para firmar en tu equipo crea `spotifree-android/keystore.properties` (Git lo ignora):

```properties
storeFile=app/spotifree.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Usa siempre la misma llave, porque Android solo acepta una actualización firmada con la llave de la versión instalada.

## 🚀 Publicar una versión

1. **Una sola vez:** en *Settings → Secrets and variables → Actions*, crea `SPOTIFREE_KEYSTORE_BASE64`, `SPOTIFREE_KEYSTORE_PASSWORD`, `SPOTIFREE_KEY_ALIAS` y `SPOTIFREE_KEY_PASSWORD`.
2. Crea y sube un tag:
   ```bash
   git tag v2.0.1
   git push origin v2.0.1
   ```
3. El workflow **Release Android** se encarga del resto:
   - pasa las pruebas;
   - compila el APK con la versión del tag (`v2.0.1` → `versionCode 20001`);
   - verifica la firma;
   - publica `Spotifree.apk`, `update-manifest.json`, `SHA256SUMS.txt` y `jam-server.zip`.

Además, cada push a `main` y cada pull request ejecuta el workflow **CI** (pruebas + APK de prueba).

---

## 🙏 Créditos

Spotifree existe gracias al trabajo abierto de muchas personas y proyectos.

### Proyectos que lo inspiraron
- **[RiPlay](https://github.com/fast4x/RiPlay)** de [fast4x](https://github.com/fast4x): base y referencia principal de Spotifree.
- **[RiMusic](https://github.com/fast4x/RiMusic)** de fast4x y **[ViMusic](https://github.com/vfsfitvnm/ViMusic)** de [vfsfitvnm](https://github.com/vfsfitvnm): los proyectos de los que nace RiPlay.
- **[TGym](https://github.com/Truesilverking/TGym)**: app de entrenamiento con la que Spotifree se conecta.

### Música y contenido
- **[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)** de [TeamNewPipe](https://github.com/TeamNewPipe), el corazón de la búsqueda y la reproducción (GPL-3.0).
- **[YouTube Music](https://music.youtube.com)**, de donde vienen el catálogo y el audio.

### Letras y traducción
- **[SimpMusic Lyrics](https://github.com/maxrave-dev/SimpMusic)** de [maxrave-dev](https://github.com/maxrave-dev).
- **[LRCLIB](https://lrclib.net)** ([código](https://github.com/tranxuanthang/lrclib)) de [tranxuanthang](https://github.com/tranxuanthang): letras sincronizadas libres.
- **KuGou**, letras sincronizadas adicionales.
- **Google Translate**, para la traducción de letras.

### Portadas, reconocimiento y nube
- **[iTunes Search API](https://performance-partners.apple.com/search-api)** (Apple) y **[Deezer API](https://developers.deezer.com/api)**: portadas de las canciones importadas.
- **[AudD](https://audd.io)** y **[ACRCloud](https://www.acrcloud.com)**: reconocimiento de canciones (con tu propia clave).
- **[Google Drive API](https://developers.google.com/drive)**: copias de seguridad en la nube.
- **[Google Cast](https://developers.google.com/cast)**: Chromecast.
- **[Exportify](https://github.com/watsonbox/exportify)** de [watsonbox](https://github.com/watsonbox) y servicios similares, cuyo formato CSV entiende el importador.

### Librerías
- **[Kotlin](https://kotlinlang.org)**, **[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)** y **[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)** (JetBrains).
- **[Android Jetpack](https://developer.android.com/jetpack)** (Google):
  - Compose y Material 3;
  - **[Media3 / ExoPlayer](https://github.com/androidx/media)**;
  - Room, WorkManager, DataStore, Navigation, Lifecycle, Glance, Palette y SplashScreen.
- **[OkHttp](https://github.com/square/okhttp)** de [Square](https://github.com/square).
- **[Coil](https://github.com/coil-kt/coil)**: carga de imágenes.
- **[Material Symbols](https://fonts.google.com/icons)** (Google): iconos.
- **[ws](https://github.com/websockets/ws)**: servidor de "Escuchar juntos".

### Herramientas
- **[Gradle](https://gradle.org)** y el **Android Gradle Plugin**.
- **[GitHub Actions](https://github.com/features/actions)** con [actions/checkout](https://github.com/actions/checkout), [actions/setup-java](https://github.com/actions/setup-java), [gradle/actions](https://github.com/gradle/actions) y [softprops/action-gh-release](https://github.com/softprops/action-gh-release).
- **[Eclipse Temurin](https://adoptium.net)**: JDK de las compilaciones.
- **[Shields.io](https://shields.io)**: insignias de este README.

¿Falta alguien? Abre un issue o un pull request y lo añadimos. 💙

---

## ⚠️ Aviso legal

Spotifree es un proyecto personal sin fines de lucro. **No está afiliado, patrocinado ni respaldado por Spotify, YouTube, Google, Apple, Deezer ni ningún otro servicio mencionado.** Todas las marcas pertenecen a sus dueños.

El contenido se obtiene de fuentes públicas. Cada usuario es responsable de usarlo según las leyes de su país y los términos de esos servicios. Apoya a los artistas que escuchas.

## 📄 Licencia

Distribuido bajo **[GPL-3.0](LICENSE)**, la misma licencia de NewPipe Extractor y RiPlay. Si compartes o modificas el APK, publica también el código fuente.
