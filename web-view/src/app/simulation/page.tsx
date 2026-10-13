"use client";

import { useState } from "react";
import {
  Activity,
  Gauge,
  Play,
  Radio,
  Save,
  TrendingUp,
  WifiOff,
} from "lucide-react";
import { clsx } from "clsx";
import { PageHeader } from "@/components/page-header";
import { useSession } from "@/components/session";
import {
  AlertBanner,
  Badge,
  Button,
  Card,
  EmptyState,
  Overline,
  Skeleton,
} from "@/components/ui";
import { getDevicesByUser, simulateDevice, updateUser } from "@/lib/api";
import { useAsync } from "@/lib/use-async";
import { deviceMeta } from "@/lib/device-meta";
import { kwh } from "@/lib/format";
import type { DeviceDto } from "@/lib/types";

interface PipelineEntry {
  id: number;
  deviceId: number;
  deviceName: string;
  mode: "normal" | "high";
  at: string;
  ok: boolean;
  detail: string;
}

export default function SimulationPage() {
  const { user, status, error, refresh, patchUser } = useSession();
  const userId = user?.userId;
  const devices = useAsync<DeviceDto[]>(() => getDevicesByUser(userId!), [userId], {
    enabled: userId != null,
  });

  const [thresholdInput, setThresholdInput] = useState<string | null>(null);
  const [alertingInput, setAlertingInput] = useState<boolean | null>(null);
  const [savingSettings, setSavingSettings] = useState(false);
  const [settingsError, setSettingsError] = useState<string | null>(null);
  const [settingsSaved, setSettingsSaved] = useState(false);

  const [selectedDevice, setSelectedDevice] = useState<number | null>(null);
  const [dispatching, setDispatching] = useState<"normal" | "high" | null>(null);
  const [dispatchError, setDispatchError] = useState<string | null>(null);
  const [log, setLog] = useState<PipelineEntry[]>([]);

  if (status === "loading") return <SimulationSkeleton />;

  if (status !== "ready" || !user) {
    return (
      <>
        <PageHeader title="Simulation" description="Thresholds and synthetic telemetry" />
        <EmptyState
          icon={<WifiOff size={22} />}
          title={status === "missing" ? "Demo session not initialized" : "API gateway unreachable"}
          description={error ?? "Start the backend stack, then retry."}
          actions={<Button variant="primary" onClick={() => void refresh()}>Retry</Button>}
        />
      </>
    );
  }

  const threshold = thresholdInput != null ? Number(thresholdInput) : user.energyAlertingThreshold;
  const alerting = alertingInput ?? user.alerting;
  const thresholdDirty =
    thresholdInput != null && Number(thresholdInput) !== user.energyAlertingThreshold;
  const alertingDirty = alertingInput != null && alertingInput !== user.alerting;
  const selected = (devices.data ?? []).find((d) => d.deviceId === selectedDevice) ?? null;

  async function saveSettings(e: React.FormEvent) {
    e.preventDefault();
    const current = user;
    if (current == null || userId == null) return;
    setSavingSettings(true);
    setSettingsError(null);
    setSettingsSaved(false);
    try {
      const updated = await updateUser(userId, {
        energyAlertingThreshold: Number(thresholdInput ?? current.energyAlertingThreshold),
        alerting: alertingInput ?? current.alerting,
      });
      patchUser(updated);
      setThresholdInput(null);
      setAlertingInput(null);
      setSettingsSaved(true);
      setTimeout(() => setSettingsSaved(false), 2500);
    } catch (err) {
      setSettingsError(err instanceof Error ? err.message : "Failed to update settings.");
    } finally {
      setSavingSettings(false);
    }
  }

  async function dispatch(mode: "normal" | "high") {
    if (!selected) return;
    setDispatching(mode);
    setDispatchError(null);
    const entry: PipelineEntry = {
      id: Date.now(),
      deviceId: selected.deviceId,
      deviceName: selected.name,
      mode,
      at: new Date().toISOString(),
      ok: false,
      detail: "queued…",
    };
    setLog((prev) => [entry, ...prev].slice(0, 12));
    try {
      const res = await simulateDevice(selected.deviceId, mode);
      setLog((prev) =>
        prev.map((l) =>
          l.id === entry.id
            ? {
                ...l,
                ok: true,
                detail:
                  res.message ??
                  `${res.readings ?? (mode === "high" ? 5 : 10)} readings dispatched (${mode})`,
              }
            : l,
        ),
      );
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Dispatch failed.";
      setDispatchError(msg);
      setLog((prev) =>
        prev.map((l) => (l.id === entry.id ? { ...l, ok: false, detail: msg } : l)),
      );
    } finally {
      setDispatching(null);
    }
  }

  return (
    <>
      <PageHeader
        title="Simulation"
        description="Tune the system-wide threshold and push synthetic readings through Kafka"
      />

      <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-12">
        {/* Threshold settings */}
        <Card className="lg:col-span-5">
          <div className="mb-4 flex items-center gap-2">
            <Gauge size={16} className="text-warning-text" />
            <h2 className="text-[15px] font-semibold text-text">System threshold</h2>
          </div>

          <p className="mb-4 text-[12.5px] leading-relaxed text-text-secondary">
            A single ceiling evaluated against aggregate device load. When the sum
            crosses this value, the alert service emits a violation event.
          </p>

          {settingsError && (
            <div className="mb-3">
              <AlertBanner title="Update failed" description={settingsError} onDismiss={() => setSettingsError(null)} />
            </div>
          )}
          {settingsSaved && (
            <div className="mb-3">
              <AlertBanner tone="warning" title="Threshold updated" description="New limit is live for the next evaluation cycle." />
            </div>
          )}

          <form onSubmit={saveSettings} className="flex flex-col gap-4">
            <label className="flex flex-col gap-1.5">
              <Overline>Threshold (kWh per evaluation window)</Overline>
              <input
                type="number"
                step="0.1"
                min="0.1"
                value={threshold}
                onChange={(e) => setThresholdInput(e.target.value)}
                className="h-10 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 font-mono text-[15px] tabular text-text focus:border-accent focus:outline-none"
              />
            </label>

            <div className="flex items-center justify-between rounded-[var(--radius)] bg-surface-1 px-3.5 py-3">
              <div>
                <div className="text-[13px] font-medium text-text">Edge-triggered alerting</div>
                <div className="text-[11.5px] text-text-muted">
                  Email a notification on each crossing
                </div>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={alerting}
                onClick={() => setAlertingInput(!alerting)}
                className={clsx(
                  "relative h-5 w-9 rounded-full border transition-colors",
                  alerting
                    ? "border-[rgba(16,185,129,0.4)] bg-accent"
                    : "border-border bg-surface-0",
                )}
              >
                <span
                  className={clsx(
                    "absolute top-[2px] h-3.5 w-3.5 rounded-full transition-all",
                    alerting ? "left-[18px] bg-[#06281c]" : "left-[2px] bg-text-muted",
                  )}
                />
              </button>
            </div>

            <div className="flex items-center justify-between">
              <span className="font-mono text-[11px] text-text-muted">
                PUT /user/{user.userId}
              </span>
              <Button
                type="submit"
                variant="primary"
                size="sm"
                disabled={savingSettings || (!thresholdDirty && !alertingDirty)}
              >
                <Save size={13} />
                {savingSettings ? "Saving…" : "Save settings"}
              </Button>
            </div>
          </form>
        </Card>

        {/* Dispatch panel */}
        <div className="flex flex-col gap-5 lg:col-span-7">
          <Card>
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Radio size={16} className="text-accent-text" />
                <h2 className="text-[15px] font-semibold text-text">Dispatch synthetic telemetry</h2>
              </div>
              <Badge tone={dispatching ? "accent" : "neutral"} dot={!!dispatching}>
                {dispatching ? "pipeline active" : "idle"}
              </Badge>
            </div>

            {dispatchError && (
              <div className="mb-3">
                <AlertBanner
                  title="Ingestion rejected"
                  description={dispatchError}
                  onDismiss={() => setDispatchError(null)}
                />
              </div>
            )}

            <label className="mb-3 flex flex-col gap-1.5">
              <Overline>Target device</Overline>
              <select
                value={selectedDevice ?? ""}
                onChange={(e) => setSelectedDevice(Number(e.target.value) || null)}
                className="h-9 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 text-[13px] text-text focus:border-accent focus:outline-none"
              >
                <option value="">Select a device…</option>
                {(devices.data ?? []).map((d) => (
                  <option key={d.deviceId} value={d.deviceId}>
                    #{d.deviceId} · {d.name} ({d.location})
                  </option>
                ))}
              </select>
            </label>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <button
                onClick={() => void dispatch("normal")}
                disabled={!selected || dispatching != null}
                className="panel-raised group flex flex-col items-start gap-1.5 p-4 text-left transition-colors hover:border-border-strong disabled:cursor-not-allowed disabled:opacity-50"
              >
                <div className="flex w-full items-center justify-between">
                  <span className="flex h-8 w-8 items-center justify-center rounded-[var(--radius-sm)] border border-[rgba(16,185,129,0.25)] bg-accent-soft text-accent-text">
                    <Activity size={14} />
                  </span>
                  <Play size={13} className="text-text-muted transition-colors group-hover:text-accent-text" />
                </div>
                <div className="mt-1 text-[13px] font-semibold text-text">Normal usage</div>
                <p className="text-[11.5px] leading-snug text-text-secondary">
                  10 readings · 0.5–2.0 kWh · stays under the limit
                </p>
              </button>

              <button
                onClick={() => void dispatch("high")}
                disabled={!selected || dispatching != null}
                className="panel-raised group flex flex-col items-start gap-1.5 p-4 text-left transition-colors hover:border-[rgba(244,63,94,0.4)] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <div className="flex w-full items-center justify-between">
                  <span className="flex h-8 w-8 items-center justify-center rounded-[var(--radius-sm)] border border-[rgba(244,63,94,0.28)] bg-critical-soft text-critical-text">
                    <TrendingUp size={14} />
                  </span>
                  <Play size={13} className="text-text-muted transition-colors group-hover:text-critical-text" />
                </div>
                <div className="mt-1 text-[13px] font-semibold text-text">High spike</div>
                <p className="text-[11.5px] leading-snug text-text-secondary">
                  5 readings · 100–150 kWh · deterministic violation
                </p>
              </button>
            </div>

            {devices.loading && (
              <div className="mt-3">
                <Skeleton className="h-9 w-full" />
              </div>
            )}
            {!devices.loading && (devices.data ?? []).length === 0 && (
              <p className="mt-3 text-[12px] text-text-muted">
                No devices available — register one on the Devices page first.
              </p>
            )}
          </Card>

          {/* Pipeline lifecycle log */}
          <Card>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-[14px] font-semibold text-text">Pipeline activity</h2>
              <span className="font-mono text-[10.5px] text-text-muted">kafka → usage service</span>
            </div>
            {log.length === 0 ? (
              <p className="py-5 text-center text-[12px] text-text-muted">
                No dispatches this session. Pick a device and push readings.
              </p>
            ) : (
              <ul className="flex flex-col divide-y divide-border">
                {log.map((entry) => {
                  const meta = deviceMeta(
                    (devices.data ?? []).find((d) => d.deviceId === entry.deviceId)?.type ?? "LIGHT",
                  );
                  return (
                    <li key={entry.id} className="flex items-center gap-3 py-2.5">
                      <span
                        className={clsx(
                          "h-1.5 w-1.5 shrink-0 rounded-full",
                          entry.ok ? "bg-accent" : "bg-critical",
                        )}
                      />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate text-[12.5px] font-medium text-text">
                            #{entry.deviceId} {entry.deviceName}
                          </span>
                          <Badge tone={entry.mode === "high" ? "critical" : "accent"}>
                            {entry.mode}
                          </Badge>
                        </div>
                        <div className="truncate font-mono text-[10.5px] text-text-muted">
                          {entry.detail}
                        </div>
                      </div>
                      <span className="shrink-0 font-mono text-[10px] text-text-muted">
                        {new Date(entry.at).toLocaleTimeString("en-US", { hour12: false })}
                      </span>
                      <meta.icon className="hidden shrink-0 text-text-muted sm:block" size={13} />
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
        </div>
      </div>

      {/* Context strip */}
      <Card className="mt-5 flex flex-wrap items-center justify-between gap-3">
        <div className="text-[12px] text-text-secondary">
          Current effective limit{" "}
          <span className="font-mono font-semibold text-warning-text">
            {kwh(user.energyAlertingThreshold, 2)} kWh
          </span>{" "}
          · alerting {user.alerting ? "armed" : "muted"}
        </div>
        <Button
          size="sm"
          onClick={() => {
            void devices.reload();
            void refresh();
          }}
        >
          Sync state
        </Button>
      </Card>
    </>
  );
}

function SimulationSkeleton() {
  return (
    <>
      <div className="mb-6">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="mt-2 h-4 w-72" />
      </div>
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-12">
        <Skeleton className="h-96 lg:col-span-5" />
        <Skeleton className="h-96 lg:col-span-7" />
      </div>
    </>
  );
}
