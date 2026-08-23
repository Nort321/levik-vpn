import Link from "next/link";
import {
  ArrowUpRightIcon,
  BoltIcon,
  ConnectIcon,
  GlobeIcon,
  LockIcon,
  NetworkScanIcon,
  PlansIcon,
  ShieldCheckIcon,
  SignalIcon,
  SupportIcon,
  TelegramIcon,
} from "@/components/icons";
import { FreeProxyButton } from "@/components/free-proxy-button";
import { LandingVisual } from "@/components/landing-visual";
import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";
import { getOptionalSession } from "@/lib/server/browser-auth";
import { getOptionalAccountOverview } from "@/lib/web/account-actions";

const cases = [
  {
    icon: <SignalIcon />,
    title: "Другой VPN не подключается",
    text: "Когда привычный клиент зависает на подключении, Levik VPN даёт отдельный сценарий для мобильной сети и помогает быстрее вернуться онлайн.",
    featured: true,
  },
  {
    icon: <ShieldCheckIcon />,
    title: "Не открываются привычные сайты",
    text: "Подходит для ситуаций, когда часть сервисов грузится, а нужные сайты, приложения или личные кабинеты внезапно недоступны.",
    featured: false,
  },
  {
    icon: <TelegramIcon />,
    title: "Telegram не открывается",
    text: "Бесплатный Telegram proxy помогает зайти в мессенджер и открыть бота Levik VPN для дальнейшей настройки.",
    featured: false,
  },
  {
    icon: <SignalIcon />,
    title: "Мобильная сеть нестабильна",
    text: "Для дороги, поездок, LTE/4G и публичного Wi‑Fi: подключение остаётся под контролем, а инструкция всегда рядом.",
    featured: false,
  },
  {
    icon: <BoltIcon />,
    title: "Нужно быстро вернуть доступ",
    text: "Когда нет времени разбираться в настройках, понятный сценарий подключения поможет быстрее снова пользоваться интернетом.",
    featured: false,
  },
] as const;

const features = [
  {
    icon: <BoltIcon />,
    title: "Быстрый старт",
    text: "Levik Account и личный кабинет проведут по настройке за несколько понятных шагов.",
  },
  {
    icon: <GlobeIcon />,
    title: "Несколько серверов",
    text: "Разные направления для стабильного подключения: Германия, Швеция, Финляндия и другие локации.",
  },
  {
    icon: <SupportIcon />,
    title: "Поддержка рядом",
    text: "Если подключение не заработало, создайте обращение в кабинете. Telegram остаётся дополнительным каналом.",
  },
] as const;

