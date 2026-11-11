import { CONFIG } from '../../src/config.js';
import { initializeSupabase, getServiceRoleSupabase } from '../../src/memory/supabase.js';
import { recordFinanceTransaction } from '../../src/tools/finance.js';
import { AuthContext } from '../../src/auth/supabase_auth.js';

export default async (req: Request) => {
  await initializeSupabase();
  const sb = getServiceRoleSupabase();

  const authContext: AuthContext = {
    isTacticalToken: true,
    scopedClient: sb,
  };

  if (req.method === 'GET') {
    if (!sb) {
      return Response.json({ transactions: [], mode: 'unconfigured' }, { status: 200 });
    }
    const { data, error } = await sb
      .from(CONFIG.supabase.financeTable)
      .select('*')
      .order('created_at', { ascending: false })
      .limit(50);

    if (error) {
      return Response.json({ error: error.message }, { status: 500 });
    }
    return Response.json({ transactions: data || [] }, { status: 200 });
  }

  if (req.method === 'POST') {
    const body = (await req.json()) as Record<string, any>;
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
    return Response.json(result, { status: result.success ? 201 : 400 });
  }

  return Response.json({ error: 'Method Not Allowed' }, { status: 405 });
};

export const config = {
  path: '/api/transactions',
};
