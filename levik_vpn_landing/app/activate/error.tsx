"use client";

import Link from "next/link";

import { AlertIcon, RefreshIcon } from "@/components/icons";

export default function ActivateError({ reset }: { reset: () => void }) {
  return (
    <main className="auth-page" id="main-content">
      <div className="route-error" role="alert">
        <span className="route-error__icon"><AlertIcon height={30} width={30} /></span>
        <h1>Не удалось проверить устройство</h1>
        <p>Запрос не подтверждён. Повторите проверку или создайте новый код в приложении.</p>
        <div className="button-row">
          <button className="button button--primary" onClick={reset} type="button">
            <RefreshIcon />
            Повторить
          </button>
          <Link className="button button--quiet" href="/login">Войти отдельно</Link>
        </div>
      </div>
    </main>
  );
}
