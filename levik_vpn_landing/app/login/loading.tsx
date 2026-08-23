import { Brand } from "@/components/brand";

export default function LoginLoading() {
  return (
    <main className="auth-page" id="main-content">
      <div className="auth-page__header">
        <Brand />
      </div>
      <div aria-busy="true" aria-label="Готовим безопасный вход" className="auth-shell">
        <div>
          <div className="skeleton skeleton--title" />
          <div className="skeleton login-skeleton__copy" />
        </div>
        <div className="skeleton login-skeleton__card" />
        <span className="sr-only">Загрузка…</span>
      </div>
    </main>
  );
}
