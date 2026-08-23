import Link from "next/link";
import { Brand } from "@/components/brand";
import {
  ArrowUpRightIcon,
  DashboardIcon,
  GlobeIcon,
  SupportIcon,
} from "@/components/icons";

export default function NotFound() {
  return (
    <main className="not-found-page" id="main-content">
      <div className="not-found-page__header">
        <Brand />
      </div>
      <section className="not-found-card">
        <span className="not-found-card__code">404</span>
        <span className="not-found-card__icon">
          <GlobeIcon height={34} width={34} />
        </span>
        <span className="section-kicker">Страница не найдена</span>
        <h1>Похоже, такой страницы у нас нет</h1>
        <p>
          Адрес мог измениться или ссылка устарела. Вернитесь на главную либо
          откройте личный кабинет.
        </p>
        <div className="button-row">
          <Link className="button button--primary" href="/">
            На главную
            <ArrowUpRightIcon />
          </Link>
          <Link className="button button--quiet" href="/dashboard">
            <DashboardIcon />
            Личный кабинет
          </Link>
          <Link className="button button--quiet" href="/login?next=%2Fdashboard%2Fsupport">
            <SupportIcon />
            Web-поддержка
          </Link>
        </div>
      </section>
    </main>
  );
}
