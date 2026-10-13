"use client";

import { useMemo, useState } from "react";
import { Cpu, MapPin, Pencil, Plus, Trash2, WifiOff, X } from "lucide-react";
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
import {
  createDevice,
  deleteDevice,
  getDevicesByUser,
  updateDevice,
} from "@/lib/api";
import { useAsync } from "@/lib/use-async";
import { DEVICE_META, deviceMeta } from "@/lib/device-meta";
import { compactDateTime } from "@/lib/format";
import { DEVICE_TYPES, type DeviceDto, type DeviceType } from "@/lib/types";

type ModalMode = { kind: "create" } | { kind: "edit"; device: DeviceDto } | null;

export default function DevicesPage() {
  const { user, status, error, refresh } = useSession();
  const userId = user?.userId;
  const devices = useAsync<DeviceDto[]>(() => getDevicesByUser(userId!), [userId], {
    enabled: userId != null,
  });

  const [modal, setModal] = useState<ModalMode>(null);
  const [detail, setDetail] = useState<DeviceDto | null>(null);
  const [pendingDelete, setPendingDelete] = useState<DeviceDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const grouped = useMemo(() => {
    const map = new Map<DeviceType, DeviceDto[]>();
    for (const d of devices.data ?? []) {
      const list = map.get(d.type) ?? [];
      list.push(d);
      map.set(d.type, list);
    }
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [devices.data]);

  async function handleDelete(device: DeviceDto) {
    setBusy(true);
    setActionError(null);
    try {
      await deleteDevice(device.deviceId);
      setPendingDelete(null);
      setDetail(null);
      devices.reload();
    } catch (err) {
      setActionError(err instanceof Error ? err.message : "Failed to delete device.");
    } finally {
      setBusy(false);
    }
  }

  if (status === "loading") return <DevicesSkeleton />;

  if (status !== "ready" || !user) {
    return (
      <>
        <PageHeader title="Devices" description="Registered monitoring hardware" />
        <EmptyState
          icon={<WifiOff size={22} />}
          title={status === "missing" ? "Demo session not initialized" : "API gateway unreachable"}
          description={error ?? "Start the backend stack, then retry."}
          actions={<Button variant="primary" onClick={() => void refresh()}>Retry</Button>}
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Devices"
        description={`${devices.data?.length ?? 0} registered nodes for ${user.name || user.email}`}
        actions={
          <Button variant="primary" size="sm" onClick={() => setModal({ kind: "create" })}>
            <Plus size={14} /> Register device
          </Button>
        }
      />

      {actionError && (
        <div className="mb-4">
          <AlertBanner title="Action failed" description={actionError} onDismiss={() => setActionError(null)} />
        </div>
      )}
      {devices.error && (
        <div className="mb-4">
          <AlertBanner
            tone="warning"
            title="Device registry unreachable"
            description={`GET /device/user/${user.userId} failed (${devices.error.status}).`}
            action={<Button size="sm" onClick={devices.reload}>Retry</Button>}
          />
        </div>
      )}

      {devices.loading ? (
        <DevicesSkeletonGrid />
      ) : grouped.length === 0 ? (
        <EmptyState
          icon={<Cpu size={22} />}
          title="No devices registered"
          description="Register your first monitoring node — speakers, cameras, thermostats, lights, locks, or doorbells."
          actions={
            <Button variant="primary" onClick={() => setModal({ kind: "create" })}>
              <Plus size={14} /> Register first device
            </Button>
          }
        />
      ) : (
        <div className="flex flex-col gap-6">
          {grouped.map(([type, list]) => {
            const meta = DEVICE_META[type];
            const Icon = meta.icon;
            return (
              <section key={type}>
                <div className="mb-2.5 flex items-center gap-2">
                  <span className={clsx("flex h-6 w-6 items-center justify-center rounded-[5px] border", meta.tint)}>
                    <Icon size={12} />
                  </span>
                  <h2 className="text-[13px] font-semibold text-text">{meta.label}s</h2>
                  <span className="font-mono text-[11px] text-text-muted">{list.length}</span>
                </div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
                  {list.map((d) => (
                    <Card
                      key={d.deviceId}
                      raised
                      className="cursor-pointer transition-colors hover:border-border-strong"
                      onClick={() => setDetail(d)}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="truncate text-[13.5px] font-semibold text-text">{d.name}</div>
                          <div className="mt-0.5 flex items-center gap-1 truncate text-[11.5px] text-text-muted">
                            <MapPin size={11} /> {d.location}
                          </div>
                        </div>
                        <Badge>{meta.label}</Badge>
                      </div>
                      <div className="mt-3 flex items-center justify-between border-t border-border pt-2.5">
                        <span className="font-mono text-[10.5px] text-text-muted">
                          ID {d.deviceId}
                        </span>
                        <div className="flex items-center gap-1" onClick={(e) => e.stopPropagation()}>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7 px-2"
                            onClick={() => setModal({ kind: "edit", device: d })}
                            aria-label={`Edit ${d.name}`}
                          >
                            <Pencil size={12} />
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="h-7 px-2 text-critical-text hover:bg-critical-soft"
                            onClick={() => setPendingDelete(d)}
                            aria-label={`Delete ${d.name}`}
                          >
                            <Trash2 size={12} />
                          </Button>
                        </div>
                      </div>
                    </Card>
                  ))}
                </div>
              </section>
            );
          })}
        </div>
      )}

      {/* Detail drawer */}
      {detail && (
        <Drawer onClose={() => setDetail(null)} title={detail.name}>
          <div className="flex flex-col gap-4">
            <Field label="Device ID">
              <span className="font-mono text-accent-text">{detail.deviceId}</span>
            </Field>
            <Field label="Type">
              <Badge className={clsx(deviceMeta(detail.type).tint)}>
                {deviceMeta(detail.type).label}
              </Badge>
            </Field>
            <Field label="Location">{detail.location}</Field>
            <Field label="Owner user ID">
              <span className="font-mono">{detail.userId}</span>
            </Field>
            <Field label="Registered">
              <span className="font-mono">{compactDateTime(detail.createdAt)}</span>
            </Field>
            <Field label="Last updated">
              <span className="font-mono">{compactDateTime(detail.updatedAt)}</span>
            </Field>
            <div className="mt-2 flex gap-2">
              <Button
                size="sm"
                onClick={() => {
                  setModal({ kind: "edit", device: detail });
                  setDetail(null);
                }}
              >
                <Pencil size={12} /> Edit
              </Button>
              <Button size="sm" variant="danger" onClick={() => setPendingDelete(detail)}>
                <Trash2 size={12} /> Delete
              </Button>
            </div>
          </div>
        </Drawer>
      )}

      {/* Create / edit modal */}
      {modal && (
        <DeviceModal
          mode={modal}
          userId={user.userId}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null);
            devices.reload();
          }}
        />
      )}

      {/* Delete confirm */}
      {pendingDelete && (
        <ConfirmDialog
          title="Delete device"
          description={`Remove “${pendingDelete.name}” from the registry? Historical usage stays in InfluxDB but the node stops reporting.`}
          busy={busy}
          onCancel={() => setPendingDelete(null)}
          onConfirm={() => void handleDelete(pendingDelete)}
        />
      )}
    </>
  );
}

