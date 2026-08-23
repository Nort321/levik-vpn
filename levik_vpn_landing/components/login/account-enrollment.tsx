"use client";

import type { FormEvent } from "react";
import { useRef, useState } from "react";

import { RecoveryCodeReveal } from "@/components/account/recovery-code-reveal";
import { AccountShieldIcon } from "@/components/account/account-icons";
import { LockIcon } from "@/components/icons";
import {
  accountClientRequest,
  accountErrorMessage,
  isRecord,
  requiredString,
  safeAccountReturnTo,
} from "@/components/login/account-api-client";

type EnrollmentResult = {
  levikId: string;
  recoveryCodes: string[];
};

function fieldValue(formData: FormData, name: string): string {
  const value = formData.get(name);
  return typeof value === "string" ? value : "";
}

function parseEnrollment(value: unknown): EnrollmentResult {
  if (
    !isRecord(value) ||
    value.ok !== true ||
    !isRecord(value.account) ||
    !isRecord(value.session) ||
    typeof value.csrfToken !== "string" ||
    !Array.isArray(value.recoveryCodes)
  ) {
    throw new Error("Invalid enrollment response");
  }
  const accountId = requiredString(value.account.id, 36, 36);
  const levikId = requiredString(value.account.levikId, 3, 32);
  const displayName = requiredString(value.account.displayName, 1, 120);
  const createdAt = requiredString(value.account.createdAt, 20, 40);
  const sessionId = requiredString(value.session.id, 36, 36);
  const expiresAt = requiredString(value.session.expiresAt, 20, 40);
  const csrfToken = requiredString(value.csrfToken, 20, 512);
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(accountId) ||
    !/^LVK-[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){2}$/.test(levikId) ||
    !["active", "suspended", "deletion_pending"].includes(String(value.account.status)) ||
    Number.isNaN(Date.parse(createdAt)) ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(sessionId) ||
    Number.isNaN(Date.parse(expiresAt)) ||
    displayName.trim().length === 0 ||
    csrfToken.length < 20
  ) {
    throw new Error("Invalid enrollment response");
  }
  const recoveryCodes = value.recoveryCodes.map((code) => requiredString(code, 12, 32));
  if (
    recoveryCodes.length < 4 ||
    recoveryCodes.length > 20 ||
    new Set(recoveryCodes).size !== recoveryCodes.length ||
    recoveryCodes.some(
      (code) => !/^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){4}$/.test(code),
    )
  ) {
    throw new Error("Invalid enrollment response");
  }
  return { levikId, recoveryCodes };
}

export function AccountEnrollment({ returnTo }: { returnTo: string }) {
  const [result, setResult] = useState<EnrollmentResult | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submitting = useRef(false);
  const destination = safeAccountReturnTo(returnTo);

  async function enroll(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (submitting.current) return;
    const form = event.currentTarget;
    const formData = new FormData(form);
    const displayName = fieldValue(formData, "displayName").trim();
    const password = fieldValue(formData, "password");
    if (displayName.length < 1 || displayName.length > 120) {
      setError("Введите отображаемое имя длиной до 120 символов.");
      return;
    }
    if ([...password].length < 12) {
      setError("Парольная фраза должна содержать не менее 12 символов.");
      return;
    }
    if (password !== fieldValue(formData, "confirmPassword")) {
      setError("Парольные фразы не совпадают.");
      return;
    }
    submitting.current = true;
    setPending(true);
    setError(null);
    try {
      const response = await accountClientRequest("/auth/password/enroll", {
        method: "POST",
        body: { displayName, password },
      });
      const enrollment = parseEnrollment(response);
      form.reset();
      setResult(enrollment);
    } catch (caught) {
      setError(accountErrorMessage(caught));
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }

  if (result) {
    return (
      <div className="account-enrollment-result">
        <div className="account-enrollment-result__identity" role="status">
          <AccountShieldIcon height={30} width={30} />
          <div>
            <span className="card-kicker">Ваш Levik ID</span>
            <strong>{result.levikId}</strong>
            <p>Сохраните его вместе с recovery-кодами. ID не отправляется на email.</p>
          </div>
        </div>
        <RecoveryCodeReveal
          codes={result.recoveryCodes}
          contextLines={[`Levik ID: ${result.levikId}`]}
          description="Это единственный автоматический показ комплекта. Сервер хранит только хеши; каждый код действует один раз."
          doneLabel="Я сохранил ID и коды — продолжить"
          onDone={() => window.location.assign(destination)}
          title="Сохраните recovery-коды до продолжения"
        />
      </div>
    );
  }

  return (
    <form className="account-form account-enrollment-form" onSubmit={(event) => void enroll(event)}>
      <label>
        <span>Отображаемое имя</span>
        <input
          autoComplete="name"
          disabled={pending}
          maxLength={120}
          minLength={1}
          name="displayName"
          placeholder="Как обращаться к вам в кабинете"
          required
          type="text"
        />
      </label>
      <label>
        <span>Пароль или парольная фраза</span>
        <input
          autoComplete="new-password"
          disabled={pending}
          maxLength={256}
          minLength={12}
          name="password"
          required
          type="password"
        />
      </label>
      <label>
        <span>Повторите парольную фразу</span>
        <input
          autoComplete="new-password"
          disabled={pending}
          maxLength={256}
          minLength={12}
          name="confirmPassword"
          required
          type="password"
        />
      </label>
      <label className="account-checkbox">
        <input disabled={pending} required type="checkbox" />
        <span>
          Я принимаю <a href="/legal/terms">условия</a> и ознакомился с{" "}
          <a href="/legal/privacy">политикой конфиденциальности</a>.
        </span>
      </label>
      <button className="button button--primary button--wide button--large" disabled={pending} type="submit">
        <LockIcon />
        {pending ? "Создаём защищённый аккаунт…" : "Создать Levik Account"}
      </button>
      <p className="account-enrollment-form__note">
        Levik ID создаст сервер. Recovery-коды будут показаны один раз и не
        попадут в URL, email или browser storage.
      </p>
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </form>
  );
}
