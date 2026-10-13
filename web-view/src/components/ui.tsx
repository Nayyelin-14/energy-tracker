import { clsx } from "clsx";
import type { ComponentProps, ReactNode } from "react";

/** Hairline panel with layered-surface treatment (no drop shadows). */
export function Card({
  className,
  raised = false,
  ...props
}: ComponentProps<"div"> & { raised?: boolean }) {
  return (
    <div
      className={clsx(
        raised ? "panel-raised" : "panel",
        "p-5",
        className,
      )}
      {...props}
    />
  );
}

/** Small uppercase overline label. */
export function Overline({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return <div className={clsx("overline", className)}>{children}</div>;
}

type Tone = "neutral" | "accent" | "warning" | "critical" | "info";

const TONES: Record<Tone, string> = {
  neutral: "text-text-secondary bg-surface-2 border-border",
  accent: "text-accent-text bg-accent-soft border-[rgba(16,185,129,0.25)]",
  warning: "text-warning-text bg-warning-soft border-[rgba(245,158,11,0.28)]",
  critical: "text-critical-text bg-critical-soft border-[rgba(244,63,94,0.28)]",
  info: "text-info bg-info-soft border-[rgba(56,189,248,0.25)]",
};

/** Status chip — 22px tall, hairline border + tint wash, optional pulse dot. */
export function Badge({
  tone = "neutral",
  dot = false,
  children,
  className,
}: {
  tone?: Tone;
  dot?: boolean;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span
      className={clsx(
        "inline-flex h-[22px] items-center gap-1.5 rounded-[5px] border px-2 font-mono text-[11px] font-medium tabular",
        TONES[tone],
        className,
      )}
    >
      {dot && (
        <span className="relative flex h-1.5 w-1.5">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-60" />
          <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-current" />
        </span>
      )}
      {children}
    </span>
  );
}

type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary:
    "bg-accent text-[#06281c] font-semibold hover:bg-[#0ea371] active:translate-y-px",
  secondary:
    "bg-surface-2 text-text border border-border hover:border-border-strong hover:bg-surface-3",
  danger:
    "bg-critical-soft text-critical-text border border-[rgba(244,63,94,0.35)] hover:bg-critical hover:text-[#2a0710]",
  ghost: "text-text-secondary hover:text-text hover:bg-surface-2",
};

export function Button({
  variant = "secondary",
  size = "md",
  className,
  ...props
}: ComponentProps<"button"> & { variant?: ButtonVariant; size?: "sm" | "md" }) {
  return (
    <button
      className={clsx(
        "inline-flex items-center justify-center gap-2 rounded-[var(--radius-sm)] font-medium transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/60",
        "disabled:cursor-not-allowed disabled:opacity-50",
        size === "sm" ? "h-8 px-3 text-[12px]" : "h-9 px-4 text-[13px]",
        BUTTON_VARIANTS[variant],
        className,
      )}
      {...props}
    />
  );
}

/** Centered empty state with icon, title, body, and optional actions. */
export function EmptyState({
  icon,
  title,
  description,
  actions,
  className,
}: {
  icon?: ReactNode;
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={clsx(
        "panel flex flex-col items-center justify-center px-8 py-16 text-center",
        className,
      )}
    >
      {icon && (
        <div className="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-surface-2 text-text-muted">
          {icon}
        </div>
      )}
      <h3 className="text-[15px] font-semibold text-text">{title}</h3>
      {description && (
        <p className="mt-1.5 max-w-md text-[13px] leading-relaxed text-text-secondary">
          {description}
        </p>
      )}
      {actions && <div className="mt-5 flex flex-wrap justify-center gap-2">{actions}</div>}
    </div>
  );
}

/** Inline alert banner — degraded service / recoverable error. */
export function AlertBanner({
  tone = "critical",
  title,
  description,
  action,
  onDismiss,
}: {
  tone?: "critical" | "warning";
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  onDismiss?: () => void;
}) {
  const isCritical = tone === "critical";
  return (
    <div
      role="alert"
      className={clsx(
        "panel-raised flex flex-col gap-3 border-l-4 p-4 sm:flex-row sm:items-center sm:justify-between",
        isCritical
          ? "border-l-critical bg-[rgba(244,63,94,0.06)]"
          : "border-l-warning bg-[rgba(245,158,11,0.06)]",
      )}
    >
      <div>
        <div
          className={clsx(
            "text-[13px] font-semibold",
            isCritical ? "text-critical-text" : "text-warning-text",
          )}
        >
          {title}
        </div>
        {description && (
          <div className="mt-0.5 text-[12px] text-text-secondary">{description}</div>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {action}
        {onDismiss && (
          <button
            onClick={onDismiss}
            aria-label="Dismiss"
            className="rounded p-1 text-text-muted transition-colors hover:text-text focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/60"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden>
              <path d="M3 3l8 8M11 3l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}

/** Loading skeleton block. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      className={clsx("animate-pulse rounded-[var(--radius)] bg-surface-2", className)}
    />
  );
}

/** Segmented toggle (hourly/daily style). */
export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <div className="inline-flex items-center gap-0.5 rounded-[var(--radius-sm)] bg-surface-0 p-0.5 ring-1 ring-border">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={clsx(
            "rounded-[5px] px-2.5 py-1 text-[11px] font-medium transition-colors",
            value === opt.value
              ? "bg-surface-2 text-text shadow-[inset_0_1px_0_0_rgba(255,255,255,0.04)]"
              : "text-text-muted hover:text-text-secondary",
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
