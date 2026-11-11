import http from 'http';
import { createClient, SupabaseClient, User } from '@supabase/supabase-js';
import { CONFIG } from '../config.js';
import { getSupabaseClient } from '../memory/supabase.js';

export interface AuthContext {
  userId: string | null;
  email?: string;
  accessToken?: string;
  isTacticalToken: boolean;
  scopedClient: SupabaseClient | null;
}

/**
 * Creates a request-scoped Supabase client carrying the user's JWT so Postgres RLS
 * (`auth.uid() = user_id`) is enforced on every query.
 */
export function createScopedSupabaseClient(accessToken?: string): SupabaseClient | null {
  if (!CONFIG.supabase.url || !CONFIG.supabase.key) {
    return null;
  }
  if (!accessToken) {
    return getSupabaseClient();
  }
  return createClient(CONFIG.supabase.url, CONFIG.supabase.key, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    },
  });
}

/**
 * Verifies a Bearer JWT or tactical token using `supabase.auth.getUser(token)`
 * (never trusts unverified session payloads, per Supabase Auth security rules).
 */
export async function verifyRequestAuth(
  req: http.IncomingMessage
): Promise<AuthContext | null> {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const authHeader = req.headers['authorization'] || '';
  const bearerToken = authHeader.startsWith('Bearer ')
    ? authHeader.slice(7).trim()
    : '';
  const headerTactical = String(req.headers['x-edvis-token'] || '').trim();
  const queryToken = (url.searchParams.get('token') || '').trim();

  const candidateToken = bearerToken || headerTactical || queryToken;

  // 1. Verify against Supabase Auth (`auth.getUser`) if Supabase is configured
  const sb = getSupabaseClient();
  if (sb && candidateToken && candidateToken !== CONFIG.server.authToken) {
    const { data, error } = await sb.auth.getUser(candidateToken);
    if (!error && data?.user) {
      return {
        userId: data.user.id,
        email: data.user.email,
        accessToken: candidateToken,
        isTacticalToken: false,
        scopedClient: createScopedSupabaseClient(candidateToken),
      };
    }
  }

  // 2. Fallback: Tactical Pre-Shared Token (for local dev or service-role key mode)
  if (
    CONFIG.server.authToken &&
    candidateToken === CONFIG.server.authToken
  ) {
    return {
      userId: CONFIG.supabase.defaultUserId,
      isTacticalToken: true,
      scopedClient: getSupabaseClient(),
    };
  }

  // If no auth token is configured on the server at all, allow local development
  if (!CONFIG.server.authToken && !sb) {
    return {
      userId: CONFIG.supabase.defaultUserId,
      isTacticalToken: true,
      scopedClient: null,
    };
  }

  return null;
}

/**
 * Authenticates an operator with Email & Password via Supabase Auth and returns the JWT access_token.
 */
export async function signInWithEmail(
  email: string,
  password: string
): Promise<{
  success: boolean;
  access_token?: string;
  refresh_token?: string;
  user?: Pick<User, 'id' | 'email'>;
  error?: string;
}> {
  const sb = getSupabaseClient();
  if (!sb) {
    return {
      success: false,
      error: 'SUPABASE_URL y SUPABASE_KEY no están configurados aún en .env',
    };
  }

  const { data, error } = await sb.auth.signInWithPassword({ email, password });
  if (error || !data.session || !data.user) {
    return {
      success: false,
      error: error?.message || 'Credenciales inválidas',
    };
  }

  return {
    success: true,
    access_token: data.session.access_token,
    refresh_token: data.session.refresh_token,
    user: {
      id: data.user.id,
      email: data.user.email,
    },
  };
}

/**
 * Registers a new operator account in Supabase Auth.
 */
export async function signUpWithEmail(
  email: string,
  password: string
): Promise<{
  success: boolean;
  access_token?: string;
  user?: Pick<User, 'id' | 'email'>;
  message?: string;
  error?: string;
}> {
  const sb = getSupabaseClient();
  if (!sb) {
    return {
      success: false,
      error: 'SUPABASE_URL y SUPABASE_KEY no están configurados aún en .env',
    };
  }

  const { data, error } = await sb.auth.signUp({ email, password });
  if (error || !data.user) {
    return {
      success: false,
      error: error?.message || 'No se pudo registrar el usuario',
    };
  }

  return {
    success: true,
    access_token: data.session?.access_token,
    user: {
      id: data.user.id,
      email: data.user.email,
    },
    message: data.session
      ? 'Sesión iniciada correctamente.'
      : 'Usuario creado. Revisa tu correo si tienes confirmación de email activa en Supabase.',
  };
}
