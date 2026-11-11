import { CONFIG } from '../config.js';

export interface RouteBypassInput {
  current_lat?: number;
  current_lng?: number;
  destination: string;
  avoid_points?: string[];
}

export interface TelemetrySnapshot {
  lat: number;
  lng: number;
  speedKmh: number;
  bearing: number;
  updatedAt: number;
}

export async function calculateRouteBypass(
  input: RouteBypassInput,
  latestTelemetry?: TelemetrySnapshot | null
): Promise<Record<string, unknown>> {
  const lat = input.current_lat ?? latestTelemetry?.lat;
  const lng = input.current_lng ?? latestTelemetry?.lng;

  if (lat === undefined || lng === undefined) {
    return {
      success: false,
      error: 'Sin telemetría GPS activa. Verifica que el GPS del casco/móvil esté transmitiendo.',
    };
  }

  if (!CONFIG.routing.googleMapsApiKey) {
    return {
      success: true,
      mode: 'simulation',
      origin: { lat, lng },
      destination: input.destination,
      avoided: input.avoid_points || [],
      tactical_instruction: `Desvío calculado desde (${lat.toFixed(4)}, ${lng.toFixed(4)}) hacia ${input.destination}. Toma la siguiente paralela a la derecha para evadir congestión.`,
      note: 'Configura GOOGLE_MAPS_API_KEY en .env para activar Google Routes API en tiempo real.',
    };
  }

  try {
    const response = await fetch(
      'https://routes.googleapis.com/directions/v2:computeRoutes',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Goog-Api-Key': CONFIG.routing.googleMapsApiKey,
          'X-Goog-FieldMask':
            'routes.duration,routes.distanceMeters,routes.description,routes.legs.steps.navigationInstruction,routes.routeLabels',
        },
        body: JSON.stringify({
          origin: {
            location: {
              latLng: { latitude: lat, longitude: lng },
            },
          },
          destination: {
            address: input.destination,
          },
          travelMode: 'TWO_WHEELER',
          routingPreference: 'TRAFFIC_AWARE_OPTIMAL',
          computeAlternativeRoutes: true,
          languageCode: 'es-419',
          units: 'METRIC',
        }),
      }
    );

    if (!response.ok) {
      const errText = await response.text();
      throw new Error(`Google Routes API HTTP ${response.status}: ${errText}`);
    }

    const data = (await response.json()) as {
      routes?: Array<{
        description?: string;
        distanceMeters?: number;
        duration?: string;
        legs?: Array<{
          steps?: Array<{
            navigationInstruction?: { instructions?: string };
          }>;
        }>;
      }>;
    };

    const routes = data.routes || [];
    if (routes.length === 0) {
      return {
        success: false,
        error: 'No se encontraron rutas alternas disponibles para el destino indicado.',
      };
    }

    // Filter out routes matching avoid_points if specified
    const avoidLower = (input.avoid_points || []).map((p) => p.toLowerCase());
    const chosenRoute =
      routes.find((r) => {
        const desc = (r.description || '').toLowerCase();
        return !avoidLower.some((avoid) => desc.includes(avoid));
      }) || routes[0];

    const firstSteps =
      chosenRoute.legs?.[0]?.steps
        ?.slice(0, 3)
        .map((s) => s.navigationInstruction?.instructions)
        .filter(Boolean) || [];

    const durationSec = parseInt((chosenRoute.duration || '0s').replace('s', ''), 10);
    const durationMin = Math.max(1, Math.round(durationSec / 60));
    const distanceKm = ((chosenRoute.distanceMeters || 0) / 1000).toFixed(1);

    return {
      success: true,
      via: chosenRoute.description || 'Ruta rápida',
      distance_km: distanceKm,
      eta_minutes: durationMin,
      immediate_maneuvers: firstSteps,
      alternatives_count: routes.length,
    };
  } catch (err) {
    console.error('[Tool:Traffic] Google Routes error:', (err as Error).message);
    return {
      success: false,
      error: `Error consultando tráfico en vivo: ${(err as Error).message}`,
    };
  }
}
