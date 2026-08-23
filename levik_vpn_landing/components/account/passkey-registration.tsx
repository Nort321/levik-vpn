"use client";

import type { FormEvent } from "react";
import { useState } from "react";

import { PasskeyIcon } from "@/components/account/account-icons";
import {
  accountClientRequest,
  accountErrorMessage,
  isRecord,
} from "@/components/login/account-api-client";
import {
  parseRegistrationOptions,
  requirePublicKeyCredential,
  serializeRegistrationResponse,
} from "@/components/login/webauthn";

export function PasskeyRegistration({ csrfToken }: { csrfToken: string }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function register(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!window.PublicKeyCredential || !navigator.credentials) {
      setError("Этот браузер или устройство не поддерживает passkey.");
      return;
    }
    const form = event.currentTarget;
    const formData = new FormData(form);
    const nameValue = formData.get("name");
    const name = typeof nameValue === "string" ? nameValue.trim() : "";
    if (name.length < 2 || name.length > 120) {
      setError("Введите понятное название длиной от 2 до 120 символов.");
      return;
    }

    setPending(true);
    setError(null);
    try {
      const rawOptions = await accountClientRequest("/passkeys/options", {
        method: "POST",
        body: { name },
        csrfToken,
      });
      const { ceremonyId, publicKey } = parseRegistrationOptions(rawOptions);
      const credential = requirePublicKeyCredential(
        await navigator.credentials.create({ publicKey }),
      );
      const result = await accountClientRequest("/passkeys/verify", {
        method: "POST",
        body: {
          ceremonyId,
          response: serializeRegistrationResponse(credential),
          name,
        },
        csrfToken,
      });
      if (!isRecord(result) || result.ok !== true || !isRecord(result.passkey)) {
        throw new Error("Invalid passkey response");
      }
      window.location.assign("/dashboard/passkeys?notice=passkey_registered");
    } catch (caught) {
      setError(
        caught instanceof DOMException && caught.name === "NotAllowedError"
          ? "Создание passkey отменено или истекло."
          : accountErrorMessage(caught),
      );
      setPending(false);
    }
  }

  return (
    <form className="account-form passkey-registration" onSubmit={(event) => void register(event)}>
      <label>
        <span>Название passkey</span>
        <input
          autoComplete="off"
          disabled={pending}
          maxLength={120}
          minLength={2}
          name="name"
          placeholder="Например, Pixel 9 или MacBook"
          required
          type="text"
        />
      </label>
      <button className="button button--primary" disabled={pending} type="submit">
        <PasskeyIcon />
        {pending ? "Подтвердите на устройстве…" : "Добавить passkey"}
      </button>
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </form>
  );
}
