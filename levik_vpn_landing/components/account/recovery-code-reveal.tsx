"use client";

import { useId, useState } from "react";

import { CopyIcon, RecoveryIcon } from "@/components/account/account-icons";
import { CheckIcon } from "@/components/icons";

type RecoveryCodeRevealProps = {
  codes: readonly string[];
  contextLines?: readonly string[];
  description: string;
  doneLabel: string;
  onDone: () => void;
  title: string;
};

export function RecoveryCodeReveal({
  codes,
  contextLines = [],
  description,
  doneLabel,
  onDone,
  title,
}: RecoveryCodeRevealProps) {
  const titleId = useId();
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const exportText = [
    "Levik Account",
    ...contextLines,
    "",
    "Recovery codes (each code works once):",
    ...codes,
    "",
  ].join("\n");

  async function copyCodes(): Promise<void> {
    try {
      await navigator.clipboard.writeText(exportText);
      setCopied(true);
      setError(null);
    } catch {
      setError("Не удалось скопировать автоматически. Сохраните данные вручную.");
    }
  }

  function downloadCodes(): void {
    const url = URL.createObjectURL(
      new Blob([exportText], { type: "text/plain;charset=utf-8" }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "levik-account-recovery-codes.txt";
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  return (
    <section className="recovery-reveal" aria-labelledby={titleId}>
      <span className="card-kicker">Показаны один раз</span>
      <h2 id={titleId}>{title}</h2>
      <p>{description}</p>
      <ol className="recovery-code-list">
        {codes.map((code) => <li key={code}><code>{code}</code></li>)}
      </ol>
      <div className="button-row">
        <button className="button button--primary" onClick={() => void copyCodes()} type="button">
          <CopyIcon />
          {copied ? "Скопировано" : "Скопировать данные"}
        </button>
        <button className="button button--quiet" onClick={downloadCodes} type="button">
          <RecoveryIcon />
          Скачать TXT
        </button>
      </div>
      <button className="button button--quiet recovery-reveal__done" onClick={onDone} type="button">
        <CheckIcon />
        {doneLabel}
      </button>
      {error ? <p className="account-form__error" role="alert">{error}</p> : null}
    </section>
  );
}
