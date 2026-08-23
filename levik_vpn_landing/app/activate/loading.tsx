import { Brand } from "@/components/brand";

export default function ActivateLoading() {
  return (
    <main className="auth-page" id="main-content">
      <div className="auth-page__header"><Brand /></div>
      <div aria-busy="true" aria-label="Проверяем запрос устройства" className="activation-shell">
        <div className="skeleton login-skeleton__card" />
        <span className="sr-only">Проверяем код активации…</span>
      </div>
    </main>
  );
}
