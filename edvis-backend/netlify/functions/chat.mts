import { initializeSupabase, getServiceRoleSupabase } from '../../src/memory/supabase.js';
import { runStatelessEdvisTurn } from '../../src/orchestrator/llm.js';
import { AuthContext } from '../../src/auth/supabase_auth.js';

export default async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization, x-edvis-token',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
      },
    });
  }

  if (req.method !== 'POST') {
    return Response.json({ error: 'Method Not Allowed' }, { status: 405 });
  }

  try {
    await initializeSupabase();
    const body = (await req.json()) as Record<string, any>;

    const authContext: AuthContext = {
      isTacticalToken: true,
      scopedClient: getServiceRoleSupabase(),
    };

    const result = await runStatelessEdvisTurn({
      userText: String(body.text || ''),
      history: Array.isArray(body.history) ? body.history : [],
      telemetry: body.telemetry || null,
      authContext,
    });

    return Response.json(result, { status: 200 });
  } catch (err) {
    return Response.json(
      { error: (err as Error).message || 'Error procesando turno en E.D.V.I.S.' },
      { status: 500 }
    );
  }
};

export const config = {
  path: '/api/chat',
};
