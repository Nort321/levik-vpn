import { headers } from "next/headers";

import { ActionFeedback } from "@/components/account/action-feedback";
import { AccountNav } from "@/components/account/account-nav";
import { GoogleIcon, IdentityIcon } from "@/components/account/account-icons";
import { formatAccountDate } from "@/components/account/date-time";
import { GoogleIdentityLink } from "@/components/account/google-identity-link";
import { LockIcon, RemoveDeviceIcon, TelegramIcon } from "@/components/icons";
import { SubmitButton } from "@/components/submit-button";
import {
  getAccountOverview,
  linkIdentityAction,
  setPasswordIdentityAction,
  unlinkIdentityAction,
} from "@/lib/web/account-actions";

type IdentitiesPageProps = {
  searchParams: Promise<{ error?: string; notice?: string }>;
};

const PROVIDER_LABELS = {
  google: "Google",
  telegram: "Telegram",
  password: "Levik ID и парольная фраза",
} as const;

function ProviderIcon({ provider }: { provider: keyof typeof PROVIDER_LABELS }) {
  if (provider === "google") return <GoogleIcon />;
  if (provider === "telegram") return <TelegramIcon />;
  return <LockIcon />;
}

export default async function IdentitiesPage({ searchParams }: IdentitiesPageProps) {
  const [view, params, requestHeaders] = await Promise.all([
    getAccountOverview(),
    searchParams,
    headers(),
  ]);
  const providers = new Set(view.identities.map((identity) => identity.provider));
  const recoveryPaths = view.identities.length + view.passkeys.length + (view.recoveryCodesRemaining > 0 ? 1 : 0);

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Identity</span>
          <h1>Способы входа</h1>
          <p>
            Identity отделены от внутреннего account ID. Ничего не объединяется
            автоматически по имени, display name или email.
          </p>
        </div>
      </header>
      <AccountNav current="/dashboard/identities" />
      <ActionFeedback error={params.error} notice={params.notice} />

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Привязаны</span><h2>Ваши identities</h2></div>
          <span className="session-count">{view.identities.length}</span>
        </div>
        {view.identities.length > 0 ? (
          <ul className="account-item-list">
            {view.identities.map((identity) => (
              <li key={identity.id}>
                <span className="account-item-list__icon"><ProviderIcon provider={identity.provider} /></span>
                <div className="account-item-list__main">
                  <strong>{PROVIDER_LABELS[identity.provider]}</strong>
                  <span>
                    {identity.provider === "telegram"
                      ? identity.label
                      : identity.provider === "password"
                        ? `Levik ID ${view.account.levikId}`
                        : "Google ID подтверждён"}
                  </span>
                  <small>
                    Подтверждено {formatAccountDate(identity.verifiedAt)} · последнее использование{" "}
                    {formatAccountDate(identity.lastUsedAt)}
                  </small>
                </div>
                {recoveryPaths > 1 ? (
                  <form action={unlinkIdentityAction}>
                    <input name="csrf" type="hidden" value={view.csrfToken} />
                    <input name="identityId" type="hidden" value={identity.id} />
                    <SubmitButton
                      className="button button--danger-ghost button--compact"
                      pendingText="Отвязываем…"
                    >
                      <RemoveDeviceIcon />
                      Отвязать
                    </SubmitButton>
                  </form>
                ) : (
                  <span className="account-item-list__protected">Последний способ входа</span>
                )}
              </li>
            ))}
          </ul>
        ) : (
          <div className="account-empty-state">
            <IdentityIcon height={30} width={30} />
            <h2>Identity ещё не привязаны</h2>
            <p>Добавьте хотя бы два независимых способа, чтобы не потерять доступ.</p>
          </div>
        )}
      </section>

      <section className="dashboard-section dashboard-section--panel">
        <div className="dashboard-section__head">
          <div><span className="card-kicker">Добавить</span><h2>Новый способ входа</h2></div>
        </div>
        <div className="identity-link-grid">
          {!providers.has("google") ? (
            <GoogleIdentityLink
              csrfToken={view.csrfToken}
              cspNonce={requestHeaders.get("x-nonce") ?? undefined}
            />
          ) : null}
          {!providers.has("telegram") ? (
            <div className="identity-link-provider">
              <span className="identity-link-provider__icon"><TelegramIcon /></span>
              <div>
                <strong>Telegram (дополнительно)</strong>
                <p>Можно отвязать после добавления другого способа. Telegram не является account ID.</p>
              </div>
              <form action={linkIdentityAction}>
                <input name="csrf" type="hidden" value={view.csrfToken} />
                <input name="provider" type="hidden" value="telegram" />
                <SubmitButton className="button button--quiet" pendingText="Связываем…">
                  <TelegramIcon />
                  Привязать
                </SubmitButton>
              </form>
            </div>
          ) : null}
        </div>

        <div className="password-identity-panel">
          <div>
            <span className="password-identity-panel__icon"><LockIcon /></span>
            <div>
              <strong>{providers.has("password") ? "Сменить парольную фразу" : "Добавить парольную фразу"}</strong>
              <p>Не менее 12 символов. Короткий PIN не подходит.</p>
            </div>
          </div>
          <form action={setPasswordIdentityAction} className="account-form account-form--inline">
            <input name="csrf" type="hidden" value={view.csrfToken} />
            <label>
              <span>Новая парольная фраза</span>
              <input autoComplete="new-password" maxLength={256} minLength={12} name="password" required type="password" />
            </label>
            <label>
              <span>Повторите фразу</span>
              <input autoComplete="new-password" maxLength={256} minLength={12} name="confirmPassword" required type="password" />
            </label>
            <SubmitButton className="button button--primary" pendingText="Сохраняем…">
              <LockIcon />
              Сохранить парольную фразу
            </SubmitButton>
          </form>
        </div>
      </section>
    </>
  );
}
