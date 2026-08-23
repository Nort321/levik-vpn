import { ActionFeedback } from "@/components/account/action-feedback";
import { AccountNav } from "@/components/account/account-nav";
import { PasskeyIcon } from "@/components/account/account-icons";
import { formatAccountDate } from "@/components/account/date-time";
import { PasskeyRegistration } from "@/components/account/passkey-registration";
import { RemoveDeviceIcon, ShieldCheckIcon } from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import {
  getAccountOverview,
  renamePasskeyAction,
  revokePasskeyAction,
} from "@/lib/web/account-actions";

type PasskeysPageProps = {
  searchParams: Promise<{ error?: string; notice?: string }>;
};

export default async function PasskeysPage({ searchParams }: PasskeysPageProps) {
  const [view, params] = await Promise.all([getAccountOverview(), searchParams]);
  const otherRecoveryPaths = view.identities.length + (view.recoveryCodesRemaining > 0 ? 1 : 0);

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">WebAuthn</span>
          <h1>Passkeys</h1>
          <p>
            Вход по биометрии или разблокировке устройства. RP ID строго
            <strong> leviknet.com</strong>; можно добавить несколько passkeys.
          </p>
        </div>
      </header>
      <AccountNav current="/dashboard/passkeys" />
      <ActionFeedback error={params.error} notice={params.notice} />

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Новое устройство</span><h2>Добавить passkey</h2></div>
          <ShieldCheckIcon />
        </div>
        <p className="dashboard-section__copy">
          Дайте понятное название. Приватный ключ останется в защищённом
          хранилище вашего устройства и не передаётся Levik VPN.
        </p>
        <PasskeyRegistration csrfToken={view.csrfToken} />
      </section>

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Зарегистрированы</span><h2>Ваши passkeys</h2></div>
          <span className="session-count">{view.passkeys.length}</span>
        </div>
        {view.passkeys.length > 0 ? (
          <ul className="account-item-list account-item-list--stacked-actions">
            {view.passkeys.map((passkey) => (
              <li key={passkey.credentialId}>
                <span className="account-item-list__icon"><PasskeyIcon /></span>
                <div className="account-item-list__main">
                  <strong>{passkey.name}</strong>
                  <span>Добавлен {formatAccountDate(passkey.createdAt)}</span>
                  <small>Последнее использование: {formatAccountDate(passkey.lastUsedAt)}</small>
                </div>
                <div className="account-item-list__actions">
                  <form action={renamePasskeyAction} className="passkey-rename-form">
                    <input name="csrf" type="hidden" value={view.csrfToken} />
                    <input name="credentialId" type="hidden" value={passkey.credentialId} />
                    <label>
                      <span className="sr-only">Новое название для {passkey.name}</span>
                      <input defaultValue={passkey.name} maxLength={120} minLength={1} name="name" required type="text" />
                    </label>
                    <SubmitButton className="button button--quiet button--compact" pendingText="Сохраняем…">
                      <PasskeyIcon />
                      Переименовать
                    </SubmitButton>
                  </form>
                  {view.passkeys.length > 1 || otherRecoveryPaths > 0 ? (
                    <form action={revokePasskeyAction}>
                      <input name="csrf" type="hidden" value={view.csrfToken} />
                      <input name="credentialId" type="hidden" value={passkey.credentialId} />
                      <SubmitButton className="button button--danger-ghost button--compact" pendingText="Отзываем…">
                        <RemoveDeviceIcon />
                        Отозвать
                      </SubmitButton>
                    </form>
                  ) : (
                    <span className="account-item-list__protected">Последний способ входа</span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        ) : (
          <div className="account-empty-state">
            <PasskeyIcon height={30} width={30} />
            <h2>Passkeys пока нет</h2>
            <p>Добавьте passkey на личном устройстве и сохраните recovery-коды.</p>
          </div>
        )}
      </section>
    </>
  );
}
