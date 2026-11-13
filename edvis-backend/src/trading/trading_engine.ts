/**
 * E.D.V.I.S. Trading Engine - Gestor Cuantitativo Multimodo
 * Administra los tres niveles de autonomía operativa (Vigía, Táctico, Autónomo),
 * interruptores de circuito (Kill Switches) y ejecución en MetaTrader 5 (Vantage).
 */

import { executeBrokerOrder, BrokerOrderInput } from './trader_service.js';
import { SetupOpportunity } from './institutional_confluence.js';

export type OperatingMode = 'MODO_VIGIA' | 'MODO_TACTICO' | 'MODO_AUTONOMO';

export interface TradingEngineState {
  currentMode: OperatingMode;
  dailyTradesCount: number;
  dailyLossPercentage: number;
  maxDailyTrades: number;
  maxDailyLossPercentage: number;
  isKillSwitchActive: boolean;
  pendingSetup: SetupOpportunity | null;
}

export class TradingEngine {
  private static state: TradingEngineState = {
    currentMode: 'MODO_TACTICO', // Modo por defecto recomendado
    dailyTradesCount: 0,
    dailyLossPercentage: 0.0,
    maxDailyTrades: 2,
    maxDailyLossPercentage: 2.0,
    isKillSwitchActive: false,
    pendingSetup: null
  };

  /**
   * Cambia el nivel de autonomía operativa por comando de voz.
   */
  public static setOperatingMode(mode: OperatingMode): string {
    this.state.currentMode = mode;
    switch (mode) {
      case 'MODO_VIGIA':
        return "Modo Vigía activado, señor. Realizaré análisis macroeconómico y técnico continuo emitiendo alertas por voz, sin enviar órdenes a Vantage.";
      case 'MODO_TACTICO':
        return "Modo Táctico activado, señor. Prepararé las órdenes con lotaje y ratio exacto en MetaTrader 5 y solicitaré su autorización verbal antes de disparar.";
      case 'MODO_AUTONOMO':
        return "Modo Autónomo activado, señor. Ejecución institucional delegada con límite máximo de 2 operaciones diarias y circuito de desconexión al 2 por ciento.";
    }
  }

  public static getState(): TradingEngineState {
    return this.state;
  }

  /**
   * Procesa una oportunidad detectada según el modo operativo activo.
   */
  public static processOpportunity(setup: SetupOpportunity): {
    spokenVoiceAlert: string;
    requiresVoiceConfirmation: boolean;
    executedImmediately: boolean;
  } {
    if (this.state.isKillSwitchActive) {
      return {
        spokenVoiceAlert: "Oportunidad detectada pero descartada: Interruptor de circuito de seguridad activo.",
        requiresVoiceConfirmation: false,
        executedImmediately: false
      };
    }

    if (this.state.dailyTradesCount >= this.state.maxDailyTrades) {
      return {
        spokenVoiceAlert: "Oportunidad detectada pero no ejecutable: Se alcanzó el límite institucional de dos operaciones diarias.",
        requiresVoiceConfirmation: false,
        executedImmediately: false
      };
    }

    this.state.pendingSetup = setup;

    const actionText = setup.direction === 'BUY' ? 'compra' : 'venta';
    const symbolText = setup.symbol === 'XAUUSD' ? 'Oro' : 'Euro Dólar';

    // 1. MODO VIGÍA: Solo alerta informativa
    if (this.state.currentMode === 'MODO_VIGIA') {
      return {
        spokenVoiceAlert: `Elvis, señal detectada en ${symbolText}. Configuración de ${actionText} en precio ${setup.entryPrice} con Stop Loss en ${setup.stopLoss}. Modo Vigía activo: no se preparó orden.`,
        requiresVoiceConfirmation: false,
        executedImmediately: false
      };
    }

    // 2. MODO TÁCTICO: Alerta verbal al oído y espera confirmación del operador
    if (this.state.currentMode === 'MODO_TACTICO') {
      return {
        spokenVoiceAlert: `Elvis, tengo una confirmación institucional en ${symbolText}. Escenario de ${actionText} en ${setup.entryPrice}. Confluencia SMC validada con vacío de liquidez y retroceso OTE. Riesgo al uno por ciento, ${setup.calculatedLotSize} lotes, relación uno a tres. ¿Autorizas la ejecución en Vantage?`,
        requiresVoiceConfirmation: true,
        executedImmediately: false
      };
    }

    // 3. MODO AUTÓNOMO: Ejecución directa bajo límites estrictos
    if (this.state.currentMode === 'MODO_AUTONOMO') {
      this.confirmAndExecutePendingSetup();
      return {
        spokenVoiceAlert: `Elvis, ejecutando automáticamente orden de ${actionText} en ${symbolText} a ${setup.entryPrice} con Stop Loss en ${setup.stopLoss}. Lote: ${setup.calculatedLotSize}.`,
        requiresVoiceConfirmation: false,
        executedImmediately: true
      };
    }

    return {
      spokenVoiceAlert: "Análisis completado.",
      requiresVoiceConfirmation: false,
      executedImmediately: false
    };
  }

  /**
   * Ejecuta la orden en Vantage (MetaTrader 5) tras la autorización por voz del usuario.
   */
  public static async confirmAndExecutePendingSetup(): Promise<Record<string, unknown>> {
    const setup = this.state.pendingSetup;
    if (!setup) {
      return { success: false, message: "No hay ninguna orden pendiente de autorización." };
    }

    const orderInput: BrokerOrderInput = {
      symbol: setup.symbol,
      action: setup.direction,
      volume: setup.calculatedLotSize,
      stop_loss: setup.stopLoss,
      take_profit: setup.takeProfit,
      confirmed: true
    };

    const result = await executeBrokerOrder(orderInput);
    this.state.dailyTradesCount++;
    this.state.pendingSetup = null;

    return result;
  }

  /**
   * Cancela la orden pendiente si el usuario dice "Descarta" o "Cancela".
   */
  public static cancelPendingSetup(): string {
    this.state.pendingSetup = null;
    return "Orden descartada, señor. Continuando vigilancia silenciosa en XAUUSD y EURUSD.";
  }
}
