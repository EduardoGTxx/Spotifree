# Servidor Jam de Spotifree ("Escuchar juntos")

Servidor mínimo (Node + WebSocket) para que varios teléfonos con Spotifree escuchen la misma música en sincronía.
- El **anfitrión** crea una sala y comparte un código de 5 letras.
- Los **invitados** se unen con ese código y siguen la canción, la pausa y la posición del anfitrión.
- Los invitados pueden **sugerir** canciones; el anfitrión decide si las añade a su cola.

No guarda nada: las salas viven en memoria y desaparecen cuando el anfitrión sale o el servidor se reinicia.

## Probar en local (misma Wi-Fi)

```powershell
cd jam-server
npm install
npm start
```

1. Busca la IP de tu PC con `ipconfig` (por ejemplo, `192.168.1.20`).
2. En la app: Ajustes → Integraciones → Servidor Jam → `ws://192.168.1.20:8080`.
3. Si no conecta, permite Node en el Firewall de Windows (red privada).

## Desplegar gratis en Render

1. En https://render.com crea un **New → Web Service** desde el repositorio:
   - Root Directory: `jam-server`
   - Build Command: `npm install`
   - Start Command: `npm start`
   - Plan: Free.
2. Obtendrás una URL como `https://spotifree-jam.onrender.com`; en la app escribe **`wss://spotifree-jam.onrender.com`**.

En el plan gratuito el servidor se "duerme" tras 15 minutos sin uso: la primera conexión puede tardar unos 50 segundos.

También funciona en Railway, Fly.io o cualquier VPS con Node 18+ (usa la variable `PORT` si el proveedor la define).

## Límites

- Máximo 20 personas por sala. Sin cuentas: cualquiera con el código puede unirse.
- Cada teléfono reproduce el audio por su cuenta, así que puede haber 1–2 s de diferencia; los invitados corrigen la posición si se desvían más de 3 s.
- Si un invitado pierde la conexión, la app vuelve a unirse sola (hasta 5 intentos). Si se cae el anfitrión, la sala se cierra.
