import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { NoteViewer } from "@/components/notes/note-viewer";
import { NotesShell } from "@/components/notes/notes-shell";
import { NOTE_ID_PATTERN } from "@/lib/notes/crypto";

export const metadata: Metadata = {
  title: { absolute: "Секретная заметка — Levik Notes" },
  description: "Зашифрованная одноразовая заметка Levik Notes.",
  robots: { index: false, follow: false, noarchive: true },
};

export default async function NotePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  if (!NOTE_ID_PATTERN.test(id)) notFound();
  return (
    <NotesShell>
      <div className="container notes-reader">
        <NoteViewer id={id} />
      </div>
    </NotesShell>
  );
}
