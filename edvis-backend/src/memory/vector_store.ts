import { GoogleGenAI } from '@google/genai';
import { CONFIG } from '../config.js';
import { AuthContext } from '../auth/supabase_auth.js';
import { getSupabaseClient, getPgPool } from './supabase.js';

export interface TacticalMemoryItem {
  id?: string | number;
  category: string;
  content: string;
  similarity?: number;
  created_at?: string;
}

const localFallbackMemories: TacticalMemoryItem[] = [];

async function generateEmbedding(text: string): Promise<number[] | null> {
  if (!CONFIG.gemini.apiKey) {
    return null;
  }
  try {
    const ai = new GoogleGenAI({ apiKey: CONFIG.gemini.apiKey });
    const response = await ai.models.embedContent({
      model: CONFIG.gemini.embeddingModel,
      contents: text,
      config: {
        outputDimensionality: 768,
      },
    });
    const values = response.embeddings?.[0]?.values;
    return values ?? null;
  } catch (err) {
    console.error('[VectorStore] Embedding generation failed:', (err as Error).message);
    return null;
  }
}

export async function storeTacticalMemory(
  category: string,
  content: string,
  metadata: Record<string, unknown> = {},
  authContext?: AuthContext
): Promise<{ status: string; id?: string | number; mode: string }> {
  const sb = authContext?.scopedClient || getSupabaseClient();
  const pool = getPgPool();
  const effectiveUserId = authContext?.userId || CONFIG.supabase.defaultUserId;
  const embedding = await generateEmbedding(`${category}: ${content}`);

  // 1. Try Scoped Supabase JS Client (@supabase/supabase-js with RLS)
  if (sb) {
    try {
      const row: Record<string, unknown> = {
        category,
        content,
        metadata,
        ...(embedding ? { embedding } : {}),
      };
      if (effectiveUserId) {
        row.user_id = effectiveUserId;
      }

      const { data, error } = await sb
        .from(CONFIG.supabase.memoryTable)
        .insert(row)
        .select('id')
        .single();

      if (!error && data) {
        return { status: 'stored', id: data.id, mode: 'supabase-js' };
      }
      if (error) {
        console.warn('[VectorStore] Supabase insert warning:', error.message);
      }
    } catch (err) {
      console.error('[VectorStore] Supabase client insert error:', (err as Error).message);
    }
  }

  // 2. Fallback to Direct PostgreSQL Pool (DATABASE_URL)
  if (pool && embedding) {
    try {
      const vectorLiteral = `[${embedding.join(',')}]`;
      const result = await pool.query(
        `INSERT INTO ${CONFIG.supabase.memoryTable} (user_id, category, content, metadata, embedding)
         VALUES ($1, $2, $3, $4, $5::vector)
         RETURNING id`,
        [effectiveUserId, category, content, JSON.stringify(metadata), vectorLiteral]
      );
      return { status: 'stored', id: result.rows[0]?.id, mode: 'supabase-pgvector' };
    } catch (err) {
      console.error('[VectorStore] PG insert error:', (err as Error).message);
    }
  }

  // 3. In-memory fallback when credentials are not yet set
  const id = localFallbackMemories.length + 1;
  localFallbackMemories.push({
    id,
    category,
    content,
    created_at: new Date().toISOString(),
  });

  return {
    status: 'stored',
    id,
    mode: 'in-memory-fallback',
  };
}

export async function recallTacticalMemory(
  query: string,
  limit = 5,
  authContext?: AuthContext
): Promise<TacticalMemoryItem[]> {
  const sb = authContext?.scopedClient || getSupabaseClient();
  const pool = getPgPool();
  const queryEmbedding = await generateEmbedding(query);

  // 1. Try Supabase RPC `match_edvis_memories` (SECURITY INVOKER — filters by auth.uid())
  if (sb && queryEmbedding) {
    try {
      const { data, error } = await sb.rpc('match_edvis_memories', {
        query_embedding: queryEmbedding,
        match_count: limit,
      });

      if (!error && Array.isArray(data)) {
        return data as TacticalMemoryItem[];
      }
    } catch (err) {
      console.warn('[VectorStore] Supabase RPC recall warning:', (err as Error).message);
    }
  }

  // 2. Fallback to Direct PostgreSQL Pool (DATABASE_URL)
  if (pool && queryEmbedding) {
    try {
      const vectorLiteral = `[${queryEmbedding.join(',')}]`;
      const result = await pool.query(
        `SELECT id, category, content,
                1 - (embedding <=> $1::vector) AS similarity,
                created_at
         FROM ${CONFIG.supabase.memoryTable}
         WHERE embedding IS NOT NULL
         ORDER BY embedding <=> $1::vector
         LIMIT $2`,
        [vectorLiteral, limit]
      );
      return result.rows;
    } catch (err) {
      console.error('[VectorStore] PG recall error:', (err as Error).message);
    }
  }

  // 3. Keyword search on Supabase table or in-memory fallback
  if (sb) {
    try {
      const { data } = await sb
        .from(CONFIG.supabase.memoryTable)
        .select('id, category, content, created_at')
        .ilike('content', `%${query}%`)
        .limit(limit);
      if (data && data.length > 0) return data;
    } catch {
      // ignore
    }
  }

  const qLower = query.toLowerCase();
  return localFallbackMemories
    .filter(
      (m) =>
        m.content.toLowerCase().includes(qLower) ||
        m.category.toLowerCase().includes(qLower)
    )
    .slice(0, limit);
}
