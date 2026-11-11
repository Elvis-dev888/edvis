import { CONFIG } from '../../src/config.js';
import { initializeSupabase, getSupabaseClient, getPgPool } from '../../src/memory/supabase.js';

export default async () => {
  await initializeSupabase();
  return Response.json({
    system: 'E.D.V.I.S. Tactical Brain Server (Netlify Cloud)',
    status: 'ONLINE',
    geminiConfigured: Boolean(CONFIG.gemini.apiKey),
    supabaseConnected: Boolean(getSupabaseClient() || getPgPool()),
    financeTable: CONFIG.supabase.financeTable,
    mt5Server: CONFIG.trading.mt5Server,
    timestamp: new Date().toISOString(),
  });
};

export const config = {
  path: '/health',
};
