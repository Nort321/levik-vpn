import { AccountNav } from "@/components/account/account-nav";
import { RecoveryIcon } from "@/components/account/account-icons";
import { RecoveryCodesManager } from "@/components/account/recovery-codes-manager";
import { LockIcon, ShieldCheckIcon } from "@/components/icons";
import { getAccountOverview } from "@/lib/web/account-actions";

export default async function RecoveryPage() {
  const view = await getAccountOverview();
  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Offline fallback</span>
          <h1>Recovery-коды</h1>
          <p>
            Одноразовые высокоэнтропийные коды для входа без email, Google или
            Telegram. Храните их отдельно от устройства с passkey.
          </p>
        </div>
      </header>
      <AccountNav current="/dashboard/recovery" />

      <section className="recovery-summary">
        <span className="recovery-summary__icon"><RecoveryIcon height={34} width={34} /></span>
        <div>
          <span className="card-kicker">Осталось</span>
          <h2>{view.recoveryCodesRemaining} неиспользованных кодов</h2>
          <p>После успешного входа использованный код немедленно инвалидируется.</p>
        </div>
        <strong>{view.recoveryCodesRemaining}</strong>
      </section>

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Новый комплект</span><h2>Перевыпустить коды</h2></div>
          <ShieldCheckIcon />
        </div>
        <p className="dashboard-section__copy">
          Старые коды перестанут работать сразу. Новый список будет показан
          только один раз и не попадёт в URL, журнал браузера или localStorage.
        </p>
        <RecoveryCodesManager csrfToken={view.csrfToken} />
      </section>

      <aside className="account-critical-note">
        <LockIcon />
        <div>
          <strong>Сохраните минимум два независимых способа</strong>
          <p>
            При потере всех identities, passkeys и recovery-кодов поддержка не
            сможет автоматически восстановить аккаунт. Платёж или совпадение
            имени не являются доказательством владения.
          </p>
        </div>
      </aside>
    </>
  );
}
