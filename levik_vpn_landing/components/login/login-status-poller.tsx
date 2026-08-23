"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { RefreshIcon } from "@/components/icons";
import type { LoginPollResult } from "@/components/view-types";

type LoginStatusPollerProps = {
  expiresAt: string;
  initialExpiresLabel: string;
  pollAfterMs: number;
};

type PollerState =
  | { kind: "waiting"; message: string }
  | { kind: "expired"; message: string }
  | { kind: "error"; message: string };

const DEFAULT_POLL_INTERVAL = 2500;

export function LoginStatusPoller({
  expiresAt,
  initialExpiresLabel,
  pollAfterMs,
}: LoginStatusPollerProps) {
  const router = useRouter();
  const safePollInterval = useMemo(
    () => Math.min(Math.max(pollAfterMs, 1500), 10_000),
    [pollAfterMs],
  );
  const [state, setState] = useState<PollerState>({
    kind: "waiting",
    message: `Ожидаем подтверждение · ${initialExpiresLabel}`,
  });

  useEffect(() => {
    const expiresAtMs = Date.parse(expiresAt);
    const controller = new AbortController();
    let timeout: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      if (Number.isFinite(expiresAtMs) && Date.now() >= expiresAtMs) {
        setState({
          kind: "expired",
          message: "Код больше не действует. Создайте новый запрос на вход.",
        });
        return;
      }

      try {
        const response = await fetch("/api/auth/status", {
          method: "POST",
          credentials: "same-origin",
          cache: "no-store",
          headers: {
            Accept: "application/json",
          },
          signal: controller.signal,
        });

        const result = (await response.json()) as LoginPollResult;

        if (
          response.ok &&
          result.state === "authenticated" &&
          result.redirectTo === "/dashboard"
        ) {
          router.replace("/dashboard");
          router.refresh();
          return;
        }

        if (result.state === "expired") {
          setState({ kind: "expired", message: result.message });
          return;
        }

        if (result.state === "error") {
          setState({ kind: "error", message: result.message });
          timeout = setTimeout(() => {
            void poll();
          }, DEFAULT_POLL_INTERVAL * 2);
          return;
        }

        setState({
          kind: "waiting",
          message: `Ожидаем подтверждение · ${initialExpiresLabel}`,
        });
        timeout = setTimeout(() => {
          void poll();
        }, safePollInterval);
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }

        setState({
          kind: "error",
          message: "Не удалось проверить вход. Повторим автоматически.",
        });
        timeout = setTimeout(() => {
          void poll();
        }, DEFAULT_POLL_INTERVAL * 2);
      }
    };

    timeout = setTimeout(() => {
      void poll();
    }, safePollInterval);

    return () => {
      controller.abort();
      if (timeout) clearTimeout(timeout);
    };
  }, [expiresAt, initialExpiresLabel, router, safePollInterval]);

  return (
    <div
      aria-live="polite"
      className={`login-poll login-poll--${state.kind}`}
      role="status"
    >
      <RefreshIcon className="login-poll__icon" />
      <span>{state.message}</span>
    </div>
  );
}
