import Link from "next/link";
import { ArrowUpRightIcon, DashboardIcon, PulseIcon } from "@/components/icons";
import { Brand } from "@/components/brand";

type SiteHeaderProps = {
  authenticated: boolean;
};

export function SiteHeader({ authenticated }: SiteHeaderProps) {
  const cabinetHref = authenticated ? "/dashboard" : "/login";

  return (
    <header className="site-header">
      <div className="container site-header__inner">
        <Brand />
        <nav aria-label="Навигация по странице" className="site-nav">
          <Link href="/#cases">Когда нужно</Link>
          <Link href="/#inside">Что внутри</Link>
          <Link href="/#proxy">Бесплатный proxy</Link>
          <Link href="/downloads">Скачать</Link>
          <Link href="https://check.leviknet.com/">Проверить IP</Link>
          <Link href="https://mon.leviknet.com/"><PulseIcon />Monitor</Link>
          <Link href="/status">Статус серверов</Link>
        </nav>
        <div className="site-header__actions">
          <Link className="button button--ghost site-header__bot" href="https://t.me/levikvpnbot">
            Бот
            <ArrowUpRightIcon />
          </Link>
          <Link className="button button--primary" href={cabinetHref}>
            <DashboardIcon />
            <span className="site-header__cabinet-label">
              {authenticated ? "Открыть кабинет" : "Личный кабинет"}
            </span>
            <span className="site-header__cabinet-short">
              {authenticated ? "Кабинет" : "Войти"}
            </span>
          </Link>
        </div>
      </div>
    </header>
  );
}
