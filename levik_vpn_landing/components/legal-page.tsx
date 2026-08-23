import type { ReactNode } from "react";
import Link from "next/link";
import { Brand } from "@/components/brand";
import { ArrowUpRightIcon } from "@/components/icons";

type LegalPageProps = {
  eyebrow: string;
  title: string;
  updatedAt: string;
  homeLabel?: string;
  updatedLabel?: string;
  children: ReactNode;
};

export function LegalPage({
  eyebrow,
  title,
  updatedAt,
  homeLabel = "На главную",
  updatedLabel = "Последнее обновление:",
  children,
}: LegalPageProps) {
  return (
    <main className="legal-page" id="main-content">
      <header className="legal-page__header">
        <Brand />
        <Link className="text-link" href="/">
          {homeLabel}
          <ArrowUpRightIcon />
        </Link>
      </header>
      <article className="legal-document">
        <div className="legal-document__title">
          <span className="section-kicker">{eyebrow}</span>
          <h1>{title}</h1>
          <p>
            {updatedLabel} {updatedAt}
          </p>
        </div>
        <div className="legal-document__body">{children}</div>
      </article>
    </main>
  );
}
