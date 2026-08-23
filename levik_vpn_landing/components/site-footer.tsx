import Link from "next/link";
import { Brand } from "@/components/brand";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="container site-footer__grid">
        <div>
          <Brand compact />
          <p className="site-footer__about">
            Защищённое подключение для обычного и мобильного интернета.
          </p>
        </div>
        <nav aria-label="Документы" className="site-footer__links">
          <Link href="https://check.leviknet.com/">Проверить мой IP</Link>
          <Link href="https://mon.leviknet.com/">Levik Monitor</Link>
          <Link href="https://leviknet.com/">Сайт Levik VPN</Link>
          <Link href="/status">Статус серверов</Link>
          <Link href="/downloads">Скачать приложение</Link>
          <Link href="/login?next=%2Fdashboard%2Fsupport">Web-поддержка</Link>
          <Link href="/legal/privacy">Конфиденциальность</Link>
          <Link href="/legal/terms">Условия использования</Link>
          <Link href="https://t.me/leviksupportbot">Дополнительный Telegram-контакт</Link>
        </nav>
        <p className="site-footer__copyright">
          © 2026 Levik VPN
          <span>Мультиподписка, мобильный VPN и Telegram proxy</span>
        </p>
      </div>
    </footer>
  );
}
