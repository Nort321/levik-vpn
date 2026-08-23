import { Brand } from "@/components/brand";

export default function SignupLoading() {
  return (
    <main className="auth-page" id="main-content">
      <div className="auth-page__header"><Brand /></div>
      <div aria-busy="true" aria-label="Открываем создание аккаунта" className="auth-shell auth-shell--account">
        <div className="skeleton login-skeleton__copy" />
        <div className="skeleton login-skeleton__card" />
        <span className="sr-only">Загрузка…</span>
      </div>
    </main>
  );
}
