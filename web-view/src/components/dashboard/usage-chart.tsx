"use client";

import {
  Area,
  AreaChart,
  CartesianGrid,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { UsageSeriesDto } from "@/lib/types";
import { kwh } from "@/lib/format";

export interface ChartPoint {
  label: string;
  total: number;
}

/** Flatten per-device series into per-bucket totals with relative time labels. */
export function seriesToChart(series: UsageSeriesDto): ChartPoint[] {
  if (!series.devices.length) return [];
  const length = Math.max(...series.devices.map((d) => d.points.length), 0);
  const points: ChartPoint[] = [];
  for (let i = 0; i < length; i++) {
    const total = series.devices.reduce(
      (sum, d) => sum + (d.points[i]?.energyConsumed ?? 0),
      0,
    );
    const back = length - 1 - i;
    const label =
      series.interval === "hour"
        ? back === 0
          ? "now"
          : `−${back}h`
        : back === 0
          ? "today"
          : `−${back}d`;
    points.push({ label, total: Math.round(total * 100) / 100 });
  }
  return points;
}

interface TooltipProps {
  active?: boolean;
  payload?: { payload?: ChartPoint }[];
  threshold: number;
}

function ChartTooltip({ active, payload, threshold }: TooltipProps) {
  if (!active || !payload?.length || !payload[0]?.payload) return null;
  const point = payload[0].payload as ChartPoint;
  const over = point.total > threshold;
  return (
    <div className="panel-raised px-3 py-2 text-[12px]">
      <div className="font-mono text-[10px] uppercase tracking-wider text-text-muted">
        {point.label}
      </div>
      <div
        className={
          over ? "mt-1 font-mono font-semibold text-critical-text" : "mt-1 font-mono font-semibold text-text"
        }
      >
        {kwh(point.total, 2)} kWh
        {over && <span className="ml-1.5 text-[10px] font-normal">over limit</span>}
      </div>
    </div>
  );
}

export function UsageChart({
  data,
  threshold,
  compact = false,
}: {
  data: ChartPoint[];
  threshold: number;
  compact?: boolean;
}) {
  // Show every nth label to keep the axis readable on dense hourly series
  const interval = Math.max(1, Math.ceil(data.length / 8));
  return (
    <div className={compact ? "h-52 w-full" : "h-72 w-full"}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -12 }}>
          <defs>
            <linearGradient id="usageFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#10b981" stopOpacity={0.22} />
              <stop offset="100%" stopColor="#10b981" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke="#262b34" strokeDasharray="3 4" vertical={false} />
          <XAxis
            dataKey="label"
            interval={interval - 1}
            tick={{ fill: "#6d7585", fontSize: 10, fontFamily: "JetBrains Mono" }}
            tickLine={false}
            axisLine={{ stroke: "#262b34" }}
          />
          <YAxis
            tick={{ fill: "#6d7585", fontSize: 10, fontFamily: "JetBrains Mono" }}
            tickLine={false}
            axisLine={false}
            width={44}
          />
          <Tooltip
            content={<ChartTooltip threshold={threshold} />}
            cursor={{ stroke: "#333945", strokeWidth: 1 }}
          />
          {threshold > 0 && (
            <ReferenceLine
              y={threshold}
              stroke="#f59e0b"
              strokeDasharray="6 4"
              strokeWidth={1.25}
              label={{
                value: `Limit ${kwh(threshold, 2)} kWh`,
                position: "insideTopLeft",
                fill: "#fbbf24",
                fontSize: 10,
                fontFamily: "JetBrains Mono",
                offset: 8,
              }}
            />
          )}
          <Area
            type="monotone"
            dataKey="total"
            stroke="#10b981"
            strokeWidth={1.75}
            fill="url(#usageFill)"
            dot={false}
            activeDot={{ r: 3, fill: "#10b981", stroke: "#0e1013", strokeWidth: 2 }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
