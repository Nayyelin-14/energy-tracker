"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Activity,
  Bell,
  Cpu,
  FlaskConical,
  Sparkles,
  Zap,
} from "lucide-react";
import { clsx } from "clsx";
import type { ReactNode } from "react";
import { useSession } from "./session";

const NAV = [
  { href: "/", label: "Overview", icon: Activity },
  { href: "/devices", label: "Devices", icon: Cpu },
  { href: "/alerts", label: "Alerts", icon: Bell },
  { href: "/insights", label: "Insights", icon: Sparkles },
  { href: "/simulation", label: "Simulation", icon: FlaskConical },
] as const;

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { user, status } = useSession();

  const statusDot =
    status === "ready"
      ? "bg-accent"
      : status === "loading"
        ? "bg-warning"
        : "bg-critical";

  const statusLabel =
    status === "ready"
      ? user
        ? `Demo user #${user.userId}`
        : "Connected"
      : status === "loading"
        ? "Connecting…"
        : status === "missing"
          ? "No demo user"
          : "Gateway offline";

  return (
    <div className="flex min-h-screen">
      {/* Sidebar */}
      <aside className="fixed inset-y-0 left-0 z-30 flex w-[216px] flex-col border-r border-border bg-surface-0">
        <div className="flex h-14 items-center gap-2.5 border-b border-border px-4">
          <span className="flex h-7 w-7 items-center justify-center rounded-[var(--radius-sm)] bg-accent-soft text-accent-text">
            <Zap size={15} strokeWidth={2.2} />
          </span>
          <div className="leading-tight">
            <div className="text-[13px] font-semibold tracking-tight text-text">
              Energy Tracker
            </div>
            <div className="font-mono text-[10px] text-text-muted">v0.1 playground</div>
          </div>
        </div>

        <nav className="flex flex-1 flex-col gap-0.5 p-2.5" aria-label="Primary">
          {NAV.map(({ href, label, icon: Icon }) => {
            const active =
              href === "/" ? pathname === "/" : pathname.startsWith(href);
            return (
              <Link
                key={href}
                href={href}
                className={clsx(
                  "flex items-center gap-2.5 rounded-[var(--radius-sm)] px-2.5 py-2 text-[13px] font-medium transition-colors",
                  active
                    ? "bg-surface-2 text-text shadow-[inset_2px_0_0_0_var(--accent)]"
                    : "text-text-secondary hover:bg-surface-1 hover:text-text",
                )}
              >
                <Icon size={15} strokeWidth={active ? 2.2 : 1.8} />
                {label}
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-border p-3.5">
          <div className="flex items-center gap-2">
            <span className={clsx("h-1.5 w-1.5 rounded-full", statusDot)} />
            <span className="text-[11px] text-text-secondary">{statusLabel}</span>
          </div>
          <div className="mt-1 font-mono text-[10px] text-text-muted">
            api gateway :9000
          </div>
        </div>
      </aside>

      {/* Content */}
      <main className="ml-[216px] flex-1 px-6 py-6 lg:px-8 xl:px-10">
        <div className="mx-auto max-w-[1180px] fade-in">{children}</div>
      </main>
    </div>
  );
}
