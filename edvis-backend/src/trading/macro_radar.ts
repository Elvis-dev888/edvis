/**
 * E.D.V.I.S. Radar Macroeconómico Mundial & Análisis Intermercado
 * Monitorea calendarios económicos de alto impacto, DXY, US10Y y sentimiento geopolítico.
 */

export interface MacroNewsEvent {
  title: string;
  currency: 'USD' | 'EUR' | 'ALL';
  impact: 'HIGH' | 'MEDIUM' | 'LOW';
  scheduledTime: Date;
  actual?: string;
  forecast?: string;
  previous?: string;
}

export interface IntermarketStatus {
  dxyTrend: 'BULLISH' | 'BEARISH' | 'NEUTRAL';
  us10yYield: number; // e.g. 4.45%
  riskSentiment: 'RISK_ON' | 'RISK_OFF' | 'NEUTRAL';
  goldMacroBias: 'BULLISH' | 'BEARISH' | 'NEUTRAL';
  eurUsdBias: 'BULLISH' | 'BEARISH' | 'NEUTRAL';
  lastUpdated: Date;
}

export class MacroRadar {
  private static highImpactCalendar: MacroNewsEvent[] = [];
  private static lastIntermarket: IntermarketStatus = {
    dxyTrend: 'BULLISH',
    us10yYield: 4.38,
    riskSentiment: 'RISK_OFF',
    goldMacroBias: 'BEARISH',
    eurUsdBias: 'BEARISH',
    lastUpdated: new Date()
  };

  /**
   * Verifica si el mercado se encuentra dentro de la ventana de congelamiento de noticias
   * (15 minutos antes o 15 minutos después de una noticia de alto impacto).
   */
  public static isNewsFreezeWindowActive(): { isFrozen: boolean; eventTitle?: string; minutesRemaining?: number } {
    const now = Date.now();
    const freezeWindowMs = 15 * 60 * 1000;

    for (const event of this.highImpactCalendar) {
      if (event.impact === 'HIGH') {
        const eventTime = event.scheduledTime.getTime();
        const diff = eventTime - now;

        if (diff > -freezeWindowMs && diff < freezeWindowMs) {
          const minutesRemaining = Math.round(Math.abs(diff) / 60000);
          return {
            isFrozen: true,
            eventTitle: event.title,
            minutesRemaining
          };
        }
      }
    }

    return { isFrozen: false };
  }

  /**
   * Actualiza las correlaciones intermercado (DXY, US10Y y Sentimiento Global)
   */
  public static updateIntermarket(status: Partial<IntermarketStatus>) {
    this.lastIntermarket = {
      ...this.lastIntermarket,
      ...status,
      lastUpdated: new Date()
    };
  }

  public static getIntermarketStatus(): IntermarketStatus {
    return this.lastIntermarket;
  }

  /**
   * Genera el briefing matutino o de sesión (JARVIS style) para audio TTS.
   */
  public static generateMorningBriefing(): string {
    const status = this.lastIntermarket;
    const freeze = this.isNewsFreezeWindowActive();
    const dxyText = status.dxyTrend === 'BULLISH' 
      ? "presión compradora en el índice del dólar (DXY)" 
      : "debilidad estructural en el dólar";
    const riskText = status.riskSentiment === 'RISK_OFF' 
      ? "aversión al riesgo global (Risk-Off)" 
      : "apetito por el riesgo (Risk-On)";

    let newsWarning = "";
    if (freeze.isFrozen) {
      newsWarning = ` Advertencia táctica: ventana de alta volatilidad activa por ${freeze.eventTitle}. Entradas bloqueadas para evitar deslizamientos.`;
    }

    return `Buenos días, Elvis. Reporte macroeconómico y cuantitativo listo. Detecto ${riskText} con ${dxyText}. Rendimiento de bonos a 10 años en ${status.us10yYield} por ciento. Sesgo para Oro (XAUUSD): ${status.goldMacroBias}. Sesgo para Euro (EURUSD): ${status.eurUsdBias}.${newsWarning} Sistemas de confluencia institucional en vigilancia.`;
  }
}
