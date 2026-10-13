import type {
  AlertDto,
  DeviceDto,
  DeviceType,
  InsightDto,
  ProviderCatalogDto,
  SimulateResponse,
  UsageCoverageDto,
  UsageDto,
  UsageSeriesDto,
  UserDto,
} from "./types";

/**
 * All requests go through the Next.js rewrite at /backend/* so the browser
 * stays same-origin — the gateway has no CORS config and doesn't need one.
 * Override the upstream with NEXT_PUBLIC_API_BASE_URL (server-side env).
 */
const BASE = "/backend/api/v1";

export class ApiError extends Error {
  constructor(
    public status: number,
    public path: string,
    message?: string,
  ) {
    super(message ?? `Request to ${path} failed with status ${status}`);
    this.name = "ApiError";
  }
}

/** True when the failure is a network-level outage (backend down, not HTTP error). */
export function isNetworkError(err: unknown): boolean {
  return err instanceof TypeError || (err instanceof Error && err.message === "Failed to fetch");
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      ...init,
      headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, path, "Network error — is the API gateway running?");
  }
  if (!res.ok) {
    throw new ApiError(res.status, path);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

/* ---------- users ---------- */

export const getDemoUser = () => request<UserDto>("/user/demo");

export const getUser = (userId: number) => request<UserDto>(`/user/${userId}`);

export interface UpdateUserInput {
  name?: string;
  email?: string;
  address?: string;
  alerting?: boolean;
  energyAlertingThreshold?: number;
}

export const updateUser = (userId: number, input: UpdateUserInput) =>
  request<UserDto>(`/user/${userId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });

/* ---------- devices ---------- */

export const getDevicesByUser = (userId: number) =>
  request<DeviceDto[]>(`/device/user/${userId}`);

export const getDevice = (deviceId: number) =>
  request<DeviceDto>(`/device/${deviceId}`);

export interface CreateDeviceInput {
  name: string;
  type: DeviceType;
  location: string;
  userId: number;
}

export const createDevice = (input: CreateDeviceInput) =>
  request<DeviceDto>("/device/create", {
    method: "POST",
    body: JSON.stringify(input),
  });

export const updateDevice = (deviceId: number, input: Omit<CreateDeviceInput, "userId">) =>
  request<DeviceDto>(`/device/${deviceId}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });

export const deleteDevice = (deviceId: number) =>
  request<void>(`/device/${deviceId}`, { method: "DELETE" });

/* ---------- usage ---------- */

export const getUsage = (userId: number, days = 7) =>
  request<UsageDto>(`/usage/${userId}?days=${days}`);

export const getUsageSeries = (userId: number, days = 7, interval: "hour" | "day" = "hour") =>
  request<UsageSeriesDto>(`/usage/${userId}/series?days=${days}&interval=${interval}`);

export const getCoverage = (userId: number) =>
  request<UsageCoverageDto>(`/usage/${userId}/coverage`);

/* ---------- alerts ---------- */

export const getAlerts = (userId: number, limit?: number) =>
  request<AlertDto[]>(
    `/alert/user/${userId}${limit != null ? `?limit=${limit}` : ""}`,
  );

/* ---------- insights ---------- */

export const getProviders = () => request<ProviderCatalogDto>("/insight/providers");

export const getSavingTips = (userId: number, opts?: { provider?: string; model?: string; days?: number }) =>
  request<InsightDto>(`/insight/saving-tips/${userId}${qs(opts)}`);

export const getOverviewInsight = (userId: number, opts?: { provider?: string; model?: string; days?: number }) =>
  request<InsightDto>(`/insight/overview/${userId}${qs(opts)}`);

/** Open an SSE stream of saving-tip chunks. Returns the reader for cleanup. */
export async function streamSavingTips(
  userId: number,
  onChunk: (text: string) => void,
  opts?: { provider?: string; model?: string; days?: number },
): Promise<() => void> {
  const res = await fetch(
    `${BASE}/insight/saving-tips/stream/${userId}${qs(opts)}`,
    { headers: { Accept: "text/event-stream" } },
  );
  if (!res.ok || !res.body) {
    throw new ApiError(res.status, `/insight/saving-tips/stream/${userId}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let cancelled = false;

  (async () => {
    while (!cancelled) {
      const { done, value } = await reader.read();
      if (done) break;
      const text = decoder.decode(value, { stream: true });
      // SSE frames look like: data:{"chunk":"..."} — extract payload chunks
      for (const line of text.split("\n")) {
        if (!line.startsWith("data:")) continue;
        const payload = line.slice(5).trim();
        if (!payload || payload === "[DONE]") continue;
        try {
          const parsed = JSON.parse(payload) as { chunk?: string; tips?: string };
          const chunk = parsed.chunk ?? parsed.tips;
          if (chunk) onChunk(chunk);
        } catch {
          // partial frame; ignore
        }
      }
    }
  })().catch(() => {});

  return () => {
    cancelled = true;
    reader.cancel().catch(() => {});
  };
}

/* ---------- simulation ---------- */

export const simulateDevice = (deviceId: number, mode: "normal" | "high" = "normal") =>
  request<SimulateResponse>(`/ingestion/simulate/device/${deviceId}?mode=${mode}`, {
    method: "POST",
  });

/* ---------- helpers ---------- */

function qs(opts?: Record<string, string | number | undefined>): string {
  if (!opts) return "";
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(opts)) {
    if (v !== undefined && v !== "") params.set(k, String(v));
  }
  const s = params.toString();
  return s ? `?${s}` : "";
}
