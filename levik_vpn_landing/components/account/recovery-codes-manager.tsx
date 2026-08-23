"use client";

import { useState } from "react";

import { RecoveryIcon } from "@/components/account/account-icons";
import { RecoveryCodeReveal } from "@/components/account/recovery-code-reveal";
import {
  accountClientRequest,
  accountErrorMessage,
  isRecord,
  requiredString,
} from "@/components/login/account-api-client";

function parseCodes(value: unknown): string[] {
  if (!isRecord(value) || value.ok !== true || !Array.isArray(value.codes)) {
    throw new Error("Invalid recovery codes response");
  }
  const codes = value.codes.map((code) => requiredString(code, 12, 32));
  if (codes.length < 4 || codes.length > 20 || new Set(codes).size !== codes.length) {
    throw new Error("Invalid recovery codes response");
  }
  return codes;
}

export function RecoveryCodesManager({ csrfToken }: { csrfToken: string }) {
  const [codes, setCodes] = useState<string[] | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function generateCodes() {
    if (
      !window.confirm(
        "Создать новый комплект? Все прежние recovery-коды сразу перестанут работать.",
      )
    ) {
      return;
    }
    setPending(true);
    setError(null);
    try {
      const response = await accountClientRequest("/recovery-codes", {
        method: "POST",
        body: {},
        csrfToken,
      });
      setCodes(parseCodes(response));
    } catch (caught) {
      setError(accountErrorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  if (codes) {
    return (
      <RecoveryCodeReveal
        codes={codes}
        description="После ухода со страницы получить этот список снова нельзя. Каждый код действует один раз; сервер хранит только его защищённый хеш."
        doneLabel="Я сохранил коды — скрыть список"
        onDone={() => setCodes(null)}
        title="Сохраните новые recovery-коды сейчас"
      />
    );
  }

  return (
    <div>
      <button className="button button--primary" disabled={pending} onClick={() => void generateCodes()} type="button">
        <RecoveryIcon />
        {pending ? "Создаём новый комплект…" : "Перевыпустить recovery-коды"}
      </button>
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </div>
  );
}
