export const EDVIS_SYSTEM_PROMPT = `SYSTEM PROMPT: E.D.V.I.S. (Electronic Direct Vision Intelligence System)

You are E.D.V.I.S., the tactical and operative intelligence assistant designed to operate with absolute precision and zero conversational friction.
Your operator interacts with you through an in-helmet Bluetooth audio headset while riding a motorcycle or engaged in field activities.
Always respond in Spanish (Latin America / Colombia) with a tactical, sharp, direct tone unless instructed otherwise.

OPERATIONAL RULES:
1. Tone and Cadence:
   - Analytical, highly efficient, direct, and completely loyal.
   - Strictly prohibit conversational filler (e.g., avoid "Hola", "¿En qué te puedo ayudar hoy?", "Claro, con gusto haré eso").
   - Safety rule: When in transit or responding to road events, deliver information in maximum 2 concise sentences. Be clear, unambiguous, and focused on survival and efficiency.

2. Tool Calling Execution:
   - App Control & Device Actions: You have FULL PERMISSION to open any application on the operator's phone or PC (\`OPEN_APP\`), send/reply WhatsApp messages (\`REPLY_WHATSAPP\`), or place phone calls (\`MAKE_PHONE_CALL\`) using \`dispatch_device_action\`. Whenever the operator says "Abre [App]" (e.g., WhatsApp, YouTube, Instagram, Spotify, Waze, Maps, TikTok, Nequi, Bancolombia, Daviplata, MetaTrader 5, Vantage, Gmail, Telegram, Cámara, Ajustes, etc.), ALWAYS call \`dispatch_device_action\` with \`action_type: "OPEN_APP"\` and \`target\` set to the app name or package, and confirm briefly ("Abriendo [App], señor.").
   - Greeting / Wake Response: If the operator calls your name ("Edvis", "Oye Edvis", "¿Me escuchas?") or greets you, respond immediately and naturally like Iron Man's J.A.R.V.I.S. (e.g., "En línea, señor. Lo escucho fuerte y claro. ¿Cuál es la orden?").
   - Traffic & Routing: When the operator reports blocked traffic or asks for navigation, automatically call \`calculate_route_bypass\` using current GPS telemetry.
   - Finance Logging: When the operator dictates an expense or income, parse amount, currency, category, and notes, and call \`record_finance_transaction\`. Confirm in one sentence.
   - Market Execution: When ordered to execute a trade on Vantage, validate symbol, order type (BUY/SELL), lot size, and stop loss. Require explicit spoken confirmation before final execution unless \`confirmed=true\` has been explicitly confirmed by the operator.
   - Communications: When an incoming notification arrives, summarize sender and message in under 10 words. If the operator orders a reply, dispatch \`dispatch_device_action\` immediately via Accessibility.

3. Continuous Learning:
   - Automatically index behavioral patterns, route bottlenecks, risk tolerances, and personal directives into long-term vector memory using \`store_tactical_memory\` and retrieve context via \`recall_tactical_memory\`.`;

export const EDVIS_TOOL_DECLARATIONS = [
  {
    name: 'record_finance_transaction',
    description:
      'Records an expense (egreso) or income (ingreso) entry into the operator financial database (visionary-frangipane-a8c9fa.netlify.app).',
    behavior: 'NON_BLOCKING',
    parameters: {
      type: 'OBJECT',
      properties: {
        type: {
          type: 'STRING',
          enum: ['egreso', 'ingreso'],
          description: 'Transaction direction: egreso (expense) or ingreso (income)',
        },
        amount: {
          type: 'NUMBER',
          description: 'Numeric value in COP or USD',
        },
        currency: {
          type: 'STRING',
          enum: ['COP', 'USD'],
          description: 'Currency code (defaults to COP)',
        },
        category: {
          type: 'STRING',
          description: 'e.g., Combustible, Comida, Repuestos, Servicios, Trading, Salario',
        },
        description: {
          type: 'STRING',
          description: 'Brief description of the transaction',
        },
      },
      required: ['type', 'amount', 'category'],
    },
  },
  {
    name: 'execute_broker_order',
    description:
      'Executes market orders directly on the Vantage Markets broker account via MetaTrader 5 bridge. Requires explicit operator confirmation.',
    behavior: 'NON_BLOCKING',
    parameters: {
      type: 'OBJECT',
      properties: {
        symbol: {
          type: 'STRING',
          description: 'Financial asset symbol, e.g., EURUSD, NAS100, US30, XAUUSD, BTCUSD',
        },
        action: {
          type: 'STRING',
          enum: ['BUY', 'SELL'],
          description: 'Order direction: BUY or SELL',
        },
        volume: {
          type: 'NUMBER',
          description: 'Lot size (e.g., 0.01, 0.1, 0.5, 1.0)',
        },
        stop_loss: {
          type: 'NUMBER',
          description: 'Price level for Stop Loss (0 if none)',
        },
        take_profit: {
          type: 'NUMBER',
          description: 'Price level for Take Profit (0 if none)',
        },
        confirmed: {
          type: 'BOOLEAN',
          description: 'Must be true only after the operator explicitly confirms the order parameters by voice.',
        },
      },
      required: ['symbol', 'action', 'volume'],
    },
  },
  {
    name: 'calculate_route_bypass',
    description:
      'Computes an alternative low-congestion route or tactical detour given current vehicle GPS telemetry.',
    behavior: 'NON_BLOCKING',
    parameters: {
      type: 'OBJECT',
      properties: {
        current_lat: {
          type: 'NUMBER',
          description: 'Current latitude from GPS telemetry (optional if telemetry is already cached)',
        },
        current_lng: {
          type: 'NUMBER',
          description: 'Current longitude from GPS telemetry (optional if telemetry is already cached)',
        },
        destination: {
          type: 'STRING',
          description: 'Destination address or landmark',
        },
        avoid_points: {
          type: 'ARRAY',
          items: { type: 'STRING' },
          description: 'Streets, intersections, or zones reported congested by the operator',
        },
      },
      required: ['destination'],
    },
  },
  {
    name: 'dispatch_device_action',
    description:
      'Pushes direct execution commands to the Android operating system via WebSocket (WhatsApp reply, phone call, open app).',
    behavior: 'NON_BLOCKING',
    parameters: {
      type: 'OBJECT',
      properties: {
        action_type: {
          type: 'STRING',
          enum: ['REPLY_WHATSAPP', 'MAKE_PHONE_CALL', 'OPEN_APP'],
        },
        target: {
          type: 'STRING',
          description: 'Recipient name, phone number, or Android package name',
        },
        payload: {
          type: 'STRING',
          description: 'Message text to send or additional action arguments',
        },
      },
      required: ['action_type', 'target'],
    },
  },
  {
    name: 'store_tactical_memory',
    description:
      'Stores a persistent rule, habit, route preference, or financial/trading directive in pgvector memory.',
    behavior: 'NON_BLOCKING',
    parameters: {
      type: 'OBJECT',
      properties: {
        category: {
          type: 'STRING',
          enum: ['preference', 'route', 'trading', 'finance', 'contact', 'directive'],
        },
        content: {
          type: 'STRING',
          description: 'Fact or rule to remember permanently',
        },
      },
      required: ['category', 'content'],
    },
  },
  {
    name: 'recall_tactical_memory',
    description:
      'Searches long-term pgvector memory semantically for operator habits, preferences, contacts, or rules.',
    behavior: 'NON_BLOCKING',
    parameters: {
      type: 'OBJECT',
      properties: {
        query: {
          type: 'STRING',
          description: 'Semantic search query',
        },
      },
      required: ['query'],
    },
  },
];
