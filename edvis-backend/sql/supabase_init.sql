-- ============================================================================
-- E.D.V.I.S. + FINANZAS - SUPABASE SQL INITIALIZATION (AUTH + STRICT RLS)
-- Ejecuta este script en el SQL Editor de tu proyecto de Supabase
-- ============================================================================

-- 1. Activar extensión pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Tabla de Finanzas (Ingresos y Egresos vinculada a Supabase Auth)
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('egreso', 'ingreso')),
    amount NUMERIC(15, 2) NOT NULL,
    currency TEXT NOT NULL DEFAULT 'COP',
    category TEXT NOT NULL,
    description TEXT,
    source TEXT NOT NULL DEFAULT 'EDVIS_VOICE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.transactions ALTER COLUMN user_id DROP NOT NULL;

-- Índices para rendimiento en RLS y filtros
CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON public.transactions (user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON public.transactions (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_type ON public.transactions (type);

-- Habilitar Row Level Security (RLS)
ALTER TABLE public.transactions ENABLE ROW LEVEL SECURITY;

-- Políticas RLS siguiendo las mejores prácticas de Supabase (TO authenticated + ownership check)
DROP POLICY IF EXISTS "transactions_select_own" ON public.transactions;
CREATE POLICY "transactions_select_own"
    ON public.transactions FOR SELECT
    TO authenticated
    USING (user_id IS NULL OR (SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "transactions_insert_own" ON public.transactions;
CREATE POLICY "transactions_insert_own"
    ON public.transactions FOR INSERT
    TO authenticated
    WITH CHECK (user_id IS NULL OR (SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "transactions_update_own" ON public.transactions;
CREATE POLICY "transactions_update_own"
    ON public.transactions FOR UPDATE
    TO authenticated
    USING (user_id IS NULL OR (SELECT auth.uid()) = user_id)
    WITH CHECK (user_id IS NULL OR (SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "transactions_delete_own" ON public.transactions;
CREATE POLICY "transactions_delete_own"
    ON public.transactions FOR DELETE
    TO authenticated
    USING (user_id IS NULL OR (SELECT auth.uid()) = user_id);

-- 3. Tabla de Memoria Continua Vectorial de E.D.V.I.S. (768 dimensiones para gemini-embedding-001)
CREATE TABLE IF NOT EXISTS public.edvis_memories (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    category TEXT NOT NULL DEFAULT 'general',
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.edvis_memories ALTER COLUMN user_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_edvis_memories_user_id ON public.edvis_memories (user_id);
CREATE INDEX IF NOT EXISTS idx_edvis_memories_embedding_hnsw
    ON public.edvis_memories USING hnsw (embedding vector_cosine_ops);

-- Habilitar RLS en public.edvis_memories
ALTER TABLE public.edvis_memories ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "edvis_memories_select_own" ON public.edvis_memories;
CREATE POLICY "edvis_memories_select_own"
    ON public.edvis_memories FOR SELECT
    TO authenticated
    USING (user_id IS NULL OR (SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "edvis_memories_insert_own" ON public.edvis_memories;
CREATE POLICY "edvis_memories_insert_own"
    ON public.edvis_memories FOR INSERT
    TO authenticated
    WITH CHECK (user_id IS NULL OR (SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "edvis_memories_update_own" ON public.edvis_memories;
CREATE POLICY "edvis_memories_update_own"
    ON public.edvis_memories FOR UPDATE
    TO authenticated
    USING (user_id IS NULL OR (SELECT auth.uid()) = user_id)
    WITH CHECK (user_id IS NULL OR (SELECT auth.uid()) = user_id);

DROP POLICY IF EXISTS "edvis_memories_delete_own" ON public.edvis_memories;
CREATE POLICY "edvis_memories_delete_own"
    ON public.edvis_memories FOR DELETE
    TO authenticated
    USING (user_id IS NULL OR (SELECT auth.uid()) = user_id);

-- 4. Función RPC (SECURITY INVOKER) que respeta automáticamente las políticas RLS del usuario autenticado
CREATE OR REPLACE FUNCTION public.match_edvis_memories(
    query_embedding vector(768),
    match_count INT DEFAULT 5
)
RETURNS TABLE (
    id BIGINT,
    category TEXT,
    content TEXT,
    similarity FLOAT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        m.id,
        m.category,
        m.content,
        1 - (m.embedding <=> query_embedding) AS similarity,
        m.created_at
    FROM public.edvis_memories m
    WHERE m.embedding IS NOT NULL
      AND (m.user_id IS NULL OR m.user_id = (SELECT auth.uid()))
    ORDER BY m.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;

-- Permisos explícitos para roles del Data API de Supabase
GRANT USAGE ON SCHEMA public TO anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.transactions TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.edvis_memories TO authenticated;
GRANT USAGE, SELECT ON SEQUENCE public.edvis_memories_id_seq TO authenticated;
GRANT EXECUTE ON FUNCTION public.match_edvis_memories(vector, INT) TO authenticated;
