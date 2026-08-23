"use client";

import { useState } from "react";

import { CopyIcon, LockIcon, RefreshIcon, ShieldCheckIcon } from "@/components/icons";
import { ExpiryAccordion } from "@/components/notes/expiry-accordion";
import {
  encryptNote,
  MAX_NOTE_CHARACTERS,
  noteCharacterCount,
} from "@/lib/notes/crypto";

type CreatedNote = {
  url: string;
  expiresAt: string;
};

function responseMessage(payload: unknown): string | null {
  if (
    typeof payload === "object" &&
    payload !== null &&
    "message" in payload &&
    typeof payload.message === "string"
  ) {
    return payload.message;
  }
  return null;
}

export function NoteComposer() {
  const [message, setMessage] = useState("");
  const [expiresInDays, setExpiresInDays] = useState(7);
  const [created, setCreated] = useState<CreatedNote | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [copied, setCopied] = useState(false);
  const characterCount = noteCharacterCount(message);

  async function createNote(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting || characterCount < 1 || characterCount > MAX_NOTE_CHARACTERS) return;
    setSubmitting(true);
    setError(null);
    setCopied(false);
    try {
      const encrypted = await encryptNote(message);
      const response = await fetch("/api/notes", {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: encrypted.id,
          keyCommitment: encrypted.keyCommitment,
          iv: encrypted.iv,
          ciphertext: encrypted.ciphertext,
          expiresInDays,
        }),
      });
      const payload: unknown = await response.json();
      if (!response.ok || typeof payload !== "object" || payload === null || !("expiresAt" in payload) || typeof payload.expiresAt !== "string") {
        throw new Error(responseMessage(payload) ?? "Не удалось создать заметку.");
      }
      setCreated({
        url: `${window.location.origin}/${encrypted.id}#${encrypted.keyFragment}`,
        expiresAt: payload.expiresAt,
      });
      setMessage("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Не удалось создать заметку.");
    } finally {
      setSubmitting(false);
    }
  }

  async function copyLink() {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(created.url);
      setCopied(true);
    } catch {
      setError("Не удалось скопировать автоматически. Выделите ссылку вручную.");
    }
  }

  if (created) {
    return (
      <section aria-live="polite" className="notes-card notes-result">
        <div className="notes-card__icon notes-card__icon--success"><ShieldCheckIcon /></div>
        <p className="eyebrow">Заметка зашифрована</p>
        <h1>Ссылка готова</h1>
        <p className="notes-result__lead">
          Передайте её получателю. После первого чтения зашифрованная заметка будет удалена навсегда.
        </p>
        <div className="notes-link-field">
          <input aria-label="Ссылка на секретную заметку" readOnly type="text" value={created.url} />
          <button className="button button--primary" onClick={() => void copyLink()} type="button">
            <CopyIcon />
            {copied ? "Скопировано" : "Копировать"}
          </button>
        </div>
        <p className="notes-result__expiry">
          Если ссылку не откроют, заметка исчезнет {new Intl.DateTimeFormat("ru-RU", { dateStyle: "long", timeStyle: "short" }).format(new Date(created.expiresAt))}.
        </p>
        <button className="button button--ghost" onClick={() => setCreated(null)} type="button">
          <RefreshIcon />
          Создать ещё одну
        </button>
      </section>
    );
  }

  return (
    <form className="notes-card notes-composer" onSubmit={(event) => void createNote(event)}>
      <div className="notes-composer__heading">
        <div>
          <p className="eyebrow">Одноразовое сообщение</p>
          <h1>Создать секретную заметку</h1>
        </div>
        <span><LockIcon /> AES-256-GCM</span>
      </div>
      <label htmlFor="note-message">Сообщение</label>
      <textarea
        aria-describedby="note-limit"
        autoFocus
        id="note-message"
        onChange={(event) => setMessage(Array.from(event.target.value).slice(0, MAX_NOTE_CHARACTERS).join(""))}
        placeholder="Введите текст, который должен увидеть только получатель…"
        rows={9}
        value={message}
      />
      <div className="notes-composer__meta" id="note-limit">
        <span>До {MAX_NOTE_CHARACTERS.toLocaleString("ru-RU")} символов</span>
        <strong className={characterCount >= MAX_NOTE_CHARACTERS ? "notes-limit" : undefined}>
          {characterCount.toLocaleString("ru-RU")} / {MAX_NOTE_CHARACTERS.toLocaleString("ru-RU")}
        </strong>
      </div>
      <div className="notes-composer__controls">
        <ExpiryAccordion onChange={setExpiresInDays} value={expiresInDays} />
        <button className="button button--primary button--large" disabled={submitting || characterCount < 1} type="submit">
          <LockIcon />
          {submitting ? "Шифруем…" : "Зашифровать и создать ссылку"}
        </button>
      </div>
      {error ? <p className="notes-error" role="alert">{error}</p> : null}
    </form>
  );
}