export default async function HomePage() {
  const [legacySession, account] = await Promise.all([
    getOptionalSession(),
    getOptionalAccountOverview(),
  ]);
  const authenticated = Boolean(legacySession || account);
  const cabinetHref = authenticated ? "/dashboard" : "/login";
  const plansHref = legacySession
    ? "/dashboard/plans"
    : authenticated
      ? "/dashboard"
      : "/login";
  const supportHref = authenticated
    ? "/dashboard/support"
    : "/login?next=%2Fdashboard%2Fsupport";

  return (
    <div className="site-page">
      <SiteHeader authenticated={authenticated} />
      <main id="main-content">
        <section className="container landing-hero">
          <div className="landing-hero__copy">
            <p className="eyebrow">
              <span aria-hidden="true" className="live-dot" />
              Новая Мультиподписка — один ключ для двух режимов
            </p>
            <h1>
              <span>Обычный и мобильный VPN</span>
              <strong>в одной подписке</strong>
            </h1>
            <p className="landing-hero__lead">
              <b>Мультиподписка Levik VPN</b> объединяет безлимитные обычные
              серверы и 50 ГБ мобильного трафика в одном ключе Happ. Остаток
              мобильного лимита всегда виден в приложении.
            </p>
            <div className="button-row">
              <Link
                className="button button--primary button--large"
                href={cabinetHref}
              >
                <LockIcon />
                {authenticated ? "Открыть личный кабинет" : "Войти в личный кабинет"}
              </Link>
              <Link className="button button--ghost button--large" href="https://t.me/levikvpnbot">
                <TelegramIcon />
                Открыть Telegram-бота
              </Link>
              <FreeProxyButton
                className="button button--ghost button--large"
                label="Бесплатный Telegram proxy"
              />
              <Link
                className="button button--ghost button--large"
                href="https://check.leviknet.com/"
              >
                <NetworkScanIcon />
                Проверить мой IP
              </Link>
            </div>
            <dl aria-label="Ключевые преимущества" className="hero-stats">
              <div>
                <dt>1 ключ</dt>
                <dd>обычные и мобильные серверы</dd>
              </div>
              <div>
                <dt>Proxy</dt>
                <dd>вход в Telegram бесплатно</dd>
              </div>
              <div>
                <dt>24/7</dt>
                <dd>помощь с настройкой</dd>
              </div>
            </dl>
          </div>
          <LandingVisual />
        </section>

        <section className="landing-section" id="cases">
          <div className="container">
            <div className="section-heading">
              <span>Когда выручает</span>
              <h2>Интернет вроде есть, но пользоваться им невозможно</h2>
              <p>
                Основной VPN настраивается через Levik Account. Бесплатный proxy
                для Telegram остаётся отдельной опцией, если мессенджер недоступен.
              </p>
            </div>
            <div className="feature-grid feature-grid--cases">
              {cases.map((item) => (
                <article
                  className={item.featured ? "feature-card feature-card--featured" : "feature-card"}
                  key={item.title}
                >
                  <span className="feature-card__icon">{item.icon}</span>
                  <h3>{item.title}</h3>
                  <p>{item.text}</p>
                  {item.featured ? (
                    <div className="comparison">
                      <div className="comparison__item comparison__item--bad">
                        <strong>Обычный VPN</strong>
                        <span>Долго подключается или сбрасывает соединение.</span>
                      </div>
                      <div className="comparison__item comparison__item--good">
                        <strong>Levik VPN LTE</strong>
                        <span>Понятный запуск и помощь, если что-то не получилось.</span>
                      </div>
                    </div>
                  ) : null}
                </article>
              ))}
            </div>
          </div>
        </section>

        <section className="landing-section" id="inside">
          <div className="container">
            <div className="section-heading">
              <span>Что внутри</span>
              <h2>Подключение и управление без лишней сложности</h2>
              <p>
                Выбирайте тариф, оплачивайте, подключайте устройства и следите за
                сроком подписки в кабинете. Telegram-бот остаётся дополнительным инструментом.
              </p>
            </div>
            <div className="feature-grid">
              {features.map((item) => (
                <article className="feature-card" key={item.title}>
                  <span className="feature-card__icon">{item.icon}</span>
                  <h3>{item.title}</h3>
                  <p>{item.text}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section className="landing-section" id="proxy">
          <div className="container proxy-grid">
            <article className="glow-panel proxy-intro">
              <span className="section-kicker">Бесплатно</span>
              <h2>
                Telegram proxy <strong>навсегда</strong>
              </h2>
              <p>
                Заберите бесплатный proxy, чтобы открыть Telegram даже при проблемах
                с подключением. Выдача и управление доступны после безопасного входа.
              </p>
              <FreeProxyButton className="button button--primary button--large" />
            </article>
            <article className="glow-panel steps-panel">
              <ol className="steps-list">
                <li>
                  <span>1</span>
                  <div>
                    <h3>Войдите в Levik Account</h3>
                    <p>Используйте passkey, Google, Levik ID или дополнительную Telegram identity.</p>
                  </div>
                </li>
                <li>
                  <span>2</span>
                  <div>
                    <h3>Выберите нужный продукт</h3>
                    <p>Мультиподписка, отдельный VPN-тариф или бесплатный Telegram proxy.</p>
                  </div>
                </li>
                <li>
                  <span>3</span>
                  <div>
                    <h3>Подключитесь</h3>
                    <p>Следуйте инструкции для вашего устройства — без ручных конфигов.</p>
                  </div>
                </li>
              </ol>
            </article>
          </div>
        </section>

        <section className="landing-section landing-section--plans">
          <div className="container">
            <div className="section-heading">
              <span>Тарифы</span>
              <h2>Один ключ для любой сети или отдельный тариф</h2>
            </div>
            <div className="public-plans">
              <article className="public-plan public-plan--accent">
                <div>
                  <span className="public-plan__tag">Всё в одном</span>
                  <h3>Мультиподписка</h3>
                  <p>
                    Один ключ Happ: безлимит до 5 устройств и мобильные серверы
                    с лимитом 50 ГБ для 1 устройства.
                  </p>
                </div>
                <strong>200 ₽</strong>
                <span>за 1 месяц</span>
              </article>
              <article className="public-plan">
                <div>
                  <span className="public-plan__tag">Основной</span>
                  <h3>Levik VPN</h3>
                  <p>Безлимитный трафик, до 5 устройств.</p>
                </div>
                <strong>от 100 ₽</strong>
                <span>за 1 месяц</span>
              </article>
              <article className="public-plan">
                <div>
                  <span className="public-plan__tag">Мобильный</span>
                  <h3>Levik LTE Plus</h3>
                  <p>80 ГБ трафика и до 2 устройств.</p>
                </div>
                <strong>от 229 ₽</strong>
                <span>за 1 месяц</span>
              </article>
              <article className="public-plan">
                <div>
                  <span className="public-plan__tag">Мобильный</span>
                  <h3>Levik LTE Solo</h3>
                  <p>50 ГБ трафика для одного устройства.</p>
                </div>
                <strong>от 149 ₽</strong>
                <span>за 1 месяц</span>
              </article>
            </div>
            <div className="landing-section__center">
              <Link
                className="button button--ghost button--large"
                href={plansHref}
              >
                <PlansIcon />
                Цены на 1, 3, 6 и 12 месяцев
              </Link>
            </div>
          </div>
        </section>

        <section className="landing-cta">
          <div className="container landing-cta__inner">
            <div>
              <span className="section-kicker">Levik VPN</span>
              <h2>Интернет должен работать тогда, когда он нужен</h2>
              <p>
                Войдите с passkey, Google или Levik ID — email и Telegram для
                доступа к аккаунту не обязательны.
              </p>
            </div>
            <div className="button-row">
              <Link
                className="button button--primary button--large"
                href={cabinetHref}
              >
                <ConnectIcon />
                Перейти в кабинет
              </Link>
              <Link className="button button--ghost button--large" href={supportHref}>
                Web-поддержка
                <ArrowUpRightIcon />
              </Link>
            </div>
          </div>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}
