import { spawn } from 'child_process';
import path from 'path';
import { fileURLToPath } from 'url';
import { CONFIG } from '../config.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export interface BrokerOrderInput {
  symbol: string;
  action: 'BUY' | 'SELL';
  volume: number;
  stop_loss?: number;
  take_profit?: number;
  confirmed?: boolean;
}

export async function executeBrokerOrder(
  order: BrokerOrderInput
): Promise<Record<string, unknown>> {
  if (!order.confirmed) {
    return {
      status: 'AWAITING_CONFIRMATION',
      executed: false,
      message: `Confirmación requerida antes de ejecutar en Vantage: ${order.action} ${order.volume} lotes en ${order.symbol}${
        order.stop_loss ? ` con Stop Loss en ${order.stop_loss}` : ''
      }. Solicita confirmación verbal al operador.`,
    };
  }

  const bridgeScriptPath = path.resolve(__dirname, 'bridge.py');

  return new Promise((resolve) => {
    const child = spawn(
      CONFIG.trading.pythonExecutable,
      [bridgeScriptPath, JSON.stringify(order)],
      {
        env: {
          ...process.env,
          MT5_LOGIN: String(CONFIG.trading.mt5Login),
          MT5_PASSWORD: CONFIG.trading.mt5Password,
          MT5_SERVER: CONFIG.trading.mt5Server,
          MT5_TERMINAL_PATH: CONFIG.trading.mt5TerminalPath,
          MT5_DEVIATION: String(CONFIG.trading.deviation),
          MT5_MAGIC_NUMBER: String(CONFIG.trading.magicNumber),
        },
      }
    );

    let stdoutData = '';
    let stderrData = '';

    child.stdout.on('data', (chunk) => {
      stdoutData += chunk.toString();
    });

    child.stderr.on('data', (chunk) => {
      stderrData += chunk.toString();
    });

    child.on('error', (err) => {
      resolve({
        success: false,
        error: `No se pudo iniciar el bridge Python MT5: ${err.message}`,
      });
    });

    child.on('close', () => {
      try {
        const parsed = JSON.parse(stdoutData.trim());
        resolve(parsed);
      } catch {
        resolve({
          success: false,
          error: stderrData.trim() || stdoutData.trim() || 'Error desconocido en bridge.py',
        });
      }
    });
  });
}
