"use client";

import { useEffect, useRef, useState } from "react";
import { BookOpen, Cpu, Lightbulb, Sparkles, WifiOff } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { useSession } from "@/components/session";
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
import {
  ApiError,
  getOverviewInsight,
  getProviders,
  getSavingTips,
  streamSavingTips,
} from "@/lib/api";
import { useAsync } from "@/lib/use-async";
import { kwh } from "@/lib/format";
import type { InsightDto, ProviderCatalogDto } from "@/lib/types";

type InsightMode = "tips" | "overview";
type Days = 3 | 7 | 30;

export default function InsightsPage() {
  const { user, status, error, refresh } = useSession();
  const userId = user?.userId;

  const providers = useAsync<ProviderCatalogDto>(() => getProviders(), []);
  const [mode, setMode] = useState<InsightMode>("tips");
  const [days, setDays] = useState<Days>(7);
  const [provider, setProvider] = useState<string>("");
  const [model, setModel] = useState<string>("");

  const [result, setResult] = useState<InsightDto | null>(null);
  const [streamText, setStreamText] = useState("");
  const [busy, setBusy] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const cancelRef = useRef<(() => void) | null>(null);

  // Explicit user selection wins; otherwise fall back to the catalog defaults.
  const catalog = providers.data;
  const effectiveProvider = provider || catalog?.defaultProvider || "";
  const effectiveModel = model || catalog?.defaultModel || "";

  useEffect(() => () => cancelRef.current?.(), []);

  async function runGenerate(stream: boolean) {
    if (userId == null) return;
    cancelRef.current?.();
    setBusy(true);
    setRunError(null);
    setResult(null);
    setStreamText("");
    try {
      const opts = {
        provider: effectiveProvider || undefined,
        model: effectiveModel || undefined,
        days,
      };
      if (stream && mode === "tips") {
        setStreaming(true);
        await streamSavingTips(
          userId,
          (chunk) => setStreamText((prev) => prev + chunk),
          opts,
        );
        setStreaming(false);
      } else if (mode === "tips") {
        setResult(await getSavingTips(userId, opts));
      } else {
        setResult(await getOverviewInsight(userId, opts));
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        setRunError("Rate limited by the insight service — wait a moment and retry.");
      } else if (err instanceof ApiError && err.status >= 500) {
        setRunError(`Upstream provider fault (${err.status}). Try another model or retry.`);
      } else {
        setRunError(err instanceof Error ? err.message : "Insight generation failed.");
      }
      setStreaming(false);
    } finally {
      setBusy(false);
    }
  }

  if (status === "loading") return <InsightsSkeleton />;

  if (status !== "ready" || !user) {
    return (
      <>
        <PageHeader title="Insights" description="AI-assisted analysis of your telemetry" />
        <EmptyState
          icon={<WifiOff size={22} />}
          title={status === "missing" ? "Demo session not initialized" : "API gateway unreachable"}
          description={error ?? "Start the backend stack, then retry."}
          actions={<Button variant="primary" onClick={() => void refresh()}>Retry</Button>}
        />
      </>
    );
  }

  const activeProvider =
    catalog?.providers.find((p) => p.name === effectiveProvider) ?? catalog?.providers[0];

  return (
    <>
      <PageHeader
        title="Insights"
        description="LLM-assisted saving tips and consumption overviews"
        actions={
          <Badge tone="info">
            <Cpu size={11} /> {catalog?.defaultProvider ?? "provider"} · default
          </Badge>
        }
      />

      {/* Synthesis parameters */}
      <Card className="mb-5">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-[14px] font-semibold text-text">Synthesis parameters</h2>
          {providers.loading && <Skeleton className="h-4 w-24" />}
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div>
            <Overline>Output</Overline>
            <div className="mt-1.5">
              <SegmentedControl
                value={mode}
                onChange={setMode}
                options={[
                  { value: "tips", label: "Saving tips" },
                  { value: "overview", label: "Overview" },
                ]}
              />
            </div>
          </div>

          <label className="flex flex-col gap-1.5">
            <Overline>Provider</Overline>
            <select
              value={effectiveProvider}
              onChange={(e) => {
                setProvider(e.target.value);
                setModel("");
              }}
              className="h-9 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 text-[13px] text-text focus:border-accent focus:outline-none"
            >
              {(catalog?.providers ?? []).map((p) => (
                <option key={p.name} value={p.name} disabled={!p.available}>
                  {p.name}
                  {!p.available ? " (unavailable)" : ""}
                </option>
              ))}
              {!catalog?.providers.length && <option value="">loading…</option>}
            </select>
          </label>

          <label className="flex flex-col gap-1.5">
            <Overline>Model</Overline>
            <select
              value={effectiveModel}
              onChange={(e) => setModel(e.target.value)}
              className="h-9 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 text-[13px] text-text focus:border-accent focus:outline-none"
            >
              {(activeProvider?.models ?? []).map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
              {!activeProvider?.models.length && <option value="">default</option>}
            </select>
          </label>
        </div>

        <div className="mt-4 flex flex-wrap items-end justify-between gap-3 border-t border-border pt-4">
          <div>
            <Overline>Analysis window</Overline>
            <div className="mt-1.5">
              <SegmentedControl
                value={String(days)}
                onChange={(v) => setDays(Number(v) as Days)}
                options={[
                  { value: "3", label: "3 days" },
                  { value: "7", label: "7 days" },
                  { value: "30", label: "30 days" },
                ]}
              />
            </div>
          </div>
          <div className="flex gap-2">
            <Button
              size="sm"
              disabled={busy}
              onClick={() => void runGenerate(mode === "tips")}
              title={mode === "tips" ? "Stream tokens as they arrive" : undefined}
            >
              <Sparkles size={13} />
              {busy
                ? streaming
                  ? "Streaming…"
                  : "Generating…"
                : mode === "tips"
                  ? "Generate (stream)"
                  : "Generate"}
            </Button>
            {mode === "tips" && (
              <Button size="sm" disabled={busy} onClick={() => void runGenerate(false)}>
                One-shot
              </Button>
            )}
          </div>
        </div>
      </Card>

      {runError && (
        <div className="mb-5">
          <AlertBanner
            title="Insight generation failed"
            description={runError}
            onDismiss={() => setRunError(null)}
            action={
              <Button size="sm" onClick={() => void runGenerate(mode === "tips")}>
                Retry
              </Button>
            }
          />
        </div>
      )}

      {/* Result panel */}
      {busy && !streamText && !result && (
        <Card>
          <Skeleton className="h-4 w-40" />
          <div className="mt-4 flex flex-col gap-2">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-[92%]" />
            <Skeleton className="h-4 w-[78%]" />
          </div>
        </Card>
      )}

      {(streamText || result) && (
        <Card>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              {mode === "tips" ? (
                <Lightbulb size={15} className="text-warning-text" />
              ) : (
                <BookOpen size={15} className="text-info" />
              )}
              <h2 className="text-[14px] font-semibold text-text">
                {mode === "tips" ? "Saving tips" : "Consumption overview"}
              </h2>
              {streaming && <Badge tone="accent" dot>streaming</Badge>}
              {result?.periodAdjusted && (
                <Badge tone="warning">
                  window adjusted → {result.effectiveDays}d
                </Badge>
              )}
            </div>
            {result && (
              <span className="font-mono text-[11px] text-text-muted">
                usage {kwh(result.energyUsage)} kWh · {(result.metrics?.model ?? effectiveModel) || "default model"}
              </span>
            )}
          </div>

          <div className="whitespace-pre-wrap text-[13.5px] leading-relaxed text-text-secondary">
            {mode === "tips" && streamText
              ? streamText
              : result?.tips || "No content returned."}
            {streaming && (
              <span className="ml-0.5 inline-block h-3.5 w-[7px] translate-y-[2px] animate-pulse bg-accent" />
            )}
          </div>

          {result?.metrics && (
            <div className="mt-4 flex flex-wrap gap-2 border-t border-border pt-3 font-mono text-[10.5px] text-text-muted">
              {result.metrics.provider && <span>provider={result.metrics.provider}</span>}
              {result.metrics.model && <span>model={result.metrics.model}</span>}
              {result.metrics.totalTokens != null && (
                <span>tokens={result.metrics.totalTokens}</span>
              )}
              {result.metrics.latencyMs != null && (
                <span>latency={result.metrics.latencyMs}ms</span>
              )}
            </div>
          )}
        </Card>
      )}

      {!busy && !result && !streamText && !runError && (
        <EmptyState
          icon={<Sparkles size={22} />}
          title="No insight generated yet"
          description="Pick a provider and window above, then generate. Tips stream token-by-token over SSE; the overview is a single JSON response."
        />
      )}
    </>
  );
}

function InsightsSkeleton() {
  return (
    <>
      <div className="mb-6">
        <Skeleton className="h-8 w-32" />
        <Skeleton className="mt-2 h-4 w-64" />
      </div>
      <Skeleton className="mb-5 h-44 w-full" />
      <Skeleton className="h-64 w-full" />
    </>
  );
}
