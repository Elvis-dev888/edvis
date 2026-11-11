import { GoogleGenAI, Modality } from '@google/genai';
import { WebSocket } from 'ws';
import { CONFIG } from '../config.js';
import { AuthContext } from '../auth/supabase_auth.js';
import { EDVIS_SYSTEM_PROMPT, EDVIS_TOOL_DECLARATIONS } from './prompt.js';
import { recordFinanceTransaction, FinanceTransactionInput } from '../tools/finance.js';
import { calculateRouteBypass, RouteBypassInput, TelemetrySnapshot } from '../tools/traffic.js';
import { dispatchDeviceAction, DeviceActionInput } from '../tools/device.js';
import { executeBrokerOrder, BrokerOrderInput } from '../trading/trader_service.js';
import { storeTacticalMemory, recallTacticalMemory } from '../memory/vector_store.js';

const FLASH_FALLBACK_MODELS = [
  'gemini-3.5-flash-lite',
  'gemini-3.1-flash-lite',
  'gemini-3.8-flash',
  'gemini-2.5-flash',
];

export interface StatelessTurnResult {
  reply: string;
  history: Array<{ role: string; parts: Array<any> }>;
  toolActions: Array<{ name: string; args: Record<string, any>; result: Record<string, unknown> }>;
}

async function generateWithFailoverStatic(
  apiKey: string,
  bodyPayload: Record<string, unknown>
): Promise<any> {
  let lastError = '';
  for (const model of FLASH_FALLBACK_MODELS) {
    const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(bodyPayload),
    });

    if (res.ok) {
      return await res.json();
    }

    lastError = `Model ${model} HTTP ${res.status}: ${await res.text()}`;
    if (res.status === 503 || res.status === 429 || res.status === 404) {
      console.warn(`[Orchestrator] ${model} busy (${res.status}), failing over to next model...`);
      continue;
    }
    throw new Error(lastError);
  }
  throw new Error(lastError);
}

export async function runStatelessEdvisTurn(params: {
  userText: string;
  history?: Array<{ role: string; parts: Array<any> }>;
  telemetry?: TelemetrySnapshot | null;
  authContext: AuthContext;
}): Promise<StatelessTurnResult> {
  const cleanDeclarations = EDVIS_TOOL_DECLARATIONS.map(({ behavior: _b, ...rest }) => rest);
  const history = Array.isArray(params.history) ? [...params.history].slice(-14) : [];
  const toolActions: Array<{
    name: string;
    args: Record<string, any>;
    result: Record<string, unknown>;
  }> = [];

  const telemetryContext = params.telemetry
    ? ` [GPS: ${params.telemetry.lat.toFixed(5)}, ${params.telemetry.lng.toFixed(5)} | ${Math.round(
        params.telemetry.speedKmh
      )} km/h]`
    : '';

  history.push({
    role: 'user',
    parts: [{ text: `${params.userText}${telemetryContext}` }],
  });

  let data: any = await generateWithFailoverStatic(CONFIG.gemini.apiKey, {
    systemInstruction: { parts: [{ text: EDVIS_SYSTEM_PROMPT }] },
    contents: history,
    tools: [{ functionDeclarations: cleanDeclarations }],
    generationConfig: { temperature: 0.2 },
  });

  let candidate = data.candidates?.[0]?.content;
  const parts: Array<any> = candidate?.parts || [];
  const functionCalls = parts.filter((p) => p.functionCall);

  if (functionCalls.length > 0) {
    history.push(candidate);
    const responseParts: Array<any> = [];

    for (const part of functionCalls) {
      const { name, args } = part.functionCall;
      let toolResult: Record<string, unknown>;

      switch (name) {
        case 'record_finance_transaction':
          toolResult = (await recordFinanceTransaction(
            (args || {}) as unknown as FinanceTransactionInput,
            params.authContext
          )) as unknown as Record<string, unknown>;
          break;
        case 'execute_broker_order':
          toolResult = await executeBrokerOrder((args || {}) as unknown as BrokerOrderInput);
          break;
        case 'calculate_route_bypass':
          toolResult = await calculateRouteBypass(
            (args || {}) as unknown as RouteBypassInput,
            params.telemetry || null
          );
          break;
        case 'dispatch_device_action':
          toolResult = {
            dispatched: true,
            action_type: args?.action_type,
            target: args?.target,
            payload: args?.payload || '',
          };
          break;
        case 'store_tactical_memory':
          toolResult = await storeTacticalMemory(
            String(args?.category || 'general'),
            String(args?.content || ''),
            {},
            params.authContext
          );
          break;
        case 'recall_tactical_memory':
          toolResult = {
            memories: await recallTacticalMemory(
              String(args?.query || ''),
              5,
              params.authContext
            ),
          };
          break;
        default:
          toolResult = { error: `Herramienta desconocida: ${name}` };
      }

      toolActions.push({ name, args: args || {}, result: toolResult });
      responseParts.push({
        functionResponse: {
          name,
          response: { result: toolResult },
        },
      });
    }

    history.push({
      role: 'user',
      parts: responseParts,
    });

    data = await generateWithFailoverStatic(CONFIG.gemini.apiKey, {
      systemInstruction: { parts: [{ text: EDVIS_SYSTEM_PROMPT }] },
      contents: history,
      tools: [{ functionDeclarations: cleanDeclarations }],
      generationConfig: { temperature: 0.2 },
    });
    candidate = data.candidates?.[0]?.content;
  }

  const finalParts: Array<any> = candidate?.parts || [];
  const reply =
    finalParts
      .map((p) => p.text)
      .filter(Boolean)
      .join(' ')
      .trim() || 'Orden procesada.';

  history.push({
    role: 'model',
    parts: [{ text: reply }],
  });

  return { reply, history, toolActions };
}

