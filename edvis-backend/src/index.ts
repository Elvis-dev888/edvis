import fs from 'fs';
import path from 'path';
import http from 'http';
import dns from 'dns';
import { fileURLToPath } from 'url';

dns.setDefaultResultOrder('ipv4first');
import { WebSocketServer, WebSocket } from 'ws';
import { CONFIG } from './config.js';
import {
  verifyRequestAuth,
  signInWithEmail,
  signUpWithEmail,
} from './auth/supabase_auth.js';
import { initializeSupabase, getSupabaseClient, getPgPool } from './memory/supabase.js';
import { EdvisSessionOrchestrator, runStatelessEdvisTurn } from './orchestrator/llm.js';
import { recordFinanceTransaction } from './tools/finance.js';
import { recallTacticalMemory } from './memory/vector_store.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function readJsonBody(req: http.IncomingMessage): Promise<Record<string, any>> {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk.toString();
    });
    req.on('end', () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

function sendJson(
  res: http.ServerResponse,
  statusCode: number,
  payload: Record<string, unknown>
): void {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, x-edvis-token',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  });
  res.end(JSON.stringify(payload));
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'OPTIONS') {
    sendJson(res, 200, { ok: true });
    return;
  }

  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

  // 0. Tactical Web HUD Console (GET /) & PWA Manifest
  if (url.pathname === '/' && req.method === 'GET') {
    const htmlPath = path.resolve(__dirname, '..', 'public', 'index.html');
    if (fs.existsSync(htmlPath)) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(fs.readFileSync(htmlPath, 'utf-8'));
      return;
    }
  }
  if (url.pathname === '/manifest.json' && req.method === 'GET') {
    const manifestPath = path.resolve(__dirname, '..', 'public', 'manifest.json');
    if (fs.existsSync(manifestPath)) {
      res.writeHead(200, { 'Content-Type': 'application/manifest+json; charset=utf-8' });
      res.end(fs.readFileSync(manifestPath, 'utf-8'));
      return;
    }
  }

  // 0.1 Download compiled Android APK (GET /download/edvis.apk)
  if (url.pathname === '/download/edvis.apk' && req.method === 'GET') {
    const apkPath = path.resolve(
      __dirname,
      '..',
      '..',
      'edvis-android',
      'app',
      'build',
      'outputs',
      'apk',
      'debug',
      'app-debug.apk'
    );
    if (fs.existsSync(apkPath)) {
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Disposition': 'attachment; filename="edvis-tactical.apk"',
      });
      fs.createReadStream(apkPath).pipe(res);
      return;
    }
    sendJson(res, 404, {
      error:
        'El APK aún no ha sido compilado. Abre la carpeta edvis-android en Android Studio > Build > Build APK(s) o espera la compilación.',
    });
    return;
  }

  // 1. Public Health Route
  if (url.pathname === '/health' && req.method === 'GET') {
    sendJson(res, 200, {
      system: 'E.D.V.I.S. Tactical Brain Server',
      status: 'ONLINE',
      geminiModel: CONFIG.gemini.liveModel,
      geminiConfigured: Boolean(CONFIG.gemini.apiKey),
      supabaseConnected: Boolean(getSupabaseClient() || getPgPool()),
      financeTable: CONFIG.supabase.financeTable,
      mt5Server: CONFIG.trading.mt5Server,
      timestamp: new Date().toISOString(),
    });
    return;
  }

  // 2. Public Auth Route: POST /auth/login
  if (url.pathname === '/auth/login' && req.method === 'POST') {
    try {
      const body = await readJsonBody(req);
      const result = await signInWithEmail(
        String(body.email || ''),
        String(body.password || '')
      );
      sendJson(res, result.success ? 200 : 401, result);
    } catch (err) {
      sendJson(res, 400, { success: false, error: (err as Error).message });
    }
    return;
  }

  // 3. Public Auth Route: POST /auth/signup
  if (url.pathname === '/auth/signup' && req.method === 'POST') {
    try {
      const body = await readJsonBody(req);
      const result = await signUpWithEmail(
        String(body.email || ''),
        String(body.password || '')
      );
      sendJson(res, result.success ? 200 : 400, result);
    } catch (err) {
      sendJson(res, 400, { success: false, error: (err as Error).message });
    }
    return;
  }

  // ==========================================================================
  // PROTECTED ROUTES (Require valid Supabase JWT via `auth.getUser` or Tactical Token)
  // ==========================================================================
  if (url.pathname.startsWith('/api/')) {
    const authContext = await verifyRequestAuth(req);
    if (!authContext) {
      sendJson(res, 401, {
        error: 'No autorizado. Inicia sesión con Supabase Auth (Bearer JWT).',
      });
      return;
    }

    if (url.pathname === '/api/me' && req.method === 'GET') {
      sendJson(res, 200, {
        authenticated: true,
        userId: authContext.userId,
        email: authContext.email || null,
        mode: authContext.isTacticalToken ? 'tactical-token' : 'supabase-jwt',
      });
      return;
    }

    if (url.pathname === '/api/transactions' && req.method === 'GET') {
      const sb = authContext.scopedClient;
      if (!sb) {
        sendJson(res, 200, { transactions: [], mode: 'unconfigured' });
        return;
      }
      const { data, error } = await sb
        .from(CONFIG.supabase.financeTable)
        .select('*')
        .order('created_at', { ascending: false })
        .limit(50);

      if (error) {
        sendJson(res, 500, { error: error.message });
        return;
      }
      sendJson(res, 200, { transactions: data || [] });
      return;
    }

    if (url.pathname === '/api/transactions' && req.method === 'POST') {
      const body = await readJsonBody(req);
      const result = await recordFinanceTransaction(
        {
          type: body.type === 'ingreso' ? 'ingreso' : 'egreso',
          amount: Number(body.amount || 0),
          currency: body.currency === 'USD' ? 'USD' : 'COP',
          category: String(body.category || 'General'),
          description: body.description ? String(body.description) : undefined,
        },
        authContext
      );
      sendJson(res, result.success ? 201 : 400, result as unknown as Record<string, unknown>);
      return;
    }

    if (url.pathname === '/api/memories' && req.method === 'GET') {
      const q = url.searchParams.get('q') || '';
      const memories = await recallTacticalMemory(q, 5, authContext);
      sendJson(res, 200, { memories });
      return;
    }

    if (url.pathname === '/api/chat' && req.method === 'POST') {
      try {
        const body = await readJsonBody(req);
        const turnResult = await runStatelessEdvisTurn({
          userText: String(body.text || ''),
          history: Array.isArray(body.history) ? body.history : [],
          telemetry: body.telemetry || null,
          authContext,
        });
        sendJson(res, 200, turnResult as unknown as Record<string, unknown>);
      } catch (err) {
        sendJson(res, 500, { error: (err as Error).message });
      }
      return;
    }
  }

  sendJson(res, 404, { error: 'Not Found' });
});

