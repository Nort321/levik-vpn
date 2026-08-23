"use client";

import Link from "next/link";

import { AlertIcon, LockIcon, RefreshIcon } from "@/components/icons";

export default function SignupError({ reset }: { reset: () => void }) {
  return (
    <main className="auth-page" id="main-content">
      <div className="route-error" role="alert">
        <span className="route-error__icon"><AlertIcon height={30} width={30} /></span>
        <h1>Не удалось открыть создание аккаунта</h1>
        <p>Аккаунт не создан и данные не изменены. Повторите запрос позже.</p>
        <div className="button-row">
          <button className="button button--primary" onClick={reset} type="button">
            <RefreshIcon />
            Повторить
          </button>
          <Link className="button button--quiet" href="/login">
            <LockIcon />
            Вернуться ко входу
          </Link>
        </div>
      </div>
    </main>
  );
}
