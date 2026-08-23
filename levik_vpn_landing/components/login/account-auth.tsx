"use client";

import Script from "next/script";
import type { FormEvent } from "react";
import { useCallback, useEffect, useRef, useState } from "react";

import {
  GoogleIcon,
  PasskeyIcon,
  RecoveryIcon,
} from "@/components/account/account-icons";
import { LockIcon, RefreshIcon } from "@/components/icons";
import {
  accountClientRequest,
  accountErrorMessage,
  isRecord,
  requiredString,
  safeAccountReturnTo,
} from "@/components/login/account-api-client";
import {
  parseAuthenticationOptions,
  requirePublicKeyCredential,
  serializeAuthenticationResponse,
} from "@/components/login/webauthn";

type Providers = {
  google: {
    enabled: boolean;
    clientId: string | null;
    nonce: string | null;
  };
  passkey: boolean;
  password: boolean;
  recovery: boolean;
};

type GoogleCredentialResponse = {
  credential?: string;
};

type GoogleAccountsId = {
  initialize(options: {
    client_id: string;
    callback: (response: GoogleCredentialResponse) => void;
    nonce: string;
    auto_select: false;
    cancel_on_tap_outside: true;
    use_fedcm_for_button: true;
  }): void;
  renderButton(
    parent: HTMLElement,
    options: {
      type: "standard";
      theme: "filled_black";
      size: "large";
      shape: "pill";
      text: "continue_with";
      width: number;
    },
  ): void;
  cancel(): void;
};

declare global {
  interface Window {
    google?: { accounts: { id: GoogleAccountsId } };
  }
}

function parseProviders(value: unknown): Providers {
  if (!isRecord(value) || value.ok !== true || !isRecord(value.providers)) {
    throw new Error("Invalid auth providers response");
  }
  const { google, passkey, password, recovery } = value.providers;
  if (
    !isRecord(google) ||
    typeof google.enabled !== "boolean" ||
    !isRecord(passkey) ||
    passkey.enabled !== true ||
    passkey.rpId !== "leviknet.com" ||
    !isRecord(password) ||
    password.enabled !== true ||
    !isRecord(recovery) ||
    recovery.enabled !== true
  ) {
    throw new Error("Invalid auth providers response");
  }
  const clientId =
    google.clientId === null ? null : requiredString(google.clientId, 1, 512);
  const nonce = google.nonce === null ? null : requiredString(google.nonce, 16, 512);
  return {
    google: { enabled: google.enabled, clientId, nonce },
    passkey: true,
    password: true,
    recovery: true,
  };
}

function validateAuthSuccess(value: unknown): void {
  if (
    !isRecord(value) ||
    value.ok !== true ||
    !isRecord(value.account) ||
    !isRecord(value.session) ||
    typeof value.csrfToken !== "string"
  ) {
    throw new Error("Invalid authentication response");
  }
}

function fieldValue(formData: FormData, name: string): string {
  const value = formData.get(name);
  return typeof value === "string" ? value : "";
}