export class EdvisSessionOrchestrator {
  private clientWs: WebSocket;
  private authContext: AuthContext;
  private liveSession: any = null;
  private useHttpOrchestrator = false;
  private latestTelemetry: TelemetrySnapshot | null = null;
  private conversationHistory: Array<{ role: string; parts: Array<any> }> = [];

  constructor(clientWs: WebSocket, authContext: AuthContext) {
    this.clientWs = clientWs;
    this.authContext = authContext;
  }

  public updateTelemetry(telemetry: TelemetrySnapshot): void {
    this.latestTelemetry = telemetry;
  }

  public async connectGeminiLive(): Promise<void> {
    if (!CONFIG.gemini.apiKey) {
      console.warn('[Orchestrator] GEMINI_API_KEY is empty. Live audio bridge awaits key in .env');
      this.sendToAndroid({
        event: 'system_status',
        status: 'degraded',
        message: 'Falta GEMINI_API_KEY en el servidor E.D.V.I.S.',
      });
      return;
    }

    if (CONFIG.gemini.apiKey.startsWith('AQ.')) {
      this.useHttpOrchestrator = true;
      console.log('[Orchestrator] AQ.* key detected -> Multi-Model Tactical Failover Pipeline Active');
      this.sendToAndroid({
        event: 'edvis_ready',
        voice: CONFIG.gemini.voiceName,
        model: FLASH_FALLBACK_MODELS[0],
        user_id: this.authContext.userId,
      });
      return;
    }

    try {
      const ai = new GoogleGenAI({ apiKey: CONFIG.gemini.apiKey });

      this.liveSession = await ai.live.connect({
        model: CONFIG.gemini.liveModel,
        config: {
          responseModalities: [Modality.AUDIO],
          speechConfig: {
            voiceConfig: {
              prebuiltVoiceConfig: {
                voiceName: CONFIG.gemini.voiceName,
              },
            },
          },
          systemInstruction: {
            parts: [{ text: EDVIS_SYSTEM_PROMPT }],
          },
          tools: [
            {
              functionDeclarations: EDVIS_TOOL_DECLARATIONS as any,
            },
          ],
          inputAudioTranscription: {},
          outputAudioTranscription: {},
        },
        callbacks: {
          onopen: () => {
            console.log(
              `[GeminiLive] Connected to ${CONFIG.gemini.liveModel} (Voice: ${CONFIG.gemini.voiceName})`
            );
            this.sendToAndroid({
              event: 'edvis_ready',
              voice: CONFIG.gemini.voiceName,
              model: CONFIG.gemini.liveModel,
              user_id: this.authContext.userId,
            });
          },
          onmessage: async (message: any) => {
            await this.handleGeminiServerMessage(message);
          },
          onerror: (error: any) => {
            console.error('[GeminiLive] Session error:', error?.message || error);
          },
          onclose: (event: any) => {
            console.log('[GeminiLive] Session closed:', event?.reason || '');
            this.liveSession = null;
            this.useHttpOrchestrator = true;
          },
        },
      });
    } catch (err) {
      console.warn('[GeminiLive] Falling back to HTTP Flash pipeline:', (err as Error).message);
      this.useHttpOrchestrator = true;
      this.sendToAndroid({
        event: 'edvis_ready',
        voice: CONFIG.gemini.voiceName,
        model: FLASH_FALLBACK_MODELS[0],
        user_id: this.authContext.userId,
      });
    }
  }

