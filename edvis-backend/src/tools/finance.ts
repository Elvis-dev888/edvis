import { CONFIG } from '../config.js';
import { AuthContext } from '../auth/supabase_auth.js';
import { getSupabaseClient, getPgPool } from '../memory/supabase.js';

export interface FinanceTransactionInput {
  type: 'egreso' | 'ingreso';
  amount: number;
  currency?: 'COP' | 'USD';
  category: string;
  description?: string;
}

export interface FinanceTransactionResult {
  success: boolean;
  transaction_id?: string;
  recorded: FinanceTransactionInput;
  message: string;
}

/**
 * Records an expense (egreso) or income (ingreso) directly into the Supabase PostgreSQL
 * database with RLS ownership (`user_id = auth.uid()`).
 */
export async function recordFinanceTransaction(
  input: FinanceTransactionInput,
  authContext?: AuthContext
): Promise<FinanceTransactionResult> {
  const sb = authContext?.scopedClient || getSupabaseClient();
  const pool = getPgPool();
  const currency = input.currency || 'COP';
  const description =
    input.description || `${input.type.toUpperCase()} - ${input.category}`;
  const tableName = CONFIG.supabase.financeTable;
  const effectiveUserId = authContext?.userId || CONFIG.supabase.defaultUserId;

  // 1. Primary path: Scoped Supabase JS Client (enforces RLS)
  if (sb) {
    try {
      const row: Record<string, unknown> = {
        type: input.type,
        amount: Number(input.amount),
        currency,
        category: input.category,
        description,
        source: 'EDVIS_VOICE',
      };
      if (effectiveUserId) {
        row.user_id = effectiveUserId;
      }

      const { data, error } = await sb
        .from(tableName)
        .insert(row)
        .select('id')
        .single();

      if (error) {
        throw new Error(error.message);
      }

      return {
        success: true,
        transaction_id: String(data?.id),
        recorded: { ...input, currency, description },
        message: `${input.type.toUpperCase()} de ${input.amount} ${currency} registrado en Supabase (${tableName}) bajo categoría ${input.category}.`,
      };
    } catch (err) {
      console.error('[Tool:Finance] Supabase insert error:', (err as Error).message);
      return {
        success: false,
        recorded: input,
        message: `Error al escribir en Supabase (${tableName}): ${(err as Error).message}`,
      };
    }
  }

  // 2. Secondary path: Direct PostgreSQL Pool (DATABASE_URL)
  if (pool) {
    try {
      const res = await pool.query(
        `INSERT INTO ${tableName} (user_id, type, amount, currency, category, description, source)
         VALUES ($1, $2, $3, $4, $5, $6, 'EDVIS_VOICE')
         RETURNING id`,
        [
          effectiveUserId,
          input.type,
          Number(input.amount),
          currency,
          input.category,
          description,
        ]
      );
      return {
        success: true,
        transaction_id: String(res.rows[0]?.id),
        recorded: { ...input, currency, description },
        message: `${input.type.toUpperCase()} de ${input.amount} ${currency} registrado en Supabase PostgreSQL bajo categoría ${input.category}.`,
      };
    } catch (err) {
      console.error('[Tool:Finance] PG Pool write error:', (err as Error).message);
      return {
        success: false,
        recorded: input,
        message: `Error al escribir en base de datos Supabase: ${(err as Error).message}`,
      };
    }
  }

  // 3. Simulation fallback before credentials are pasted in .env
  console.warn(
    '[Tool:Finance] Supabase credentials not set yet. Simulating transaction write.'
  );
  return {
    success: true,
    transaction_id: `sim-sb-${Date.now()}`,
    recorded: { ...input, currency, description },
    message: `Transacción registrada en modo simulación (${input.type}: ${input.amount} ${currency} en ${input.category}). Configura SUPABASE_URL y SUPABASE_KEY en .env para guardarla en tu nuevo proyecto de Supabase.`,
  };
}
