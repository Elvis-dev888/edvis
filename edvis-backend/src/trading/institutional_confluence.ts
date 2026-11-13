/**
 * E.D.V.I.S. Motor de Confluencia Técnica e Institucional (SMC)
 * Especializado en XAUUSD (Oro) y EURUSD para MetaTrader 5 (Vantage).
 */

import { MacroRadar } from './macro_radar.js';

export interface SetupOpportunity {
  symbol: 'XAUUSD' | 'EURUSD';
  direction: 'BUY' | 'SELL';
  entryPrice: number;
  stopLoss: number;
  takeProfit: number;
  riskRewardRatio: number;
  calculatedLotSize: number;
  confluences: string[];
  timestamp: Date;
}

export class InstitutionalConfluenceEngine {
  /**
   * Calcula el lotaje exacto respetando el porcentaje de riesgo y la distancia al Stop Loss.
   */
  public static calculatePositionSize(
    accountBalance: number,
    riskPercentage: number,
    entryPrice: number,
    stopLossPrice: number,
    symbol: 'XAUUSD' | 'EURUSD'
  ): number {
    const riskAmount = accountBalance * (riskPercentage / 100);
    const stopDistance = Math.abs(entryPrice - stopLossPrice);

    if (stopDistance <= 0) return 0.01;

    let lotSize = 0.01;

    if (symbol === 'XAUUSD') {
      // En XAUUSD, 1 lote = 100 oz. 1 dólar de movimiento = $100 por lote.
      lotSize = riskAmount / (stopDistance * 100);
    } else {
      // En EURUSD, 1 lote estándar = 100,000 unidades. $10 por pip (0.0001).
      const pips = stopDistance / 0.0001;
      lotSize = riskAmount / (pips * 10);
    }

    // Limitar entre 0.01 y 10.00 lotes, redondeado a 2 decimales
    const bounded = Math.max(0.01, Math.min(10.0, Math.floor(lotSize * 100) / 100));
    return bounded;
  }

  /**
   * Evalúa la confluencia institucional de 4 capas:
   * 1. Dirección Macro (DXY y correlaciones)
   * 2. Barrida de Liquidez (Sweeps en sesiones previas)
   * 3. Ineficiencia y Bloques (FVG + Order Block institucional)
   * 4. Retroceso Fibonacci OTE (61.8% - 79%)
   */
  public static evaluateMarketSetup(
    symbol: 'XAUUSD' | 'EURUSD',
    currentPrice: number,
    sessionSweepDetected: boolean,
    fvgOrderBlockConfirmed: boolean,
    fibonacciOteLevel: number, // 0.618 - 0.79
    accountBalance: number = 10000,
    riskPercentage: number = 1.0
  ): SetupOpportunity | null {
    // 0. Filtro Anti-Slippage de Noticias de Alto Impacto
    const freeze = MacroRadar.isNewsFreezeWindowActive();
    if (freeze.isFrozen) {
      return null; // Bloqueo preventivo
    }

    const macro = MacroRadar.getIntermarketStatus();

    // 1. Filtrado de Dirección Macro
    let direction: 'BUY' | 'SELL' | null = null;
    if (symbol === 'EURUSD') {
      if (macro.dxyTrend === 'BULLISH') direction = 'SELL';
      else if (macro.dxyTrend === 'BEARISH') direction = 'BUY';
    } else if (symbol === 'XAUUSD') {
      if (macro.riskSentiment === 'RISK_OFF' && macro.dxyTrend === 'BEARISH') direction = 'BUY';
      else if (macro.dxyTrend === 'BULLISH' && macro.us10yYield > 4.2) direction = 'SELL';
    }

    if (!direction) return null;

    // 2. Confluencia de Smart Money (Barrida + FVG + OTE)
    const isOteValid = fibonacciOteLevel >= 0.618 && fibonacciOteLevel <= 0.79;
    if (!sessionSweepDetected || !fvgOrderBlockConfirmed || !isOteValid) {
      return null;
    }

    // 3. Estructuración de SL y TP (R:R mínimo 1:3)
    let stopLoss = 0;
    let takeProfit = 0;

    if (symbol === 'XAUUSD') {
      const slPips = 5.0; // $5 de Stop Loss en Oro
      const tpPips = 15.0; // $15 de Take Profit (1:3)
      if (direction === 'SELL') {
        stopLoss = currentPrice + slPips;
        takeProfit = currentPrice - tpPips;
      } else {
        stopLoss = currentPrice - slPips;
        takeProfit = currentPrice + tpPips;
      }
    } else {
      const slPips = 0.0020; // 20 pips
      const tpPips = 0.0060; // 60 pips (1:3)
      if (direction === 'SELL') {
        stopLoss = currentPrice + slPips;
        takeProfit = currentPrice - tpPips;
      } else {
        stopLoss = currentPrice - slPips;
        takeProfit = currentPrice + tpPips;
      }
    }

    const calculatedLotSize = this.calculatePositionSize(
      accountBalance,
      riskPercentage,
      currentPrice,
      stopLoss,
      symbol
    );

    return {
      symbol,
      direction,
      entryPrice: currentPrice,
      stopLoss: Math.round(stopLoss * 100) / 100,
      takeProfit: Math.round(takeProfit * 100) / 100,
      riskRewardRatio: 3.0,
      calculatedLotSize,
      confluences: [
        `Dirección Macro: ${direction} avalado por DXY ${macro.dxyTrend}`,
        'Barrida de liquidez de sesión completada',
        'Imbalance FVG rellenado en Order Block institucional',
        `Entrada OTE en ratio Fibonacci ${(fibonacciOteLevel * 100).toFixed(1)}%`
      ],
      timestamp: new Date()
    };
  }
}
