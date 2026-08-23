"use client";

import type { FormEvent } from "react";
import { useState } from "react";
import Link from "next/link";

import { DeleteAccountIcon } from "@/components/account/account-icons";
import { ArrowUpRightIcon } from "@/components/icons";
import {
  accountClientRequest,
  accountErrorMessage,
  isRecord,
  requiredString,
} from "@/components/login/account-api-client";

export function AccountDeletion({
  csrfToken,
  levikId,
}: {
  csrfToken: string;
  levikId: string;
}) {
  const [confirmationToken, setConfirmationToken] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function requestDeletion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    if (data.get("levikId") !== levikId) {
      setError("Levik ID не совпадает с текущим аккаунтом.");
      return;
    }
    setPending(true);
    setError(null);
    try {
      const result = await accountClientRequest("/deletion/request", {
        method: "POST",
        body: {},
        csrfToken,
      });
      if (!isRecord(result) || result.ok !== true) {
        throw new Error("Invalid deletion response");
      }
      setConfirmationToken(requiredString(result.confirmationToken, 32, 512));
    } catch (caught) {
      setError(accountErrorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  async function confirmDeletion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!confirmationToken) return;
    const data = new FormData(event.currentTarget);
    if (data.get("confirmation") !== "УДАЛИТЬ АККАУНТ") {
      setError("Введите фразу подтверждения точно, заглавными буквами.");
      return;
    }
    setPending(true);
    setError(null);
    try {
      const result = await accountClientRequest("/deletion/confirm", {
        method: "POST",
        body: { confirmationToken },
        csrfToken,
      });
      if (!isRecord(result) || result.ok !== true) {
        throw new Error("Invalid deletion response");
      }
      setConfirmationToken(null);
      setCompleted(true);
    } catch (caught) {
      setError(accountErrorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  if (completed) {
    return (
      <div className="account-feedback account-feedback--success" role="status">
        <DeleteAccountIcon />
        <span>
          Аккаунт обезличен, credentials, сеансы и устройства отозваны. На этом
          устройстве выполнен выход.
        </span>
        <Link className="button button--quiet button--compact" href="/">
          <ArrowUpRightIcon />
          На главную
        </Link>
      </div>
    );
  }

  return confirmationToken ? (
    <form className="account-form account-delete-form" onSubmit={(event) => void confirmDeletion(event)}>
      <div className="inline-message inline-message--warning" role="alert">
        <strong>Последнее подтверждение</strong>
        <span>Операцию нельзя отменить после завершения серверной обработки.</span>
      </div>
      <label>
        <span>Введите «УДАЛИТЬ АККАУНТ»</span>
        <input
          autoComplete="off"
          disabled={pending}
          name="confirmation"
          pattern="УДАЛИТЬ АККАУНТ"
          required
          type="text"
        />
      </label>
      <button className="button button--danger-ghost" disabled={pending} type="submit">
        <DeleteAccountIcon />
        {pending ? "Удаляем аккаунт…" : "Безвозвратно удалить аккаунт"}
      </button>
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </form>
  ) : (
    <form className="account-form account-delete-form" onSubmit={(event) => void requestDeletion(event)}>
      <label>
        <span>Подтвердите свой Levik ID</span>
        <input
          autoCapitalize="none"
          autoComplete="username"
          disabled={pending}
          maxLength={32}
          name="levikId"
          required
          spellCheck={false}
          type="text"
        />
      </label>
      <button className="button button--danger-ghost" disabled={pending} type="submit">
        <DeleteAccountIcon />
        {pending ? "Готовим подтверждение…" : "Продолжить удаление"}
      </button>
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </form>
  );
}
