# E.D.V.I.S. (Electronic Direct Vision Intelligence System)

Implementación completa del ecosistema **E.D.V.I.S.** (Servidor Táctico Node.js/TypeScript con **Gemini 3.8 Live** + **Supabase PostgreSQL / `pgvector`** + Bridge Python MetaTrader 5 + Cliente Nativo Android en Kotlin con Bluetooth SCO, Vosk Wake Word y Accesibilidad).

---

## Estructura del Proyecto

- [`edvis-backend/`](./edvis-backend): Servidor central con WebSockets Duplex (`/ws/edvis`), integración directa con **Gemini 3.8 Live API** (`gemini-3.8-live`, audio-a-audio 16kHz->24kHz de baja latencia + Function Calling asíncrono `NON_BLOCKING`), cliente oficial **Supabase** (`@supabase/supabase-js` + `pg`) para tu app de finanzas (`visionary-frangipane-a8c9fa.netlify.app`) y para la memoria vectorial (`edvis_memories` con `pgvector` + `gemini-embedding-001`), motor de desvío de tráfico en vivo (Google Routes API), y bridge en Python para ejecución de órdenes en **Vantage Markets (MetaTrader 5)**.
- [`edvis-android/`](./edvis-android): Aplicación nativa Android (Kotlin) con `EdvisForegroundService`, enrutamiento exclusivo de audio hacia el casco vía **Bluetooth SCO**, detección local de palabra de activación (`"edvis"`, `"oye edvis"`) con **Vosk**, interceptación y respuesta automática de WhatsApp sin tocar la pantalla (`EdvisNotificationService` + `EdvisAccessibilityService`) y telemetría GPS continua.

---

## Inicio Rápido con tu Nuevo Proyecto de Supabase

### 1. Crear Tablas y `pgvector` en Supabase
Una vez elimines uno de los proyectos antiguos en tu panel de Supabase y crees el nuevo proyecto:
1. Abre el **SQL Editor** en tu dashboard de Supabase.
2. Copia y ejecuta el contenido de [`edvis-backend/sql/supabase_init.sql`](./edvis-backend/sql/supabase_init.sql) (crea la extensión `vector`, la tabla `transactions` para finanzas, la tabla `edvis_memories` y la función RPC `match_edvis_memories`).

### 2. Configurar y Ejecutar `edvis-backend`
1. En tu proyecto de Supabase ve a **Project Settings > API** y copia el `Project URL` y tu clave (`service_role` o `anon`) dentro de [`edvis-backend/.env`](./edvis-backend/.env) (`SUPABASE_URL` y `SUPABASE_KEY`).
2. Agrega también tu `GEMINI_API_KEY`, tus credenciales de `MT5_LOGIN`/`MT5_PASSWORD` y `GOOGLE_MAPS_API_KEY` en [`edvis-backend/.env`](./edvis-backend/.env).
3. Instala las dependencias y levanta el servidor:
```bash
cd edvis-backend
npm install
npm run dev
```

Para habilitar el bridge con **MetaTrader 5 (Vantage Markets)** en Windows:
```bash
pip install -r requirements.txt
```

### 3. Compilar y Configurar `edvis-android`
1. Abre la carpeta `edvis-android` en **Android Studio**.
2. *(Opcional pero recomendado para Wake Word offline)* Descarga [`vosk-model-small-es-0.42.zip`](https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip) y descomprime su contenido en `edvis-android/app/src/main/assets/models/vosk-es/`.
3. Instala la app en tu dispositivo Android, otorga permisos de Notificaciones y Accesibilidad desde los botones del panel táctico, ingresa la IP del servidor (`ws://TU_IP:8080/ws/edvis`) y presiona **INICIAR ENLACE TÁCTICO (CASCO SCO)**.
