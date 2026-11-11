import dotenv from 'dotenv';

dotenv.config();

export const CONFIG = {
  server: {
    port: parseInt(process.env.PORT || '8080', 10),
    host: process.env.HOST || '0.0.0.0',
    authToken: process.env.EDVIS_AUTH_TOKEN || '',
  },
  gemini: {
    apiKey: process.env.GEMINI_API_KEY || '',
    liveModel: process.env.GEMINI_LIVE_MODEL || 'gemini-3.8-live',
    embeddingModel: process.env.GEMINI_EMBEDDING_MODEL || 'gemini-embedding-001',
    voiceName: process.env.GEMINI_VOICE_NAME || 'Fenrir',
  },
  supabase: {
    url: process.env.SUPABASE_URL || '',
    key:
      process.env.SUPABASE_SERVICE_ROLE_KEY ||
      process.env.SUPABASE_KEY ||
      process.env.SUPABASE_ANON_KEY ||
      '',
    databaseUrl: process.env.DATABASE_URL || '',
    financeTable: process.env.FINANCE_TABLE_NAME || 'transactions',
    memoryTable: process.env.MEMORY_TABLE_NAME || 'edvis_memories',
    defaultUserId: process.env.FINANCE_DEFAULT_USER_ID || null,
  },
  trading: {
    pythonExecutable: process.env.PYTHON_EXECUTABLE || 'python',
    mt5Login: parseInt(process.env.MT5_LOGIN || '0', 10),
    mt5Password: process.env.MT5_PASSWORD || '',
    mt5Server: process.env.MT5_SERVER || 'VantageInternational-Demo',
    mt5TerminalPath: process.env.MT5_TERMINAL_PATH || '',
    deviation: parseInt(process.env.MT5_DEVIATION || '20', 10),
    magicNumber: parseInt(process.env.MT5_MAGIC_NUMBER || '990011', 10),
  },
  routing: {
    googleMapsApiKey: process.env.GOOGLE_MAPS_API_KEY || '',
  },
} as const;
