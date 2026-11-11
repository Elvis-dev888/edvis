import { createClient, SupabaseClient } from '@supabase/supabase-js';
import pg from 'pg';
import { CONFIG } from '../config.js';

const { Pool } = pg;

let supabaseClient: SupabaseClient | null = null;
let pgPool: pg.Pool | null = null;

export function getSupabaseClient(): SupabaseClient | null {
  if (supabaseClient) return supabaseClient;
  if (CONFIG.supabase.url && CONFIG.supabase.key) {
    supabaseClient = createClient(CONFIG.supabase.url, CONFIG.supabase.key, {
      auth: { persistSession: false },
    });
  }
  return supabaseClient;
}

export function getPgPool(): pg.Pool | null {
  if (pgPool) return pgPool;
  if (CONFIG.supabase.databaseUrl) {
    pgPool = new Pool({
      connectionString: CONFIG.supabase.databaseUrl,
      ssl: CONFIG.supabase.databaseUrl.includes('localhost')
        ? false
        : { rejectUnauthorized: false },
      max: 10,
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 4000,
    });
    pgPool.on('error', (err: Error) => {
      console.error('[Supabase:PG] Pool error:', err.message);
    });
  }
  return pgPool;
}

export async function initializeSupabase(): Promise<void> {
  const sb = getSupabaseClient();

  if (sb) {
    console.log(`[Supabase] Client initialized for URL: ${CONFIG.supabase.url}`);
  } else {
    console.warn(
      '[Supabase] SUPABASE_URL/SUPABASE_KEY not set in .env. Running in local fallback mode until configured.'
    );
  }
}
