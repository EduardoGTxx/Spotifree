# 🎵 Spotifree

**Spotifree** es un reproductor de música libre para **Android 10+**: busca, reproduce y descarga música de YouTube Music sin anuncios ni cuentas obligatorias. Interfaz oscura inspirada en Spotify con acento **azul eléctrico (`#2979ff`)** y modo AMOLED.

Desde la versión 2.0 es una **app nativa en Kotlin** (Jetpack Compose + Media3 + Room). Al instalarla encima de la versión anterior (React + Capacitor) migra automáticamente la biblioteca, los Me gusta, el historial, la cola, la cuenta de YouTube Music y las canciones descargadas.

---

## ✨ Funciones

- **Reproducción nativa (Media3/ExoPlayer)** en segundo plano, con notificación, pantalla de bloqueo, Bluetooth, Android Auto y widget. La cola se conserva al cerrar la app.
- **Búsqueda y páginas** de canciones, álbumes, artistas y playlists de YouTube Music, con radio infinita al terminar la cola.
- **Descargas** con WorkManager (continúan con la app cerrada), caché automática mientras escuchas y **modo sin conexión**.
- **Letras sincronizadas** (SimpMusic, LRCLIB y KuGou) con modo karaoke, traducción, romanización y tamaño ajustable.
- **Audio**: ecualizador de 5 bandas, estilos (Slowed + Reverb, Nightcore, Vaporwave), velocidad, transición entre canciones, normalización y temporizador de apagado.
- **Biblioteca**: playlists, Me gusta, artistas seguidos, contenido oculto y música guardada en el teléfono. Las canciones importadas sin portada la obtienen de Apple Music o Deezer.
- **Importación**: datos de Spotify ("Descargar tus datos"), CSV (Exportify y similares) y playlists de tu cuenta de YouTube Music.
- **Copias de seguridad** en JSON (manual o automática) y en Google Drive.
- **Extras**: ¿Qué suena? (AudD / ACRCloud), Escuchar juntos (Jam), Chromecast, estadísticas y Rewind anual.
- **TGym**: modo entrenamiento con temporizador de descanso que baja la música, racha de días y conexión opcional con la app [TGym](https://github.com/Truesilverking/TGym).
- **Actualizaciones** desde GitHub Releases con verificación SHA-256.
- Idiomas: español, inglés, portugués y francés.

## 🏗️ Estructura

```text
Spotifree/
├── spotifree-android/        # App Android (Kotlin, Compose, Media3, Room, WorkManager)
│   └── app/src/main/java/com/spotifree/app/
│       ├── data/             # Repositorios: YouTube (NewPipe Extractor), Room, descargas, letras, importadores…
│       ├── playback/         # PlaybackService (MediaLibraryService), resolución de audio, Cast, Android Auto
│       ├── ui/               # Pantallas Compose
│       ├── widget/           # Widget de la pantalla de inicio (Glance)
│       └── workout/          # Puente con TGym
├── jam-server/               # Servidor WebSocket para "Escuchar juntos"
├── docs/INTEGRACIONES.md     # Configuración de Drive, ¿Qué suena?, Jam, Chromecast y TGym
└── .github/workflows/        # CI: pruebas, APK firmado y Release con manifiesto de actualización
```

## 🛠️ Compilar

Requisitos: JDK 21 y Android SDK 36.

```powershell
cd spotifree-android
$env:JAVA_HOME = "C:\ruta\a\jdk-21"
.\gradlew testDebugUnitTest      # pruebas
.\gradlew assembleRelease        # APK firmado en app\build\outputs\apk\release\
```

La llave de firma **no está en el repositorio**. Para firmar en local crea `spotifree-android/keystore.properties` (ignorado por Git):

```properties
storeFile=app/spotifree.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Debe ser siempre la misma llave: Android solo instala una actualización si está firmada con la llave de la versión instalada.

## 🚀 Publicar una versión

1. Una sola vez, en **Settings › Secrets and variables › Actions**, crea los secretos `SPOTIFREE_KEYSTORE_BASE64` (la llave en Base64), `SPOTIFREE_KEYSTORE_PASSWORD`, `SPOTIFREE_KEY_ALIAS` y `SPOTIFREE_KEY_PASSWORD`.
2. Crea y sube un tag: `git tag v2.0.1` y `git push origin v2.0.1` (o lanza **Actions › Release Android › Run workflow**).
3. El workflow pasa las pruebas, compila el APK con `versionName` y `versionCode` sacados del tag (v2.0.1 → 20001), comprueba que está firmado con la llave de Spotifree y publica en el Release:
   - `Spotifree.apk`
   - `update-manifest.json` (la app lo usa para avisar de la actualización)
   - `SHA256SUMS.txt`
   - `jam-server.zip` (servidor opcional de "Escuchar juntos")

Cada push a `main` y cada pull request ejecuta además el workflow **CI** (pruebas + APK de prueba como artefacto).

## ⚖️ Licencia

GPL-3.0. Spotifree usa [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0); si distribuyes el APK, publica también el código fuente.
