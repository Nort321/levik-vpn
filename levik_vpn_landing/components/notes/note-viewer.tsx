"use client";

import Link from "next/link";
import { useEffect, useState } from "react";

import { AlertIcon, EyeIcon, NoteIcon, ShieldCheckIcon } from "@/components/icons";
import { commitmentForFragment, decryptNote } from "@/lib/notes/crypto";

type ViewState =
  | { status: "ready"; fragment: string }
  | { status: "opening"; fragment: string }
  | { status: "opened"; plaintext: string }
  | { status: "error"; message: string };

function errorMessage(payload: unknown): string {
  if (typeof payload === "object" && payload !== null && "message" in payload && typeof payload.message === "string") {
    return payload.message;
  }
  return "Заметка недоступна.";
}

export function NoteViewer({ id }: { id: string }) {
  const [state, setState] = useState<ViewState | null>(null);

  useEffect(() => {
    const fragment = window.location.hash.slice(1);
    setState(fragment ? { status: "ready", fragment } : { status: "error", message: "В ссылке нет ключа расшифровки." });
  }, []);

  async function openNote(fragment: string) {
    setState({ status: "opening", fragment });
    try {
      const keyCommitment = await commitmentForFragment(id, fragment);
      if (!keyCommitment) throw new Error("Ссылка повреждена: ключ расшифровки некорректен.");
      const response = await fetch(`/api/notes/${encodeURIComponent(id)}/consume`, {
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ keyCommitment }),
      });
      const payload: unknown = await response.json();
      if (
        !response.ok ||
        typeof payload !== "object" ||
        payload === null ||
        !("iv" in payload) ||
        typeof payload.iv !== "string" ||
        !("ciphertext" in payload) ||
        typeof payload.ciphertext !== "string"
      ) {
        throw new Error(errorMessage(payload));
      }
      const plaintext = await decryptNote(id, fragment, payload.iv, payload.ciphertext);
      window.history.replaceState(null, "", window.location.pathname);
      setState({ status: "opened", plaintext });
    } catch (caught) {
      setState({ status: "error", message: caught instanceof Error ? caught.message : "Заметка недоступна." });
    }
  }

  if (state?.status === "opened") {
    return (
      <section aria-live="polite" className="notes-card notes-viewer notes-viewer--opened">
        <div className="notes-card__icon notes-card__icon--success"><ShieldCheckIcon /></div>
        <p className="eyebrow">Заметка уничтожена</p>
        <h1>Секретное сообщение</h1>
        <div className="notes-plaintext">{state.plaintext}</div>
        <p>Это единственный просмотр. После обновления страницы сообщение восстановить невозможно.</p>
        <Link className="button button--primary" href="/"><NoteIcon /> Создать свою заметку</Link>
      </section>
    );
  }

  if (state?.status === "error") {
    return (
      <section aria-live="polite" className="notes-card notes-viewer notes-viewer--error">
        <div className="notes-card__icon notes-card__icon--error"><AlertIcon /></div>
        <p className="eyebrow">Доступ закрыт</p>
        <h1>Заметка недоступна</h1>
        <p>{state.message}</p>
        <Link className="button button--primary" href="/"><NoteIcon /> Создать новую заметку</Link>
      </section>
    );
  }

  const opening = state?.status === "opening";
  return (
    <section className="notes-card notes-viewer">
      <div className="notes-card__icon"><EyeIcon /></div>
      <p className="eyebrow">Одноразовый просмотр</p>
      <h1>Вам передали секретную заметку</h1>
      <p>После нажатия зашифрованные данные будут безвозвратно удалены с сервера. Открыть заметку второй раз нельзя.</p>
      <button
        className="button button--primary button--large"
        disabled={!state || opening}
        onClick={() => state && "fragment" in state ? void openNote(state.fragment) : undefined}
        type="button"
      >
        <EyeIcon />
        {opening ? "Открываем…" : "Открыть и уничтожить"}
      </button>
    </section>
  );
}