const wss = new WebSocketServer({ server, path: '/ws/edvis' });

wss.on('connection', async (ws: WebSocket, req) => {
  const remoteIp = req.socket.remoteAddress;

  const authContext = await verifyRequestAuth(req);
  if (!authContext) {
    console.warn(`[WS Gateway] Unauthorized connection attempt from ${remoteIp} rejected.`);
    ws.close(4001, 'Unauthorized: Valid Supabase JWT or EDVIS_AUTH_TOKEN required');
    return;
  }

  console.log(
    `[WS Gateway] Operator connected from ${remoteIp} (User: ${
      authContext.email || authContext.userId || 'tactical'
    })`
  );

  const orchestrator = new EdvisSessionOrchestrator(ws, authContext);
  await orchestrator.connectGeminiLive();

  ws.on('message', async (data: Buffer, isBinary: boolean) => {
    try {
      if (isBinary) {
        orchestrator.sendAudioChunk(data.toString('base64'));
        return;
      }

      const packet = JSON.parse(data.toString('utf-8'));

      switch (packet.event) {
        case 'audio_in':
          if (packet.pcm_base64) {
            orchestrator.sendAudioChunk(packet.pcm_base64);
          }
          break;

        case 'audio_stream_end':
          orchestrator.sendAudioStreamEnd();
          break;

        case 'wake_word_detected':
          console.log('[WS Gateway] Wake word ("Edvis") activated on helmet.');
          break;

        case 'gps_telemetry':
          orchestrator.updateTelemetry({
            lat: Number(packet.lat),
            lng: Number(packet.lng),
            speedKmh: Number(packet.speed_kmh || 0),
            bearing: Number(packet.bearing || 0),
            updatedAt: Date.now(),
          });
          break;

        case 'incoming_notification':
          console.log(
            `[WS Gateway] Incoming notification from ${packet.app}: ${packet.sender}`
          );
          orchestrator.sendTextTurn(
            `[NOTIFICACIÓN ENTRANTE DE ${packet.app}] Remitente: "${packet.sender}". Mensaje: "${packet.text}". Resume remitente y mensaje en menos de 10 palabras.`
          );
          break;

        case 'operator_text':
          if (packet.text) {
            orchestrator.sendTextTurn(String(packet.text));
          }
          break;

        case 'direct_tool_test':
          if (packet.tool_name) {
            const result = await orchestrator.executeToolCall(
              packet.tool_name,
              packet.args || {}
            );
            ws.send(
              JSON.stringify({
                event: 'tool_test_result',
                tool_name: packet.tool_name,
                result,
              })
            );
          }
          break;

        default:
          break;
      }
    } catch (err) {
      console.error('[WS Gateway] Packet handling error:', (err as Error).message);
    }
  });

  ws.on('close', () => {
    console.log('[WS Gateway] Android Tactical Client disconnected.');
    orchestrator.close();
  });
});

async function bootstrap() {
  await initializeSupabase();

  server.listen(CONFIG.server.port, CONFIG.server.host, () => {
    console.log('===================================================================');
    console.log('  E.D.V.I.S. (Electronic Direct Vision Intelligence System) ONLINE ');
    console.log(`  Web Console : http://localhost:${CONFIG.server.port}`);
    console.log(`  Gemini Live : ${CONFIG.gemini.liveModel} (Voice: ${CONFIG.gemini.voiceName})`);
    console.log(`  Database    : Supabase Auth + RLS (${CONFIG.supabase.financeTable} + pgvector)`);
    console.log(`  HTTP Health : http://${CONFIG.server.host}:${CONFIG.server.port}/health`);
    console.log(`  WS Endpoint : ws://${CONFIG.server.host}:${CONFIG.server.port}/ws/edvis`);
    console.log('===================================================================');
  });
}

bootstrap();
