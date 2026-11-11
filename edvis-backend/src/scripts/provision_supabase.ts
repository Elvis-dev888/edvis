import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import dotenv from 'dotenv';

dotenv.config();

const SUPABASE_API = 'https://api.supabase.com/v1';
const DEFAULT_ORG_ID = 'upobfukyiykqsldmvwh';

async function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

async function apiRequest(
  token: string,
  endpoint: string,
  options: RequestInit = {}
): Promise<any> {
  const res = await fetch(`${SUPABASE_API}${endpoint}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(`Supabase API ${res.status} (${endpoint}): ${text}`);
  }
  return text ? JSON.parse(text) : {};
}

async function main() {
  const token = (process.env.SUPABASE_ACCESS_TOKEN || process.argv[2] || '').trim();
  if (!token) {
    console.error(
      '❌ Falta SUPABASE_ACCESS_TOKEN (empieza por sbp_...). Pégalo en edvis-backend/.env o pásalo por argumento.'
    );
    process.exit(1);
  }

  console.log('🔍 Consultando proyectos existentes en tu cuenta de Supabase...');
  const projects: Array<any> = await apiRequest(token, '/projects');

  let targetProject = projects.find(
    (p) =>
      p.name?.toLowerCase().includes('edvis') ||
      p.name?.toLowerCase().includes('finan')
  );

  let dbPassword = process.env.SUPABASE_DB_PASSWORD || '';

  if (!targetProject) {
    const orgs: Array<any> = await apiRequest(token, '/organizations');
    const orgId = orgs[0]?.id || DEFAULT_ORG_ID;
    dbPassword =
      dbPassword || `EdvisTactical_${crypto.randomBytes(6).toString('hex')}!`;

    console.log(`🚀 Creando nuevo proyecto 'edvis-tactical' en la organización ${orgId}...`);
    targetProject = await apiRequest(token, '/projects', {
      method: 'POST',
      body: JSON.stringify({
        name: 'edvis-tactical',
        organization_id: orgId,
        region: 'us-east-1',
        db_pass: dbPassword,
        plan: 'free',
      }),
    });
    console.log(`✅ Proyecto creado con ref: ${targetProject.id}`);
  } else {
    console.log(
      `✅ Usando proyecto existente: ${targetProject.name} (ref: ${targetProject.id})`
    );
  }

  const projectRef = targetProject.id;

  // Wait for project database to be ACTIVE_HEALTHY
  console.log('⏳ Esperando a que la base de datos de Supabase esté ACTIVE_HEALTHY...');
  for (let attempt = 1; attempt <= 30; attempt++) {
    const proj = await apiRequest(token, `/projects/${projectRef}`);
    console.log(`   Estado actual [${attempt}/30]: ${proj.status}`);
    if (proj.status === 'ACTIVE_HEALTHY') {
      break;
    }
    await sleep(5000);
  }

  // Fetch API keys (anon + service_role)
  console.log('🔑 Obteniendo API Keys del proyecto...');
  const apiKeys: Array<{ name: string; api_key: string }> = await apiRequest(
    token,
    `/projects/${projectRef}/api-keys`
  );
  const serviceRoleKey =
    apiKeys.find((k) => k.name === 'service_role')?.api_key || '';
  const anonKey = apiKeys.find((k) => k.name === 'anon')?.api_key || '';
  const chosenKey = serviceRoleKey || anonKey;
  const supabaseUrl = `https://${projectRef}.supabase.co`;

  // Execute SQL initialization script via Management API `/projects/{ref}/database/query`
  const sqlPath = path.resolve(process.cwd(), 'sql', 'supabase_init.sql');
  const sqlContent = fs.readFileSync(sqlPath, 'utf-8');

  console.log('🛠️ Ejecutando sql/supabase_init.sql (pgvector + transactions + edvis_memories + RLS)...');
  await apiRequest(token, `/projects/${projectRef}/database/query`, {
    method: 'POST',
    body: JSON.stringify({ query: sqlContent }),
  });
  console.log('✅ Esquema SQL y políticas RLS creados exitosamente.');

  // Update .env automatically
  const envPath = path.resolve(process.cwd(), '.env');
  if (fs.existsSync(envPath)) {
    let envText = fs.readFileSync(envPath, 'utf-8');
    envText = envText.replace(/^SUPABASE_URL=.*$/m, `SUPABASE_URL=${supabaseUrl}`);
    envText = envText.replace(/^SUPABASE_KEY=.*$/m, `SUPABASE_KEY=${chosenKey}`);
    if (dbPassword) {
      const dbUri = `postgresql://postgres:${encodeURIComponent(
        dbPassword
      )}@db.${projectRef}.supabase.co:5432/postgres`;
      envText = envText.replace(/^DATABASE_URL=.*$/m, `DATABASE_URL=${dbUri}`);
    }
    fs.writeFileSync(envPath, envText, 'utf-8');
    console.log(`✅ Archivo .env actualizado con SUPABASE_URL=${supabaseUrl}`);
  }

  console.log('🎉 ¡Aprovisionamiento de Supabase completado!');
}

main().catch((err) => {
  console.error('❌ Error aprovisionando Supabase:', err.message);
  process.exit(1);
});
