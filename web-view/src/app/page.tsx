"use client";

import Link from "next/link";
import { useState } from "react";
import {
  AlertTriangle,
  ArrowRight,
  Database,
  FlaskConical,
  Gauge,
  Sparkles,
  TrendingUp,
  WifiOff,
} from "lucide-react";
import { useSession } from "@/components/session";
import { PageHeader } from "@/components/page-header";
import {
  AlertBanner,
  Badge,
  Button,
  Card,
  EmptyState,
  Overline,
  SegmentedControl,
  Skeleton,
} from "@/components/ui";
import { UsageChart, seriesToChart } from "@/components/dashboard/usage-chart";
import { getAlerts, getCoverage, getDevicesByUser, getUsage, getUsageSeries } from "@/lib/api";
import { useAsync } from "@/lib/use-async";
import { deviceMeta } from "@/lib/device-meta";
import { compactDateTime, isWithinLastDays, kwh, percent, relativeTime } from "@/lib/format";
import type { AlertDto, DeviceDto } from "@/lib/types";

export default function OverviewPage() {
  const { user, status, error, refresh } = useSession();
  const [interval, setInterval] = useState<"hour" | "day">("hour");
  const userId = user?.userId;

  const devices = useAsync<DeviceDto[]>(
    () => getDevicesByUser(userId!),
    [userId],
    { enabled: userId != null },
  );
  const usage = useAsync(() => getUsage(userId!, 7), [userId], {
    enabled: userId != null,
  });
  const series = useAsync(
    () => getUsageSeries(userId!, interval === "hour" ? 7 : 7, interval),
    [userId, interval],
    { enabled: userId != null },
  );
  const coverage = useAsync(() => getCoverage(userId!), [userId], {
    enabled: userId != null,
  });
  const alerts = useAsync(() => getAlerts(userId!, 50), [userId], {
    enabled: userId != null,
  });

  if (status === "loading") return <DashboardSkeleton />;

  if (status === "offline") {
    return (
      <>
        <PageHeader title="Overview" description="House-wide consumption at a glance" />
        <EmptyState
          icon={<WifiOff size={22} />}
          title="API gateway unreachable"
          description={
            error ??
            "The Next.js proxy forwards /backend to the Spring gateway on port 9000. Start the backend stack, then retry."
          }
          actions={<Button variant="primary" onClick={() => void refresh()}>Retry connection</Button>}
        />
      </>
    );
  }

  if (status === "missing" || !user) {
    return (
      <>
        <PageHeader title="Overview" description="House-wide consumption at a glance" />
        <EmptyState
          icon={<Database size={22} />}
          title="Demo session not initialized"
          description={
            error ??
            "GET /api/v1/user/demo returned 404. Seed the user-service database or provision a demo user to continue."
          }
          actions={<Button variant="primary" onClick={() => void refresh()}>Re-check session</Button>}
        />
      </>
    );
  }

  const chartData = series.data ? seriesToChart(series.data) : [];
  const totalKwh = usage.data?.devices.reduce((s, d) => s + d.energyConsumed, 0) ?? 0;
  const threshold = user.energyAlertingThreshold ?? 0;
  const recentAlerts = alerts.data ?? [];
  const alert7d = recentAlerts.filter((a) => isWithinLastDays(a.createdAt, 7));
  const violations7d = alert7d.length;
  const deviceList = [...(usage.data?.devices ?? [])].sort(
    (a, b) => b.energyConsumed - a.energyConsumed,
  );
  const maxEnergy = deviceList[0]?.energyConsumed || 1;
  const cov = coverage.data;

  return (
    <>
      <PageHeader
        title="Overview"
        description={`Rolling 7-day window for ${user.name || user.email}`}
        actions={
          <>
            <Badge tone={user.alerting ? "warning" : "neutral"} dot={user.alerting}>
              {user.alerting ? "Alerting armed" : "Alerting muted"}
            </Badge>
            <Button size="sm" onClick={() => { void devices.reload(); void usage.reload(); void series.reload(); void alerts.reload(); void coverage.reload(); }}>
              Refresh
            </Button>
          </>
        }
      />

      {(series.error || usage.error || alerts.error) && (
        <div className="mb-4">
          <AlertBanner
            tone="warning"
            title="Some panels failed to load"
            description="Partial data shown below — device registry and alerts may be out of sync."
            action={
              <Button size="sm" onClick={() => { void series.reload(); void usage.reload(); void alerts.reload(); }}>
                Retry
              </Button>
            }
          />
        </div>
      )}

      {/* KPI tiles */}
      <div className="mb-5 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Monitored devices"
          value={devices.loading ? null : String(devices.data?.length ?? 0)}
          unit="nodes"
          hint={typeBreakdown(devices.data ?? [])}
          icon={<Gauge size={14} />}
        />
        <StatTile
          label="7-day consumption"
          value={usage.loading ? null : kwh(totalKwh)}
          unit="kWh"
          hint="Aggregated across all devices"
          tone="accent"
          icon={<TrendingUp size={14} />}
        />
        <StatTile
          label="Alert threshold"
          value={kwh(threshold, 2)}
          unit="kWh / bucket"
          hint="System-wide ceiling"
          tone="warning"
        />
        <StatTile
          label="Alerts · last 7 days"
          value={alerts.loading ? null : String(violations7d)}
          unit="events"
          hint="Threshold violations & notices"
          tone={violations7d > 0 ? "critical" : "neutral"}
          icon={<AlertTriangle size={14} />}
        />
      </div>

      {/* Main chart */}
      <Card className="mb-5">
        <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-[15px] font-semibold text-text">Consumption telemetry</h2>
              <Badge tone="accent" dot>
                live
              </Badge>
            </div>
            <p className="mt-0.5 text-[12px] text-text-secondary">
              Sum of all device energy readings per time bucket · non-interpolated
            </p>
          </div>
          <SegmentedControl
            value={interval}
            onChange={setInterval}
            options={[
              { value: "hour", label: "Hourly · 7d" },
              { value: "day", label: "Daily" },
            ]}
          />
        </div>

        {series.loading ? (
          <Skeleton className="h-72 w-full" />
        ) : chartData.length === 0 ? (
          <EmptyState
            title="No telemetry in this window"
            description="Dispatch synthetic readings from the Simulation page or wait for live device data."
            actions={
              <Link href="/simulation">
                <Button variant="primary" size="sm">
                  Open simulation
                </Button>
              </Link>
            }
            className="border-0"
          />
        ) : (
          <UsageChart data={chartData} threshold={threshold} />
        )}

        {cov && (
          <div className="mt-4 flex flex-col gap-1 rounded-[var(--radius)] bg-surface-1 px-3.5 py-2.5 text-[11px] text-text-secondary sm:flex-row sm:items-center sm:justify-between">
            <span>
              Coverage:{" "}
              <span className="font-mono font-medium text-accent-text">
                {cov.daysWithData} of {cov.maxDays} days
              </span>{" "}
              ({percent(cov.maxDays ? (cov.daysWithData / cov.maxDays) * 100 : 0)})
              {cov.devicesWithNoData.length > 0 && (
                <> · {cov.devicesWithNoData.length} device(s) silent</>
              )}
            </span>
            <span className="font-mono text-[10px] text-text-muted">
              {cov.firstDataDay ?? "—"} → {cov.lastDataDay ?? "—"}
            </span>
          </div>
        )}
      </Card>

      {/* Two-column: breakdown + alerts */}
      <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-12">
        <Card className="lg:col-span-7">
          <div className="mb-4 flex items-center justify-between">
            <div>
              <h2 className="text-[15px] font-semibold text-text">Energy by device</h2>
              <p className="mt-0.5 text-[12px] text-text-secondary">
                Ranked over the rolling 7-day interval
              </p>
            </div>
            <span className="font-mono text-[11px] text-text-muted">
              {deviceList.length} channels
            </span>
          </div>

          {usage.loading ? (
            <div className="flex flex-col gap-2.5">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-[72px] w-full" />
              ))}
            </div>
          ) : deviceList.length === 0 ? (
            <EmptyState
              title="No device usage yet"
              description="Register devices and push telemetry to see the breakdown."
              actions={
                <Link href="/devices">
                  <Button size="sm">Manage devices</Button>
                </Link>
              }
              className="border-0 py-10"
            />
          ) : (
            <ul className="flex flex-col gap-2">
              {deviceList.map((d) => {
                const meta = deviceMeta(d.type);
                const Icon = meta.icon;
                const share = totalKwh > 0 ? (d.energyConsumed / totalKwh) * 100 : 0;
                return (
                  <li
                    key={`${d.name}-${d.location}`}
                    className="rounded-[var(--radius)] bg-surface-1 p-3.5 transition-colors hover:bg-surface-2"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex min-w-0 items-center gap-3">
                        <span
                          className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-[var(--radius-sm)] border ${meta.tint}`}
                        >
                          <Icon size={14} />
                        </span>
                        <div className="min-w-0">
                          <div className="truncate text-[13px] font-medium text-text">
                            {d.name}
                          </div>
                          <div className="truncate text-[11px] text-text-muted">
                            {d.location} · {meta.label}
                          </div>
                        </div>
                      </div>
                      <div className="shrink-0 text-right">
                        <div className="font-mono text-[15px] font-semibold tabular text-text">
                          {kwh(d.energyConsumed)}{" "}
                          <span className="text-[11px] font-normal text-text-muted">kWh</span>
                        </div>
                        <div className="font-mono text-[10px] tabular text-text-secondary">
                          {percent(share)} share
                        </div>
                      </div>
                    </div>
                    <div className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-surface-3">
                      <div
                        className="h-full rounded-full bg-accent transition-all"
                        style={{ width: `${Math.max(2, (d.energyConsumed / maxEnergy) * 100)}%` }}
                      />
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        <div className="flex flex-col gap-5 lg:col-span-5">
          <Card>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-[15px] font-semibold text-text">Recent alerts</h2>
              <Link
                href="/alerts"
                className="flex items-center gap-1 text-[12px] text-accent-text transition-colors hover:text-accent"
              >
                View all <ArrowRight size={12} />
              </Link>
            </div>
            {alerts.loading ? (
              <div className="flex flex-col gap-2">
                {Array.from({ length: 3 }).map((_, i) => (
                  <Skeleton key={i} className="h-16 w-full" />
                ))}
              </div>
            ) : recentAlerts.length === 0 ? (
              <p className="py-6 text-center text-[12px] text-text-muted">
                No threshold violations recorded. Quiet grid.
              </p>
            ) : (
              <ul className="flex flex-col gap-2">
                {recentAlerts.slice(0, 4).map((a) => (
                  <AlertRow key={a.id} alert={a} />
                ))}
              </ul>
            )}
          </Card>

          {/* Simulation CTA */}
          <Card raised className="relative overflow-hidden">
            <div className="mb-2 flex items-start justify-between">
              <div>
                <Overline>Sandbox</Overline>
                <h3 className="mt-0.5 text-[14px] font-semibold text-text">
                  Dispatch synthetic telemetry
                </h3>
              </div>
              <FlaskConical size={18} className="text-accent-text" />
            </div>
            <p className="mb-3.5 text-[12px] leading-relaxed text-text-secondary">
              Push normal or spike readings through the ingestion pipeline and watch
              threshold checks react in real time.
            </p>
            <Link href="/simulation">
              <Button variant="primary" size="sm" className="w-full">
                Open simulation <ArrowRight size={13} />
              </Button>
            </Link>
          </Card>

          {/* Insights teaser */}
          <Card>
            <div className="mb-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Sparkles size={15} className="text-info" />
                <h3 className="text-[14px] font-semibold text-text">AI insights</h3>
              </div>
              <span className="font-mono text-[10px] text-text-muted">LLM-assisted</span>
            </div>
            <p className="mb-3 text-[12px] leading-relaxed text-text-secondary">
              Generate saving tips and a consumption overview from your telemetry via
              Groq or Gemini.
            </p>
            <Link href="/insights">
              <Button size="sm" className="w-full">
                Inspect insights <ArrowRight size={13} />
              </Button>
            </Link>
          </Card>
        </div>
      </div>
    </>
  );
}

/* ---------- pieces ---------- */

function StatTile({
  label,
  value,
  unit,
  hint,
  tone = "neutral",
  icon,
}: {
  label: string;
  value: string | null;
  unit: string;
  hint: string;
  tone?: "neutral" | "accent" | "warning" | "critical";
  icon?: React.ReactNode;
}) {
  const valueTone =
    tone === "accent"
      ? "text-accent-text"
      : tone === "warning"
        ? "text-warning-text"
        : tone === "critical"
          ? "text-critical-text"
          : "text-text";
  return (
    <Card raised className="flex flex-col justify-between">
      <div className="mb-3 flex items-center justify-between">
        <Overline className="flex items-center gap-1.5">
          {icon}
          {label}
        </Overline>
      </div>
      <div className="flex items-baseline gap-1.5">
        {value === null ? (
          <Skeleton className="h-8 w-20" />
        ) : (
          <span className={`font-mono text-[28px] font-semibold leading-none tabular ${valueTone}`}>
            {value}
          </span>
        )}
        <span className="text-[12px] text-text-muted">{unit}</span>
      </div>
      <div className="mt-3 truncate rounded-[var(--radius-sm)] bg-surface-0 px-2.5 py-1.5 text-[11px] text-text-muted">
        {hint}
      </div>
    </Card>
  );
}

function AlertRow({ alert }: { alert: AlertDto }) {
  const over = alert.energyConsumed > alert.threshold;
  return (
    <li className="rounded-[var(--radius)] bg-surface-1 p-3">
      <div className="flex items-center justify-between gap-2">
        <span
          className={
            over
              ? "text-[12px] font-semibold text-critical-text"
              : "text-[12px] font-semibold text-warning-text"
          }
        >
          Threshold violation
        </span>
        <span className="font-mono text-[10px] text-text-muted">
          {relativeTime(alert.createdAt)}
        </span>
      </div>
      <p className="mt-1 text-[12px] leading-snug text-text-secondary">
        Load reached{" "}
        <strong className={over ? "font-mono text-critical-text" : "font-mono text-warning-text"}>
          {kwh(alert.energyConsumed, 2)} kWh
        </strong>{" "}
        against a {kwh(alert.threshold, 2)} kWh limit.
      </p>
      <div className="mt-1.5 font-mono text-[10px] text-text-muted">
        {compactDateTime(alert.createdAt)} · {alert.sent ? "email sent" : "not sent"}
      </div>
    </li>
  );
}

function typeBreakdown(devices: DeviceDto[]): string {
  if (!devices.length) return "No hardware registered";
  const counts = new Map<string, number>();
  for (const d of devices) counts.set(d.type, (counts.get(d.type) ?? 0) + 1);
  return [...counts.entries()]
    .map(([type, n]) => `${n} ${type.toLowerCase()}`)
    .join(" · ");
}

function DashboardSkeleton() {
  return (
    <>
      <div className="mb-6">
        <Skeleton className="h-8 w-40" />
        <Skeleton className="mt-2 h-4 w-64" />
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-32" />
        ))}
      </div>
      <Skeleton className="mt-5 h-96 w-full" />
    </>
  );
}