  public sendAudioChunk(pcmBase64: string): void {
    if (!this.liveSession) return;

    this.liveSession.sendRealtimeInput({
      audio: {
        data: pcmBase64,
        mimeType: 'audio/pcm;rate=16000',
      },
    });
  }

  public sendAudioStreamEnd(): void {
    if (!this.liveSession) return;
    this.liveSession.sendRealtimeInput({
      audioStreamEnd: true,
    });
  }

  public async sendTextTurn(text: string): Promise<void> {
    const telemetryContext = this.latestTelemetry
      ? ` [GPS: ${this.latestTelemetry.lat.toFixed(5)}, ${this.latestTelemetry.lng.toFixed(5)} | ${Math.round(
          this.latestTelemetry.speedKmh
        )} km/h]`
      : '';

    const fullInput = `${text}${telemetryContext}`;

    if (this.liveSession && !this.useHttpOrchestrator) {
      this.liveSession.sendClientContent({
        turns: [
          {
            role: 'user',
            parts: [{ text: fullInput }],
          },
        ],
        turnComplete: true,
      });
      return;
    }

    await this.runFlashTurn(fullInput);
  }

  private async generateWithFailover(bodyPayload: Record<string, unknown>): Promise<any> {
    let lastError = '';
    for (const model of FLASH_FALLBACK_MODELS) {
      const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${CONFIG.gemini.apiKey}`;
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(bodyPayload),
      });

      if (res.ok) {
        return await res.json();
      }

      lastError = `Model ${model} HTTP ${res.status}: ${await res.text()}`;
      if (res.status === 503 || res.status === 429 || res.status === 404) {
        console.warn(`[Orchestrator] ${model} busy (${res.status}), failing over to next model...`);
        continue;
      }
      throw new Error(lastError);
    }
    throw new Error(lastError);
  }

  private async runFlashTurn(userText: string): Promise<void> {
    try {
      const cleanDeclarations = EDVIS_TOOL_DECLARATIONS.map(({ behavior: _b, ...rest }) => rest);

      this.conversationHistory.push({
        role: 'user',
        parts: [{ text: userText }],
      });
      if (this.conversationHistory.length > 16) {
        this.conversationHistory = this.conversationHistory.slice(-16);
      }

      let data: any = await this.generateWithFailover({
        systemInstruction: { parts: [{ text: EDVIS_SYSTEM_PROMPT }] },
        contents: this.conversationHistory,
        tools: [{ functionDeclarations: cleanDeclarations }],
        generationConfig: { temperature: 0.2 },
      });

      let candidate = data.candidates?.[0]?.content;
      const parts: Array<any> = candidate?.parts || [];

      const functionCalls = parts.filter((p) => p.functionCall);
      if (functionCalls.length > 0) {
        this.conversationHistory.push(candidate);
        const responseParts: Array<any> = [];

        for (const part of functionCalls) {
          const { name, args } = part.functionCall;
          console.log(`[ToolRouter] Executing ${name}`, args);
          const toolResult = await this.executeToolCall(name, args || {});
          responseParts.push({
            functionResponse: {
              name,
              response: { result: toolResult },
            },
          });
        }

        this.conversationHistory.push({
          role: 'user',
          parts: responseParts,
        });

        data = await this.generateWithFailover({
          systemInstruction: { parts: [{ text: EDVIS_SYSTEM_PROMPT }] },
          contents: this.conversationHistory,
          tools: [{ functionDeclarations: cleanDeclarations }],
          generationConfig: { temperature: 0.2 },
        });
        candidate = data.candidates?.[0]?.content;
      }

      const finalParts: Array<any> = candidate?.parts || [];
      const spokenText = finalParts
        .map((p) => p.text)
        .filter(Boolean)
        .join(' ')
        .trim();

      if (spokenText) {
        this.conversationHistory.push({
          role: 'model',
          parts: [{ text: spokenText }],
        });

        this.sendToAndroid({
          event: 'transcript_out',
          text: spokenText,
          speak: true,
        });
      }

      this.sendToAndroid({
        event: 'turn_complete',
        interaction_status: 'IDLE',
      });
    } catch (err) {
      console.error('[Orchestrator:Flash] Error:', (err as Error).message);
      this.sendToAndroid({
        event: 'system_status',
        status: 'error',
        message: (err as Error).message,
      });
    }
  }

  private async handleGeminiServerMessage(msg: Record<string, any>): Promise<void> {
    const content = msg.serverContent;

    if (content?.modelTurn?.parts) {
      for (const part of content.modelTurn.parts) {
        if (part.inlineData?.data) {
          this.sendToAndroid({
            event: 'audio_out',
            mime_type: part.inlineData.mimeType || 'audio/pcm;rate=24000',
            pcm_base64: part.inlineData.data,
          });
        }
        if (part.text) {
          this.sendToAndroid({
            event: 'transcript_out',
            text: part.text,
          });
        }
      }
    }

    if (content?.inputTranscription?.text) {
      this.sendToAndroid({
        event: 'transcript_in',
        text: content.inputTranscription.text,
      });
    }
    if (content?.outputTranscription?.text) {
      this.sendToAndroid({
        event: 'transcript_out',
        text: content.outputTranscription.text,
      });
    }

    if (content?.interrupted === true) {
      this.sendToAndroid({ event: 'interrupted' });
    }

    if (content?.turnComplete || msg.interactionStatus === 'IDLE') {
      this.sendToAndroid({
        event: 'turn_complete',
        interaction_status: msg.interactionStatus || 'IDLE',
      });
    }

    const functionCalls = msg.toolCall?.functionCalls;
    if (Array.isArray(functionCalls) && functionCalls.length > 0) {
      const functionResponses = [];

      for (const call of functionCalls) {
        const { id, name, args } = call;
        console.log(`[ToolRouter] Executing ${name}`, args);

        const result = await this.executeToolCall(name, args || {});
        functionResponses.push({
          id,
          name,
          response: { result },
        });
      }

      if (this.liveSession) {
        this.liveSession.sendToolResponse({
          functionResponses,
        });
      }
    }
  }

  public async executeToolCall(
    name: string,
    args: Record<string, any>
  ): Promise<Record<string, unknown>> {
    switch (name) {
      case 'record_finance_transaction': {
        const res = await recordFinanceTransaction(
          args as unknown as FinanceTransactionInput,
          this.authContext
        );
        return res as unknown as Record<string, unknown>;
      }

      case 'execute_broker_order': {
        return await executeBrokerOrder(args as unknown as BrokerOrderInput);
      }

      case 'calculate_route_bypass': {
        return await calculateRouteBypass(
          args as unknown as RouteBypassInput,
          this.latestTelemetry
        );
      }

      case 'dispatch_device_action': {
        return dispatchDeviceAction(
          this.clientWs,
          args as unknown as DeviceActionInput
        );
      }

      case 'store_tactical_memory': {
        return await storeTacticalMemory(
          String(args.category || 'general'),
          String(args.content || ''),
          {},
          this.authContext
        );
      }

      case 'recall_tactical_memory': {
        const memories = await recallTacticalMemory(
          String(args.query || ''),
          5,
          this.authContext
        );
        return { memories };
      }

      default:
        return { error: `Herramienta desconocida: ${name}` };
    }
  }

  private sendToAndroid(payload: Record<string, unknown>): void {
    if (this.clientWs.readyState === WebSocket.OPEN) {
      this.clientWs.send(JSON.stringify(payload));
    }
  }

  public close(): void {
    if (this.liveSession) {
      try {
        this.liveSession.close();
      } catch {
        // Ignore close errors
      }
      this.liveSession = null;
    }
  }
}
