"use client";

import { useId, useRef, useState } from "react";

import {
  CloseIcon,
  ProxyIcon,
  TelegramIcon,
} from "@/components/icons";

type FreeProxyButtonProps = {
  className: string;
  label?: string;
};

type ProxyState =
  | { kind: "idle" }
  | { kind: "loading" }
  | {
      kind: "ready";
      link: string;
      deviceLimit: number;
      rateLimitMbps: number;
    }
  | { kind: "error"; message: string };

type ProxyClaimResponse = {
  ok?: unknown;
  link?: unknown;
  deviceLimit?: unknown;
  rateLimitMbps?: unknown;
  message?: unknown;
};

function isSuccessfulClaim(
  value: ProxyClaimResponse,
): value is {
  ok: true;
  link: string;
  deviceLimit: number;
  rateLimitMbps: number;
} {
  return (
    value.ok === true &&
    typeof value.link === "string" &&
    value.link.startsWith("tg://proxy?") &&
    typeof value.deviceLimit === "number" &&
    Number.isSafeInteger(value.deviceLimit) &&
    value.deviceLimit > 0 &&
    typeof value.rateLimitMbps === "number" &&
    Number.isSafeInteger(value.rateLimitMbps) &&
    value.rateLimitMbps > 0
  );
}

export function FreeProxyButton({
  className,
  label = "Получить бесплатный proxy",
}: FreeProxyButtonProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [state, setState] = useState<ProxyState>({ kind: "idle" });

  const requestProxy = async () => {
    setState({ kind: "loading" });
    dialogRef.current?.showModal();

    try {
      const response = await fetch("/api/free-proxy", {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
        headers: {
          Accept: "application/json",
        },
      });
      const result = (await response.json()) as ProxyClaimResponse;

      if (!response.ok || !isSuccessfulClaim(result)) {
        setState({
          kind: "error",
          message:
            typeof result.message === "string"
              ? result.message
              : "Proxy сейчас недоступен. Попробуйте немного позже.",
        });
        return;
      }

      setState({
        kind: "ready",
        link: result.link,
        deviceLimit: result.deviceLimit,
        rateLimitMbps: result.rateLimitMbps,
      });
    } catch {
      setState({
        kind: "error",
        message: "Proxy сейчас недоступен. Попробуйте немного позже.",
      });
    }
  };

  const closeDialog = () => {
    dialogRef.current?.close();
    setState({ kind: "idle" });
  };

  return (
    <>
      <button
        className={className}
        disabled={state.kind === "loading"}
        onClick={() => {
          void requestProxy();
        }}
        type="button"
      >
        <ProxyIcon />
        {label}
      </button>

      <dialog
        aria-labelledby={titleId}
        className="proxy-dialog"
        onClose={() => {
          setState({ kind: "idle" });
        }}
        ref={dialogRef}
      >
        <button
          aria-label="Закрыть"
          className="proxy-dialog__close"
          onClick={closeDialog}
          type="button"
        >
          <CloseIcon />
        </button>
        <div className="proxy-dialog__icon">
          <ProxyIcon height={30} width={30} />
        </div>
        <h2 id={titleId}>Бесплатный Telegram proxy</h2>

        {state.kind === "loading" ? (
          <div aria-live="polite" className="proxy-dialog__status" role="status">
            <span aria-hidden="true" className="button-spinner" />
            <span>Готовим персональный proxy…</span>
          </div>
        ) : null}

        {state.kind === "ready" ? (
          <>
            <p>
              Proxy готов: до {state.rateLimitMbps} Мбит/с,{" "}
              {state.deviceLimit === 1
                ? "для одного устройства"
                : `до ${state.deviceLimit} устройств`}
              .
            </p>
            <a
              className="button button--primary button--large button--wide"
              href={state.link}
            >
              <TelegramIcon />
              Открыть в Telegram
            </a>
          </>
        ) : null}

        {state.kind === "error" ? (
          <div
            className="inline-message inline-message--warning"
            role="alert"
          >
            <strong>Не удалось получить proxy</strong>
            <span>{state.message}</span>
          </div>
        ) : null}
      </dialog>
    </>
  );
}