/* ---------- sub-components ---------- */

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <Overline>{label}</Overline>
      <div className="mt-1 text-[13px] text-text">{children}</div>
    </div>
  );
}

function Drawer({
  title,
  children,
  onClose,
}: {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-40 flex justify-end" role="dialog" aria-modal="true">
      <button
        className="absolute inset-0 bg-black/55 backdrop-blur-[2px]"
        aria-label="Close drawer"
        onClick={onClose}
      />
      <div className="relative flex h-full w-full max-w-[380px] flex-col border-l border-border-strong bg-surface-0 shadow-[0_20px_48px_-8px_rgba(0,0,0,0.7)]">
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <h2 className="truncate text-[15px] font-semibold text-text">{title}</h2>
          <button
            onClick={onClose}
            className="rounded p-1 text-text-muted transition-colors hover:text-text"
            aria-label="Close"
          >
            <X size={16} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-5">{children}</div>
      </div>
    </div>
  );
}

function DeviceModal({
  mode,
  userId,
  onClose,
  onSaved,
}: {
  mode: Exclude<ModalMode, null>;
  userId: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = mode.kind === "edit" ? mode.device : null;
  const [name, setName] = useState(editing?.name ?? "");
  const [type, setType] = useState<DeviceType>(editing?.type ?? "LIGHT");
  const [location, setLocation] = useState(editing?.location ?? "");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim() || !location.trim()) {
      setErr("Name and location are required.");
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      if (editing) {
        await updateDevice(editing.deviceId, { name: name.trim(), type, location: location.trim() });
      } else {
        await createDevice({ name: name.trim(), type, location: location.trim(), userId });
      }
      onSaved();
    } catch (error) {
      setErr(error instanceof Error ? error.message : "Save failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center p-4" role="dialog" aria-modal="true">
      <button className="absolute inset-0 bg-black/55 backdrop-blur-[2px]" aria-label="Close" onClick={onClose} />
      <Card raised className="relative w-full max-w-md shadow-[0_20px_48px_-8px_rgba(0,0,0,0.7)]">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-[15px] font-semibold text-text">
            {editing ? "Edit device" : "Register new device"}
          </h2>
          <button onClick={onClose} className="rounded p-1 text-text-muted hover:text-text" aria-label="Close">
            <X size={16} />
          </button>
        </div>

        {err && (
          <div className="mb-3">
            <AlertBanner title="Could not save" description={err} onDismiss={() => setErr(null)} />
          </div>
        )}

        <form onSubmit={submit} className="flex flex-col gap-3.5">
          <label className="flex flex-col gap-1.5">
            <Overline>Name</Overline>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Kitchen smart plug"
              className="h-9 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 text-[13px] text-text placeholder:text-text-muted focus:border-accent focus:outline-none"
            />
          </label>

          <label className="flex flex-col gap-1.5">
            <Overline>Type</Overline>
            <select
              value={type}
              onChange={(e) => setType(e.target.value as DeviceType)}
              className="h-9 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 text-[13px] text-text focus:border-accent focus:outline-none"
            >
              {DEVICE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {DEVICE_META[t].label}
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1.5">
            <Overline>Location</Overline>
            <input
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              placeholder="Utility pantry"
              className="h-9 rounded-[var(--radius-sm)] border border-border bg-surface-0 px-3 text-[13px] text-text placeholder:text-text-muted focus:border-accent focus:outline-none"
            />
          </label>

          <div className="mt-1 flex justify-end gap-2">
            <Button type="button" size="sm" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant="primary" size="sm" disabled={busy}>
              {busy ? "Saving…" : editing ? "Save changes" : "Register device"}
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}

function ConfirmDialog({
  title,
  description,
  busy,
  onCancel,
  onConfirm,
}: {
  title: string;
  description: string;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="alertdialog" aria-modal="true">
      <button className="absolute inset-0 bg-black/55 backdrop-blur-[2px]" aria-label="Cancel" onClick={onCancel} />
      <Card raised className="relative w-full max-w-sm shadow-[0_20px_48px_-8px_rgba(0,0,0,0.7)]">
        <h2 className="text-[15px] font-semibold text-text">{title}</h2>
        <p className="mt-2 text-[12.5px] leading-relaxed text-text-secondary">{description}</p>
        <div className="mt-5 flex justify-end gap-2">
          <Button size="sm" onClick={onCancel}>
            Cancel
          </Button>
          <Button size="sm" variant="danger" onClick={onConfirm} disabled={busy}>
            {busy ? "Deleting…" : "Delete device"}
          </Button>
        </div>
      </Card>
    </div>
  );
}

function DevicesSkeleton() {
  return (
    <>
      <div className="mb-6">
        <Skeleton className="h-8 w-36" />
        <Skeleton className="mt-2 h-4 w-56" />
      </div>
      <DevicesSkeletonGrid />
    </>
  );
}

function DevicesSkeletonGrid() {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
      {Array.from({ length: 6 }).map((_, i) => (
        <Skeleton key={i} className="h-28" />
      ))}
    </div>
  );
}
