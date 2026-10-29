# Integraciones opcionales de Spotifree

Todas son opcionales: la app funciona igual sin ellas. Ninguna clave viaja en las copias de seguridad.

---

## 1. ¿Qué suena? (reconocimiento de canciones)

Se abre desde Inicio → icono de ondas (arriba a la derecha).

### AudD (lo más simple)
1. Crea una cuenta en https://dashboard.audd.io y copia tu **api_token**.
2. En la app: Ajustes → Integraciones → Servicio de reconocimiento **AudD** → Clave → pega el token.

### ACRCloud
1. En https://console.acrcloud.com crea un proyecto **Audio & Video Recognition** (bucket "ACRCloud Music").
2. Anota el **Host** (por ejemplo `identify-us-west-2.acrcloud.com`), el **Access Key** y el **Access Secret**.
3. En la app elige **ACRCloud** y escribe los tres valores separados por `|`:
   `identify-us-west-2.acrcloud.com|TU_ACCESS_KEY|TU_ACCESS_SECRET`

La primera vez Android pide permiso de micrófono.

---

## 2. Escuchar juntos (Jam)

Necesita un servidor propio (gratis). Instrucciones en [`jam-server/README.md`](../jam-server/README.md).

En la app: Ajustes → Integraciones → Servidor Jam → pega la URL `wss://...`. Luego, en el reproductor, botón de dispositivos → Crear sesión / Unirse. Si un invitado pierde la conexión, la app vuelve a unirse sola a la misma sala (hasta 5 intentos).

---

## 3. Copia en Google Drive

Usa el permiso `drive.file`: la app solo ve el archivo que ella misma crea, `Spotifree_Backup.json`.

### Configuración única en Google Cloud
1. En https://console.cloud.google.com crea un proyecto (por ejemplo "Spotifree").
2. **APIs y servicios → Biblioteca** → **Google Drive API** → **Habilitar**.
3. **Pantalla de consentimiento de OAuth**: tipo **Externo**, nombre Spotifree, alcance `https://www.googleapis.com/auth/drive.file`, y tu cuenta en **Usuarios de prueba**.
4. **Credenciales → Crear credenciales → ID de cliente de OAuth** de tipo **Android**:
   - Paquete: `com.spotifree.app`.
   - Huella SHA-1 (PowerShell, desde `spotifree-android`):

     ```powershell
     & "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore app\spotifree.keystore -alias spotifree -storepass spotifree
     ```
5. No hay que pegar nada en la app: Google la reconoce por el paquete y la firma.

En la app (Ajustes → Copias de seguridad): **Guardar copia en Google Drive**, **Restaurar desde Google Drive** y **Copia automática en Drive** (usa la frecuencia de la copia automática y nunca muestra ventanas en segundo plano).

Para revocar el acceso: https://myaccount.google.com/permissions

---

## 4. Chromecast

En el reproductor: botón de dispositivos → icono de Cast → elige el televisor o altavoz (misma Wi-Fi).

- Se transmiten las canciones de YouTube; las descargadas y las del teléfono no, porque el Chromecast no puede leer archivos del teléfono.
- **Receptor personalizado (opcional)**: registra una app receptora en https://cast.google.com/publish y pega su ID en Ajustes → Integraciones → Receptor de Chromecast. Se aplica al reiniciar la app.

---

## 5. TGym

Spotifree tiene su propio modo entrenamiento (Ajustes → Integración con TGym → Habilitar). Además se conecta, si el usuario lo activa en ambas apps, con la app TGym:

| Desde TGym | Cómo llega a Spotifree | Qué hace Spotifree |
| --- | --- | --- |
| "Abrir Spotifree" / botón de música | Enlace `spotifree://`, `spotifree://playlist/<id>` o `spotifree://workout` | Abre la app; una playlist existente o, si no existe (p. ej. `pl-gym-beast-mode`), la pantalla de entrenamiento |
| Descanso iniciado / terminado | Broadcast `com.spotifree.app.action.TGYM_REST` | Baja la música al nivel configurado y la restaura con un aviso sonoro al terminar (también si TGym no avisa del final) |
| Entrenamiento terminado | El mismo broadcast con `type=WORKOUT_FINISH` | Suma el día a la racha |

Contrato del broadcast (paquete explícito `com.spotifree.app`):

```text
action        com.spotifree.app.action.TGYM_REST
type          REST_START | REST_END | WORKOUT_FINISH
REST_START    seconds (int), endsAt (long, ms epoch), total (int), exerciseName (string)
WORKOUT_FINISH date ("yyyy-MM-dd"), durationMs (long)
```

Ejemplo de envío desde un plugin nativo de TGym (Java):

```java
Intent i = new Intent("com.spotifree.app.action.TGYM_REST")
    .setPackage("com.spotifree.app")
    .putExtra("type", "REST_START")
    .putExtra("seconds", 90)
    .putExtra("endsAt", System.currentTimeMillis() + 90_000L);
context.sendBroadcast(i);
```

Para que `AppLauncher.canOpenUrl("spotifree://")` funcione en Android 11+, el `AndroidManifest.xml` de TGym debe declarar:

```xml
<queries>
  <intent>
    <action android:name="android.intent.action.VIEW" />
    <data android:scheme="spotifree" />
  </intent>
</queries>
```
