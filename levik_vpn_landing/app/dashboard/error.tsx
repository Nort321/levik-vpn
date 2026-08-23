"use client";

import { AlertIcon, RefreshIcon, SupportIcon } from "@/components/icons";

export default function DashboardError({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="route-error" role="alert">
      <span className="route-error__icon">
        <AlertIcon height={30} width={30} />
      </span>
      <h1>Не удалось загрузить кабинет</h1>
      <p>
        Данные не потеряны. Возможно, связь с сервисом временно недоступна —
        попробуйте ещё раз через несколько секунд.
      </p>
      <div className="button-row">
        <button className="button button--primary" onClick={reset} type="button">
          <RefreshIcon />
          Повторить
        </button>
        <a className="button button--quiet" href="https://t.me/leviksupportbot">
          <SupportIcon />
          Дополнительный Telegram-канал
        </a>
      </div>
    </div>
  );
}
