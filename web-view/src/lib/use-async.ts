"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, isNetworkError } from "./api";

export interface AsyncState<T> {
  data: T | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => void;
}

/**
 * Minimal fetch-on-mount hook with reload. Errors are normalized to ApiError
 * so pages can branch on offline (status 0) vs HTTP failures.
 *
 * `loading` is derived (no sync setState in effects): true only while the
 * first request for the current deps is still in flight. Refetches keep the
 * previous data visible (stale-while-revalidate).
 */
export function useAsync<T>(
  fn: () => Promise<T>,
  deps: readonly unknown[],
  opts?: { enabled?: boolean },
): AsyncState<T> {
  const enabled = opts?.enabled ?? true;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    fn()
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(
          err instanceof ApiError
            ? err
            : new ApiError(
                isNetworkError(err) ? 0 : 500,
                "unknown",
                err instanceof Error ? err.message : undefined,
              ),
        );
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, enabled]);

  return {
    data,
    error,
    loading: enabled && data === null && error === null,
    reload,
  };
}
