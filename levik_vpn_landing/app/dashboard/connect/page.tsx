import Link from "next/link";
import { getDashboardView } from "@/lib/web/view-models";
import { EmptyState } from "@/components/dashboard/empty-state";
import {
  CheckIcon,
  ConnectIcon,
  DeviceIcon,
  GlobeIcon,
  PlansIcon,
  ShieldCheckIcon,
  SignalIcon,
} from "@/components/icons";

const platforms = [
  {
    name: "iPhone и iPad",
    label: "iOS / iPadOS",
    icon: <DeviceIcon />,
    steps: [
      "Нажмите кнопку подключения у нужной подписки.",
      "Разрешите открыть рекомендованное приложение.",
      "Добавьте профиль и включите подключение.",
    ],
  },
  {
    name: "Android",
    label: "Телефон и планшет",
    icon: <SignalIcon />,
    steps: [
      "Откройте безопасную ссылку подключения.",
      "Выберите установленный VPN-клиент.",
      "Импортируйте профиль и запустите соединение.",
    ],
  },
  {
    name: "Компьютер",
    label: "Windows / macOS / Linux",
    icon: <GlobeIcon />,
    steps: [
      "Откройте страницу подписки на нужном компьютере.",
      "Установите подходящий клиент из инструкции.",
      "Импортируйте профиль и проверьте подключение.",
    ],
  },
] as const;

export default async function ConnectPage() {
  const view = await getDashboardView();
  const connectable = view.subscriptions.filter(
    (subscription) => subscription.canConnect,
  );

  return (
    <>
      <header className="dashboard-page-header">
        <div>
          <span className="section-kicker">Настройка</span>
          <h1>Подключить устройство</h1>
          <p>Ключ не показывается на странице и передаётся только в приложение.</p>
        </div>
      </header>

      {connectable.length > 0 ? (
        <section className="connect-picker">
          <div>
            <span className="card-kicker">Шаг 1</span>
            <h2>Выберите подписку</h2>
            <p>
              Открывайте ссылку только на устройстве, которое хотите подключить.
            </p>
          </div>
          <div className="connect-picker__options">
            {connectable.map((subscription) => (
              <form action="/api/connect/open" key={subscription.id} method="post">
                <input name="csrf" type="hidden" value={view.csrfToken} />
                <input
                  name="subscriptionId"
                  type="hidden"
                  value={subscription.id}
                />
                <button className="connect-option" type="submit">
                  <span className="connect-option__icon">
                    <ConnectIcon />
                  </span>
                  <span>
                    <strong>{subscription.title}</strong>
                    <small>
                      {subscription.kind === "multi"
                        ? "Один ключ · обычные и мобильные серверы"
                        : `${subscription.devices.length} из ${subscription.deviceLimit} устройств`}
                    </small>
                  </span>
                  <span className="connect-option__action">Открыть</span>
                </button>
              </form>
            ))}
          </div>
        </section>
      ) : (
        <EmptyState
          action={
            <Link className="button button--primary" href="/dashboard/plans">
              <PlansIcon />
              Выбрать тариф
            </Link>
          }
          description="Сначала оформите подписку — затем здесь появится безопасная ссылка подключения."
          icon={<ConnectIcon height={30} width={30} />}
          title="Пока нечего подключать"
        />
      )}

      <section className="dashboard-section">
        <div className="dashboard-section__head">
          <div>
            <span className="card-kicker">Шаг 2</span>
            <h2>Следуйте инструкции для устройства</h2>
          </div>
        </div>
        <div className="platform-grid">
          {platforms.map((platform) => (
            <article className="platform-card" key={platform.name}>
              <span className="platform-card__icon">{platform.icon}</span>
              <span className="card-kicker">{platform.label}</span>
              <h3>{platform.name}</h3>
              <ol>
                {platform.steps.map((step) => (
                  <li key={step}>
                    <CheckIcon />
                    <span>{step}</span>
                  </li>
                ))}
              </ol>
            </article>
          ))}
        </div>
      </section>

      <aside className="security-note">
        <ShieldCheckIcon />
        <p>
          <strong>Не пересылайте страницу подключения другим людям.</strong>
          Если ссылка могла попасть к постороннему, смените ключ в разделе
          «Подписки».
        </p>
      </aside>
    </>
  );
}
