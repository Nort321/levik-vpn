import Link from "next/link";

import { Brand } from "@/components/brand";
import {
  ArrowUpRightIcon,
  DiagnosticIcon,
  PulseIcon,
} from "@/components/icons";

export function MonitorHeader() {
  return (
    <header className="site-header monitor-header">
      <div className="container site-header__inner">
        <div className="monitor-brand">
          <Brand href="https://leviknet.com/" />
          <Link href="https://mon.leviknet.com/">
            <span>Levik</span>
            <strong>Monitor</strong>
          </Link>
        </div>
        <nav aria-label="Навигация Monitor" className="site-nav monitor-nav">
          <Link href="https://mon.leviknet.com/">
            <PulseIcon />
            Интернет-пульс
          </Link>
          <Link href="https://mon.leviknet.com/methodology">Методика</Link>
          <Link href="https://leviknet.com/">
            Levik VPN
            <ArrowUpRightIcon />
          </Link>
        </nav>
        <a className="button button--primary monitor-header__check" href="#my-connection">
          <DiagnosticIcon />
          Проверить соединение
        </a>
      </div>
    </header>
  );
}
