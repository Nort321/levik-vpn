import Link from "next/link";

import { Brand } from "@/components/brand";

export function MonitorFooter() {
  return (
    <footer className="site-footer monitor-footer">
      <div className="container site-footer__grid">
        <div>
          <Brand compact href="https://leviknet.com/" />
          <p className="site-footer__about">
            Levik Monitor объясняет, что происходит с интернет-сервисами прямо сейчас.
          </p>
        </div>
        <nav aria-label="Monitor" className="site-footer__links">
          <Link href="https://mon.leviknet.com/">Интернет-пульс</Link>
          <Link href="https://mon.leviknet.com/methodology">Как считаются статусы</Link>
          <Link href="https://leviknet.com/status">Сеть Levik</Link>
          <Link href="https://leviknet.com/legal/privacy">Конфиденциальность</Link>
          <Link href="https://leviknet.com/">Levik VPN</Link>
        </nav>
        <p className="site-footer__copyright">
          © 2026 Levik Monitor
          <span>Независимая диагностика доступности сервисов</span>
        </p>
      </div>
    </footer>
  );
}