export function AccountAuth({
  cspNonce,
  returnTo,
}: {
  cspNonce?: string;
  returnTo: string;
}) {
  const [providers, setProviders] = useState<Providers | null>(null);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [methodError, setMethodError] = useState<string | null>(null);
  const [pending, setPending] = useState<"google" | "passkey" | "password" | "recovery" | null>(null);
  const [googleLoaded, setGoogleLoaded] = useState(false);
  const googleButton = useRef<HTMLDivElement>(null);
  const destination = safeAccountReturnTo(returnTo);

  useEffect(() => {
    const controller = new AbortController();
    accountClientRequest("/auth/providers")
      .then((payload) => {
        if (!controller.signal.aborted) setProviders(parseProviders(payload));
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setProviderError(accountErrorMessage(error));
      });
    return () => controller.abort();
  }, []);

  const finishAuthentication = useCallback(() => {
    window.location.assign(destination);
  }, [destination]);

  const handleGoogleCredential = useCallback(
    async (response: GoogleCredentialResponse) => {
      if (!providers?.google.nonce || !response.credential) {
        setMethodError("Google не вернул подтверждение входа.");
        return;
      }
      setPending("google");
      setMethodError(null);
      try {
        const result = await accountClientRequest("/auth/google", {
          method: "POST",
          body: { idToken: response.credential, nonce: providers.google.nonce },
        });
        validateAuthSuccess(result);
        finishAuthentication();
      } catch (error) {
        setMethodError(accountErrorMessage(error));
        setPending(null);
      }
    },
    [finishAuthentication, providers?.google.nonce],
  );

  useEffect(() => {
    const google = window.google?.accounts.id;
    const button = googleButton.current;
    const config = providers?.google;
    if (
      !googleLoaded ||
      !google ||
      !button ||
      !config?.enabled ||
      !config.clientId ||
      !config.nonce
    ) {
      return;
    }
    button.replaceChildren();
    google.initialize({
      client_id: config.clientId,
      callback: (response) => void handleGoogleCredential(response),
      nonce: config.nonce,
      auto_select: false,
      cancel_on_tap_outside: true,
      use_fedcm_for_button: true,
    });
    google.renderButton(button, {
      type: "standard",
      theme: "filled_black",
      size: "large",
      shape: "pill",
      text: "continue_with",
      width: Math.min(360, Math.max(240, button.clientWidth)),
    });
    return () => google.cancel();
  }, [googleLoaded, handleGoogleCredential, providers]);

  async function signInWithPasskey(): Promise<void> {
    if (!window.PublicKeyCredential || !navigator.credentials) {
      setMethodError("Этот браузер или устройство не поддерживает passkey.");
      return;
    }
    setPending("passkey");
    setMethodError(null);
    try {
      const rawOptions = await accountClientRequest("/auth/passkey/options", {
        method: "POST",
        body: {},
      });
      const { ceremonyId, publicKey } = parseAuthenticationOptions(rawOptions);
      const credential = requirePublicKeyCredential(
        await navigator.credentials.get({ publicKey }),
      );
      const result = await accountClientRequest("/auth/passkey/verify", {
        method: "POST",
        body: {
          ceremonyId,
          response: serializeAuthenticationResponse(credential),
        },
      });
      validateAuthSuccess(result);
      finishAuthentication();
    } catch (error) {
      setMethodError(
        error instanceof DOMException && error.name === "NotAllowedError"
          ? "Подтверждение passkey отменено или истекло."
          : accountErrorMessage(error),
      );
      setPending(null);
    }
  }

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending("password");
    setMethodError(null);
    const form = event.currentTarget;
    const data = new FormData(form);
    try {
      const result = await accountClientRequest("/auth/password", {
        method: "POST",
        body: {
          levikId: fieldValue(data, "levikId"),
          password: fieldValue(data, "password"),
        },
      });
      validateAuthSuccess(result);
      form.reset();
      finishAuthentication();
    } catch (error) {
      form.querySelector<HTMLInputElement>("[name=password]")?.select();
      setMethodError(accountErrorMessage(error));
      setPending(null);
    }
  }

  async function submitRecovery(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending("recovery");
    setMethodError(null);
    const form = event.currentTarget;
    const data = new FormData(form);
    try {
      const result = await accountClientRequest("/auth/recovery", {
        method: "POST",
        body: {
          levikId: fieldValue(data, "levikId"),
          code: fieldValue(data, "recoveryCode"),
        },
      });
      validateAuthSuccess(result);
      form.reset();
      finishAuthentication();
    } catch (error) {
      form.querySelector<HTMLInputElement>("[name=recoveryCode]")?.select();
      setMethodError(accountErrorMessage(error));
      setPending(null);
    }
  }

  return (
    <div className="account-auth" aria-busy={!providers && !providerError}>
      {providers?.google.enabled && providers.google.clientId && providers.google.nonce ? (
        <>
          <Script
            nonce={cspNonce}
            onError={() => setMethodError("Не удалось загрузить безопасный вход Google.")}
            onLoad={() => setGoogleLoaded(true)}
            src="https://accounts.google.com/gsi/client"
            strategy="afterInteractive"
          />
          <div className="account-auth__google">
            <span className="account-auth__method-icon"><GoogleIcon /></span>
            <div>
              <strong>Google</strong>
              <span>Связь выполняется по Google ID, не по email.</span>
            </div>
            <div aria-label="Продолжить с Google" ref={googleButton} />
          </div>
        </>
      ) : null}

      {providers?.passkey ? (
        <button
          className="button button--primary button--wide button--large"
          disabled={pending !== null}
          onClick={() => void signInWithPasskey()}
          type="button"
        >
          <PasskeyIcon />
          {pending === "passkey" ? "Подтверждаем…" : "Войти с passkey"}
        </button>
      ) : null}

      {providers?.password ? (
        <details className="auth-method">
          <summary>
            <LockIcon />
            <span>Levik ID и парольная фраза</span>
          </summary>
          <form className="account-form" onSubmit={(event) => void submitPassword(event)}>
            <label>
              <span>Levik ID</span>
              <input
                autoCapitalize="none"
                autoComplete="username"
                maxLength={32}
                minLength={3}
                name="levikId"
                pattern="[A-Za-z0-9][A-Za-z0-9._-]*"
                required
                spellCheck={false}
                type="text"
              />
            </label>
            <label>
              <span>Пароль или парольная фраза</span>
              <input
                autoComplete="current-password"
                maxLength={256}
                minLength={12}
                name="password"
                required
                type="password"
              />
            </label>
            <button className="button button--primary button--wide" disabled={pending !== null} type="submit">
              <LockIcon />
              {pending === "password" ? "Проверяем…" : "Войти по Levik ID"}
            </button>
          </form>
        </details>
      ) : null}

      {providers?.recovery ? (
        <details className="auth-method">
          <summary>
            <RecoveryIcon />
            <span>Использовать recovery-код</span>
          </summary>
          <form className="account-form" onSubmit={(event) => void submitRecovery(event)}>
            <label>
              <span>Levik ID</span>
              <input
                autoCapitalize="none"
                autoComplete="username"
                maxLength={32}
                minLength={3}
                name="levikId"
                required
                spellCheck={false}
                type="text"
              />
            </label>
            <label>
              <span>Одноразовый recovery-код</span>
              <input
                autoCapitalize="characters"
                autoComplete="one-time-code"
                maxLength={32}
                minLength={12}
                name="recoveryCode"
                required
                spellCheck={false}
                type="text"
              />
            </label>
            <button className="button button--quiet button--wide" disabled={pending !== null} type="submit">
              <RecoveryIcon />
              {pending === "recovery" ? "Проверяем код…" : "Использовать код"}
            </button>
          </form>
        </details>
      ) : null}

      {!providers && !providerError ? (
        <div className="account-auth__loading" role="status">
          <RefreshIcon className="is-spinning" />
          Проверяем доступные способы входа…
        </div>
      ) : null}
      {providerError ? (
        <div className="inline-message inline-message--warning" role="alert">
          <strong>Levik Account временно недоступен</strong>
          <span>{providerError} Telegram-вход ниже продолжает работать отдельно.</span>
        </div>
      ) : null}
      {methodError ? (
        <div className="inline-message inline-message--warning" role="alert">
          <strong>Вход не завершён</strong>
          <span>{methodError}</span>
        </div>
      ) : null}
    </div>
  );
}
