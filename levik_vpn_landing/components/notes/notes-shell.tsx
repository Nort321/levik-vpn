import Link from "next/link";
import type { ReactNode } from "react";

import { ArrowUpRightIcon, LockIcon } from "@/components/icons";
import { NotesBrand } from "@/components/notes/notes-brand";

export function NotesShell({ children }: { children: ReactNode }) {
  return (
    <div className="notes-page">
      <header className="notes-header">
        <div className="container notes-header__inner">
          <NotesBrand />
          <div className="notes-header__status">
            <span><LockIcon /> End-to-end шифрование</span>
            <Link href="https://leviknet.com/">
              Levik VPN
              <ArrowUpRightIcon />
            </Link>
          </div>
        </div>
      </header>
      <main id="main-content">{children}</main>
      <footer className="notes-footer">
        <div className="container notes-footer__inner">
          <NotesBrand />
          <p>Ключ расшифровки хранится только в ссылке и никогда не передаётся серверу.</p>
          <span>© 2026 Levik</span>
        </div>
      </footer>
    </div>
  );
}
