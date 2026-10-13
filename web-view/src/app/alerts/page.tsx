"use client";

import { useMemo, useState } from "react";
import { BellOff, ShieldCheck, TriangleAlert, WifiOff } from "lucide-react";
import { clsx } from "clsx";
import { PageHeader } from "@/components/page-header";
import { useSession } from "@/components/session";
import {
  AlertBanner,
  Badge,
  Button,
  Card,
  EmptyState,
  SegmentedControl,
  Skeleton,
} from "@/components/ui";
import { getAlerts } from "@/lib/api";
import { useAsync } from "@/lib/use-async";
import { compactDateTime, kwh, relativeTime } from "@/lib/format";
import type { AlertDto } from "@/lib/types";

type Filter = "all" | "violations" | "unsent";

export default function AlertsPage() {
  const { user, status, error, refresh } = useSession();
  const userId = user?.userId;
  const [filter, setFilter] = useState<Filter>("all");
  const alerts = useAsync<AlertDto[]>(() => getAlerts(userId!), [userId], {
    enabled: userId != null,
  });

  const filtered = useMemo(() => {
    const list = alerts.data ?? [];
    const sorted = [...list].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    );
    if (filter === "violations") return sorted.filter((a) => a.energyConsumed > a.threshold);
    if (filter === "unsent") return sorted.filter((a) => !a.sent);
    return sorted;
  }, [alerts.data, filter]);

  if (status === "loading") return <AlertsSkeleton />;

  if (status !== "ready" || !user) {
    return (
      <>
        <PageHeader title="Alerts" description="Threshold violation ledger" />
        <EmptyState
          icon={<WifiOff size={22} />}
          title={status === "missing" ? "Demo session not initialized" : "API gateway unreachable"}
          description={error ?? "Start the backend stack, then retry."}
          actions={<Button variant="primary" onClick={() => void refresh()}>Retry</Button>}
        />
      </>
    );
  }

  const violationCount = (alerts.data ?? []).filter((a) => a.energyConsumed > a.threshold).length;
  const unsentCount = (alerts.data ?? []).filter((a) => !a.sent).length;

  return (
    <>
      <PageHeader
        title="Alerts"
        description="Chronological record of threshold evaluations"
        actions={
          <SegmentedControl
            value={filter}
            onChange={setFilter}
            options={[
              { value: "all", label: `All (${alerts.data?.length ?? 0})` },
              { value: "violations", label: `Violations (${violationCount})` },
              { value: "unsent", label: `Unsent (${unsentCount})` },
            ]}
          />
        }
      />

      {/* Summary strip */}
      <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <SummaryCard
          label="Total events"
          value={String(alerts.data?.length ?? 0)}
          icon={<TriangleAlert size={13} className="text-text-muted" />}
        />
        <SummaryCard
          label="Threshold breaches"
          value={String(violationCount)}
          tone={violationCount > 0 ? "critical" : "neutral"}
          icon={<TriangleAlert size={13} />}
        />
        <SummaryCard
          label="Emails pending"
          value={String(unsentCount)}
          tone={unsentCount > 0 ? "warning" : "neutral"}
          icon={<ShieldCheck size={13} />}
        />
      </div>

      {alerts.error && (
        <div className="mb-4">
          <AlertBanner
            title="Alert service unreachable"
            description={`GET /alert/user/${user.userId} failed (${alerts.error.status}). The ledger may be stale.`}
            action={<Button size="sm" onClick={alerts.reload}>Retry</Button>}
          />
        </div>
      )}

      {alerts.loading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-20 w-full" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={<BellOff size={22} />}
          title={filter === "all" ? "No alerts recorded" : "Nothing matches this filter"}
          description={
            filter === "all"
              ? "The alerting service emits an event whenever aggregate load crosses your threshold. Dispatch a high-spike simulation to see one land here."
              : "Switch filters or dispatch new telemetry to generate events."
          }
        />
      ) : (
        <Card className="p-0">
          <ul className="divide-y divide-border">
            {filtered.map((a) => (
              <AlertLedgerRow key={a.id} alert={a} />
            ))}
          </ul>
        </Card>
      )}
    </>
  );
}

function AlertLedgerRow({ alert }: { alert: AlertDto }) {
  const over = alert.energyConsumed > alert.threshold;
  return (
    <li className="flex flex-col gap-2 px-4 py-3.5 transition-colors hover:bg-surface-1 sm:flex-row sm:items-center sm:gap-4">
      <span
        className={clsx(
          "mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-[var(--radius-sm)] border",
          over
            ? "border-[rgba(244,63,94,0.28)] bg-critical-soft text-critical-text"
            : "border-[rgba(245,158,11,0.28)] bg-warning-soft text-warning-text",
        )}
      >
        <TriangleAlert size={14} />
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={clsx("text-[13px] font-semibold", over ? "text-critical-text" : "text-warning-text")}>
            {over ? "Threshold violation" : "Threshold event"}
          </span>
          <Badge tone={over ? "critical" : "warning"}>
            {kwh(alert.energyConsumed, 2)} / {kwh(alert.threshold, 2)} kWh
          </Badge>
          <Badge tone={alert.sent ? "accent" : "neutral"}>
            {alert.sent ? "email sent" : "unsent"}
          </Badge>
        </div>
        <p className="mt-1 truncate text-[12.5px] text-text-secondary">{alert.message}</p>
        <div className="mt-0.5 font-mono text-[10.5px] text-text-muted">
          #{alert.id} · {alert.email}
        </div>
      </div>

      <div className="shrink-0 text-right">
        <div className="font-mono text-[11px] tabular text-text-secondary" title={compactDateTime(alert.createdAt)}>
          {relativeTime(alert.createdAt)}
        </div>
        <div className="font-mono text-[10px] text-text-muted">{compactDateTime(alert.createdAt)}</div>
      </div>
    </li>
  );
}

function SummaryCard({
  label,
  value,
  tone = "neutral",
  icon,
}: {
  label: string;
  value: string;
  tone?: "neutral" | "critical" | "warning";
  icon: React.ReactNode;
}) {
  return (
    <Card raised className="flex items-center justify-between px-4 py-3.5">
      <div>
        <div className="overline">{label}</div>
        <div
          className={clsx(
            "mt-1 font-mono text-[22px] font-semibold leading-none tabular",
            tone === "critical" && "text-critical-text",
            tone === "warning" && "text-warning-text",
            tone === "neutral" && "text-text",
          )}
        >
          {value}
        </div>
      </div>
      <span className="text-text-muted">{icon}</span>
    </Card>
  );
}

function AlertsSkeleton() {
  return (
    <>
      <div className="mb-6">
        <Skeleton className="h-8 w-28" />
        <Skeleton className="mt-2 h-4 w-60" />
      </div>
      <div className="mb-5 grid grid-cols-3 gap-3">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-20" />
        ))}
      </div>
      <div className="flex flex-col gap-2">
        {Array.from({ length: 5 }).map((_, i) => (
          <Skeleton key={i} className="h-20" />
        ))}
      </div>
    </>
  );
}
