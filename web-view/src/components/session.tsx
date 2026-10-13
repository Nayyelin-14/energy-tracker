"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { ApiError, getDemoUser, isNetworkError } from "@/lib/api";
import type { UserDto } from "@/lib/types";

export type SessionStatus = "loading" | "ready" | "missing" | "offline";

interface SessionContextValue {
  user: UserDto | null;
  status: SessionStatus;
  error: string | null;
  refresh: () => void;
  patchUser: (user: UserDto) => void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/**
 * Resolves the shared demo playground user (GET /user/demo) once and exposes
 * it to every page. Supports refresh for the "sandbox uninitialized" and
 * "gateway down" recovery flows.
 */
export function DemoSessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserDto | null>(null);
  const [status, setStatus] = useState<SessionStatus>("loading");
  const [error, setError] = useState<string | null>(null);

  const applySuccess = useCallback((demo: UserDto) => {
    setUser(demo);
    setStatus("ready");
    setError(null);
  }, []);

  const applyFailure = useCallback((err: unknown) => {
    setUser(null);
    if (isNetworkError(err) || (err instanceof ApiError && err.status === 0)) {
      setStatus("offline");
      setError("Cannot reach the API gateway at /backend. Is it running on port 9000?");
    } else if (err instanceof ApiError && err.status === 404) {
      setStatus("missing");
      setError("No demo user provisioned in the user-service database.");
    } else {
      setStatus("offline");
      setError(err instanceof Error ? err.message : "Unexpected session error.");
    }
  }, []);

  /** Initial load — setState only inside promise callbacks. */
  useEffect(() => {
    let cancelled = false;
    getDemoUser()
      .then((demo) => {
        if (!cancelled) applySuccess(demo);
      })
      .catch((err: unknown) => {
        if (!cancelled) applyFailure(err);
      });
    return () => {
      cancelled = true;
    };
  }, [applySuccess, applyFailure]);

  /** User-triggered retry: show the loading state immediately, then reload. */
  const refresh = useCallback(() => {
    setStatus("loading");
    setError(null);
    getDemoUser().then(applySuccess).catch(applyFailure);
  }, [applySuccess, applyFailure]);

  const patchUser = useCallback((next: UserDto) => setUser(next), []);

  const value = useMemo(
    () => ({ user, status, error, refresh, patchUser }),
    [user, status, error, refresh, patchUser],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error("useSession must be used inside DemoSessionProvider");
  return ctx;
}
