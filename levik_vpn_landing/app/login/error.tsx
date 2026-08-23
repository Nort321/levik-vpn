"use client";

import Link from "next/link";
import { AlertIcon, RefreshIcon, SupportIcon } from "@/components/icons";

export default function LoginError({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <main className="auth-page" id="main-content">
      <div className="route-error" role="alert">
        <span className="route-error__icon">
          <AlertIcon height={30} width={30} />
        </span>
        <h1>Не удалось открыть вход</h1>
        <p>
          Ни один способ входа не был подтверждён. Подождите несколько секунд и
          попробуйте ещё раз — данные аккаунта не изменены.
        </p>
        <div className="button-row">
          <button className="button button--primary" onClick={reset} type="button">
            <RefreshIcon />
            Повторить
          </button>
          <Link className="button button--quiet" href="https://t.me/leviksupportbot">
            <SupportIcon />
            Дополнительный Telegram-канал
          </Link>
        </div>
      </div>
    </main>
  );
}
