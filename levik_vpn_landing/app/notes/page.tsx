import type { Metadata } from "next";

import { NoteComposer } from "@/components/notes/note-composer";
import { NotesShell } from "@/components/notes/notes-shell";
import { ClockIcon, LockIcon, ShieldCheckIcon } from "@/components/icons";

export const metadata: Metadata = {
  metadataBase: new URL("https://note.leviknet.com"),
  title: { absolute: "Levik Notes — зашифрованные одноразовые заметки" },
  description: "Бесплатные зашифрованные одноразовые заметки: создайте секретное сообщение, передайте ссылку и удалите запись навсегда после первого прочтения.",
  applicationName: "Levik Notes",
  creator: "Levik",
  alternates: { canonical: "https://note.leviknet.com/" },
  icons: {
    icon: "/assets/levik-shield.png",
    apple: "/assets/levik-shield.png",
  },
  openGraph: {
    type: "website",
    locale: "ru_RU",
    url: "https://note.leviknet.com/",
    siteName: "Levik Notes",
    title: "Levik Notes — секретные сообщения по одноразовой ссылке",
    description: "Зашифруйте заметку в браузере. Сервер не получает ключ, а запись удаляется после первого прочтения.",
  },
  twitter: {
    card: "summary",
    title: "Levik Notes — зашифрованные одноразовые заметки",
    description: "Секретное сообщение по ссылке, которое удаляется после первого прочтения.",
  },
  robots: {
    index: true,
    follow: true,
    googleBot: {
      index: true,
      follow: true,
      "max-image-preview": "large",
      "max-snippet": -1,
      "max-video-preview": -1,
    },
  },
};

const websiteStructuredData = {
  "@context": "https://schema.org",
  "@type": "WebSite",
  name: "Levik Notes",
  alternateName: "Заметки Levik",
  url: "https://note.leviknet.com/",
  description: "Зашифрованные одноразовые заметки, удаляемые после первого прочтения.",
  inLanguage: "ru-RU",
  publisher: {
    "@type": "Organization",
    name: "Levik",
    url: "https://leviknet.com/",
    logo: "https://note.leviknet.com/assets/levik-shield.png",
  },
};

export default function NotesPage() {
  return (
    <NotesShell>
      <script type="application/ld+json">
        {JSON.stringify(websiteStructuredData)}
      </script>
      <div className="container notes-home">
        <section className="notes-intro">
          <p className="eyebrow">Levik Notes</p>
          <h2>Секрет остаётся <strong>между вами</strong></h2>
          <p>Сообщение шифруется прямо в вашем браузере. Сервер хранит только бессмысленный набор байтов и удаляет его после первого чтения.</p>
          <div className="notes-trust">
            <article><LockIcon /><div><strong>AES-256-GCM</strong><span>Современное аутентифицированное шифрование</span></div></article>
            <article><ShieldCheckIcon /><div><strong>Нулевой доступ</strong><span>Ключ никогда не попадает на сервер</span></div></article>
            <article><ClockIcon /><div><strong>До 30 дней</strong><span>Автоудаление, даже если ссылку не открыли</span></div></article>
          </div>
        </section>
        <NoteComposer />
      </div>
      <section className="container notes-how">
        <p className="eyebrow">Как это работает</p>
        <div>
          <article><span>01</span><h3>Напишите</h3><p>До 3 000 символов без регистрации и личных данных.</p></article>
          <article><span>02</span><h3>Передайте ссылку</h3><p>Ключ спрятан после символа # и не отправляется серверу.</p></article>
          <article><span>03</span><h3>Прочитайте один раз</h3><p>Зашифрованная запись удаляется атомарно при открытии.</p></article>
        </div>
      </section>
    </NotesShell>
  );
}
