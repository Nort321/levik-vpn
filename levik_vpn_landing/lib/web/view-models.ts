import "server-only";

import { cache } from "react";

import type {
  DashboardView,
  LoginAttemptView,
  OrderStatus,
  OrderView,
  OrdersView,
  PlanPeriodView,
  PlansView,
  SessionView,
  SessionsView,
  SubscriptionStatus,
  SubscriptionComponentView,
  SubscriptionView,
} from "@/components/view-types";
import {
  csrfForSession,
  getLoginBrowserToken,
  requireSession,
} from "@/lib/server/browser-auth";
import {
  requireVpnSession,
  type VpnSession,
} from "@/lib/server/account/bridge-session";
import { isAdminUser } from "@/lib/server/app-updates";
import { synchronizeBridgeEntitlements } from "@/lib/server/account/entitlements";
import {
  type BridgeOrder,
  type BridgeSnapshot,
  getBridgeCatalog,
  getBridgeSnapshot,
} from "@/lib/server/bridge/cabinet";
import { getEphemeralCredential } from "@/lib/server/credential-store";
import {
  decodeSecret,
  hmacBase64Url,
} from "@/lib/server/crypto";
import { getEnvironment } from "@/lib/server/env";
import {
  getLoginAttempt,
  listSessions,
} from "@/lib/server/session-store";

const DATE_FORMATTER = new Intl.DateTimeFormat("ru-RU", {
  day: "numeric",
  month: "long",
  year: "numeric",
  timeZone: "Europe/Moscow",
});

const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ru-RU", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "Europe/Moscow",
});

const ALLOWED_PERIODS = new Set<PlanPeriodView["months"]>([1, 3, 6, 12]);

type AccountContext = {
  session: VpnSession;
  snapshot: BridgeSnapshot;
};

const loadAccountContext = cache(async (): Promise<AccountContext> => {
  const session = await requireVpnSession();
  const snapshot = await getBridgeSnapshot(session.grant);
  if (snapshot.user.userKey !== session.userKey) {
    throw new Error("Secure account identity mismatch");
  }
  if (session.authKind === "account") {
    await synchronizeBridgeEntitlements(
      session.accountId,
      snapshot.subscriptions,
    );
  }
  return { session, snapshot };
});

function formatBytes(bytes: number, zeroLabel = "Безлимит"): string {
  if (bytes <= 0) return zeroLabel;
  const gigabytes = bytes / 1000 ** 3;
  return `${new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: gigabytes >= 10 ? 0 : 1,
  }).format(gigabytes)} ГБ`;
}

function formatRemaining(expireAt: string | null): string | undefined {
  if (!expireAt) return undefined;
  const remainingDays = Math.ceil(
    (new Date(expireAt).getTime() - Date.now()) / (24 * 60 * 60 * 1_000),
  );
  if (remainingDays <= 0) return "Срок завершён";
  return `${remainingDays} ${pluralize(remainingDays, [
    "день",
    "дня",
    "дней",
  ])}`;
}

function pluralize(
  value: number,
  forms: readonly [string, string, string],
): string {
  const absolute = Math.abs(value) % 100;
  const lastDigit = absolute % 10;
  if (absolute > 10 && absolute < 20) return forms[2];
  if (lastDigit > 1 && lastDigit < 5) return forms[1];
  if (lastDigit === 1) return forms[0];
  return forms[2];
}

function subscriptionStatus(status: string): {
  status: SubscriptionStatus;
  label: string;
} {
  switch (status.toLowerCase()) {
    case "active":
      return { status: "active", label: "Активна" };
    case "limited":
      return { status: "limited", label: "Ограничена" };
    case "disabled":
      return { status: "disabled", label: "Отключена" };
    case "expired":
      return { status: "expired", label: "Истекла" };
    default:
      return { status: "disabled", label: "Недоступна" };
  }
}

function subscriptionKind(tariffId: string): SubscriptionView["kind"] {
  const normalized = tariffId.toLowerCase();
  if (normalized === "multi") return "multi";
  return normalized.startsWith("lte") ? "lte" : "regular";
}

function mapSubscriptionComponent(
  title: string,
  component: NonNullable<
    BridgeSnapshot["subscriptions"][number]["components"]
  >["regular"],
): SubscriptionComponentView {
  const trafficPercent =
    component.traffic.limitBytes > 0
      ? Math.min(
          100,
          Math.round(
            (component.traffic.usedBytes / component.traffic.limitBytes) * 100,
          ),
        )
      : undefined;
  return {
    title,
    trafficUsedLabel:
      component.traffic.limitBytes > 0
        ? `использовано ${formatBytes(component.traffic.usedBytes, "0 ГБ")}`
        : undefined,
    trafficLimitLabel: formatBytes(component.traffic.limitBytes),
    trafficPercent,
    deviceLimit: component.devices.limit,
    devices: component.devices.items.map((device) => ({
      id: device.id,
      name: device.label,
    })),
  };
}

function mapSubscription(
  subscription: BridgeSnapshot["subscriptions"][number],
): SubscriptionView {
  const mappedStatus = subscriptionStatus(subscription.status);
  const trafficPercent =
    subscription.traffic.limitBytes > 0
      ? Math.min(
          100,
          Math.round(
            (subscription.traffic.usedBytes /
              subscription.traffic.limitBytes) *
              100,
          ),
        )
      : undefined;
  return {
    id: subscription.uuid,
    kind: subscriptionKind(subscription.tariffId),
    title: subscription.title,
    subtitle:
      subscription.tariffId.toLowerCase() === "multi"
        ? "Безлимитный VPN + 50 ГБ для мобильной сети"
        : subscription.traffic.limitBytes > 0
          ? `${formatBytes(subscription.traffic.limitBytes)} трафика`
          : "Безлимитный трафик",
    status: mappedStatus.status,
    statusLabel: mappedStatus.label,
    expiresLabel: subscription.expireAt
      ? `до ${DATE_FORMATTER.format(new Date(subscription.expireAt))}`
      : "без даты окончания",
    remainingLabel: formatRemaining(subscription.expireAt),
    trafficUsedLabel:
      subscription.traffic.limitBytes > 0
        ? `использовано ${formatBytes(subscription.traffic.usedBytes, "0 ГБ")}`
        : undefined,
    trafficLimitLabel: formatBytes(subscription.traffic.limitBytes),
    trafficPercent,
    deviceLimit: subscription.devices.limit,
    devices: subscription.devices.items.map((device) => ({
      id: device.id,
      name: device.label,
    })),
    components: subscription.components
      ? {
          regular: mapSubscriptionComponent(
            "Обычные серверы",
            subscription.components.regular,
          ),
          mobile: mapSubscriptionComponent(
            "Мобильные серверы",
            subscription.components.mobile,
          ),
        }
      : undefined,
    shieldSupported: subscription.shield.supported,
    shieldEnabled: subscription.shield.enabled,
    canRenew: subscription.actions.renew,
    canRotateKey: subscription.actions.rotateKey,
    canConnect:
      mappedStatus.status === "active" &&
      subscription.subscriptionUrl !== null,
  };
}

function orderStatus(status: string): {
  status: OrderStatus;
  label: string;
} {
  switch (status.toLowerCase()) {
    case "paid":
      return { status: "paid", label: "Оплачен" };
    case "delivered":
      return { status: "delivered", label: "Выдан" };
    case "cancelled":
    case "canceled":
      return { status: "cancelled", label: "Отменён" };
    case "expired":
      return { status: "expired", label: "Истёк" };
    case "failed":
      return { status: "failed", label: "Ошибка" };
    default:
      return { status: "pending", label: "Ожидает оплаты" };
  }
}

function orderTitle(order: BridgeOrder): string {
  switch (order.kind) {
    case "access_purchase":
      return "Новая подписка";
    case "access_renewal":
      return "Продление подписки";
    case "slot_addon":
      return "Дополнительное устройство";
    case "traffic_addon":
      return "Дополнительный трафик";
    default:
      return "Заказ Levik VPN";
  }
}

function paymentMethodLabel(paymentMethodId: string): string {
  switch (paymentMethodId.toLowerCase()) {
    case "sbp":
    case "2":
      return "СБП";
    case "crypto":
    case "13":
      return "Криптовалюта";
    default:
      return "Платёжный провайдер";
  }
}

export function publicOrderId(userKey: string, orderId: number): string {
  return `ord_${hmacBase64Url(
    decodeSecret(getEnvironment().AUDIT_HMAC_KEY),
    `public-order:v1:${userKey}:${orderId}`,
  ).slice(0, 22)}`;
}

function mapOrder(order: BridgeOrder, userKey: string): OrderView {
  const mappedStatus = orderStatus(order.status);
  const id = publicOrderId(userKey, order.id);
  return {
    publicId: id,
    title: orderTitle(order),
    createdLabel: DATE_TIME_FORMATTER.format(new Date(order.createdAt)),
    amountLabel: `${new Intl.NumberFormat("ru-RU").format(order.amountRub)} ₽`,
    paymentMethodLabel: paymentMethodLabel(order.paymentMethodId),
    status: mappedStatus.status,
    statusLabel: mappedStatus.label,
    canContinuePayment:
      mappedStatus.status === "pending" && order.paymentUrl !== null,
    paymentPath:
      mappedStatus.status === "pending" && order.paymentUrl !== null
        ? `/payment/${id}`
        : undefined,
  };
}

export async function getLoginAttemptView(): Promise<LoginAttemptView> {
  const environment = getEnvironment();
  const browserToken = await getLoginBrowserToken();
  if (!browserToken) {
    return {
      state: "idle",
      botUsername: environment.TELEGRAM_BOT_USERNAME,
    };
  }
  const attempt = await getLoginAttempt(browserToken);
  if (!attempt) {
    return {
      state: "expired",
      botUsername: environment.TELEGRAM_BOT_USERNAME,
      message: "Одноразовый код истёк. Создайте новый запрос на вход.",
    };
  }
  if (attempt.provider !== "legacy_bridge") {
    return {
      state: "idle",
      botUsername: environment.TELEGRAM_BOT_USERNAME,
    };
  }
  return {
    state: "pending",
    botUsername: environment.TELEGRAM_BOT_USERNAME,
    verificationCode: attempt.verificationCode,
    telegramOpenPath: "/api/auth/telegram/open",
    expiresAt: attempt.expiresAt.toISOString(),
    expiresLabel: `до ${DATE_TIME_FORMATTER.format(attempt.expiresAt)}`,
    pollAfterMs: attempt.pollIntervalSeconds * 1_000,
  };
}

export async function getDashboardView(): Promise<DashboardView> {
  const { session, snapshot } = await loadAccountContext();
  const subscriptions = snapshot.subscriptions.map(mapSubscription);
  const activeSubscriptions = subscriptions.filter(
    (subscription) => subscription.status === "active",
  );
  const deviceUsage = (["regular", "lte"] as const)
    .map((kind) => {
      const usage = snapshot.subscriptions.reduce(
        (total, subscription) => {
          if (subscription.components) {
            const component =
              kind === "lte"
                ? subscription.components.mobile
                : subscription.components.regular;
            return {
              connected: total.connected + component.devices.used,
              limit: total.limit + component.devices.limit,
            };
          }
          if (subscriptionKind(subscription.tariffId) !== kind) return total;
          return {
            connected: total.connected + subscription.devices.used,
            limit: total.limit + subscription.devices.limit,
          };
        },
        { connected: 0, limit: 0 },
      );
      return {
        kind,
        label: kind === "lte" ? "Мобильный VPN" : "Обычный VPN",
        ...usage,
      };
    })
    .filter((usage) => usage.limit > 0);
  const expiryTimes = snapshot.subscriptions
    .map((subscription) =>
      subscription.expireAt
        ? new Date(subscription.expireAt).getTime()
        : Number.POSITIVE_INFINITY,
    )
    .filter((value) => value > Date.now());
  const storedProxy = await getEphemeralCredential(
    session.userKey,
    "free_proxy",
  );
  const proxyActive = snapshot.freeProxy.active || Boolean(storedProxy);

  return {
    csrfToken: csrfForSession(session),
    viewer: {
      displayName: snapshot.user.userLabel,
      telegramUsername: snapshot.user.telegramUsername,
      photoUrl: snapshot.user.photoUrl,
      isAdmin: isAdminUser(session.userKey),
    },
    summary: {
      activeSubscriptions: activeSubscriptions.length,
      nearestExpiryLabel:
        expiryTimes.length > 0 && Math.min(...expiryTimes) !== Infinity
          ? DATE_FORMATTER.format(new Date(Math.min(...expiryTimes)))
          : "—",
      deviceUsage,
    },
    subscriptions,
    recentOrders: snapshot.orders.map((order) =>
      mapOrder(order, session.userKey),
    ),
    referral: snapshot.referrals
      ? {
          invitedCount: snapshot.referrals.invited,
          rewardedDays:
            snapshot.referrals.rewarded * snapshot.referrals.rewardDays,
          rewardDescription: `После первой оплаты друга вы получите +${snapshot.referrals.rewardDays} дней.`,
          inviteeBenefitDescription: `Друг получит скидку ${snapshot.referrals.discountPercent}% на первую покупку.`,
          sharePath: "/api/referrals/share" as const,
        }
      : null,
    freeProxy: {
      state: proxyActive
        ? "active"
        : snapshot.freeProxy.available
          ? "available"
          : "limit_reached",
      stateLabel: proxyActive
        ? "Proxy готов"
        : snapshot.freeProxy.available
          ? "Можно получить"
          : "Лимит уже использован",
      description: proxyActive
        ? "Ваш персональный proxy готов к открытию в Telegram."
        : snapshot.freeProxy.available
          ? "Получите персональный proxy после защищённого входа."
          : "Новая бесплатная выдача сейчас недоступна.",
      openPath: proxyActive ? "/api/proxy/open" : undefined,
    },
    notices: snapshot.trial.eligible
      ? [
          {
            tone: "info",
            title: "Доступен пробный период",
            message: "Активируйте 3 дня VPN прямо в личном кабинете.",
            action: {
              kind: "activate_trial",
              label: "Активировать пробный доступ",
            },
          },
        ]
      : [],
  };
}

export async function getPlansView(): Promise<PlansView> {
  const { session, snapshot } = await loadAccountContext();
  const catalog = await getBridgeCatalog(session.grant);
  const plans = catalog.tariffs
    .filter((tariff) => tariff.purchaseEnabled)
    .map((tariff) => {
      const kind = subscriptionKind(tariff.id);
      const isMulti = kind === "multi";
      return {
        tariffId: tariff.id,
        kind,
        title: tariff.title,
        eyebrow: isMulti
          ? "Один ключ — два режима"
          : kind === "lte"
            ? "Для мобильной сети"
            : "Универсальный VPN",
        description: tariff.description,
        trafficLabel: isMulti
          ? "Безлимит + 50 ГБ мобильного"
          : formatBytes(tariff.trafficLimitBytes),
        deviceLimitLabel: isMulti
          ? "5 обычных + 1 мобильное"
          : `${tariff.deviceLimit} ${pluralize(
              tariff.deviceLimit,
              ["устройство", "устройства", "устройств"],
            )}`,
        features: isMulti
          ? [
              "Один ключ в Happ для всех серверов",
              "Обычные серверы: безлимит, до 5 устройств",
              "Мобильные серверы: 50 ГБ, 1 устройство",
              "Остаток мобильного трафика виден в Happ",
            ]
          : [
              tariff.trafficLimitBytes > 0
                ? `${formatBytes(tariff.trafficLimitBytes)} включено`
                : "Безлимитный трафик",
              `До ${tariff.deviceLimit} ${pluralize(tariff.deviceLimit, [
                "устройства",
                "устройств",
                "устройств",
              ])}`,
              "Мгновенная выдача после оплаты",
            ],
        periods: tariff.periods
          .filter(
            (
              period,
            ): period is typeof period & {
              months: PlanPeriodView["months"];
            } => ALLOWED_PERIODS.has(period.months as PlanPeriodView["months"]),
          )
          .sort((left, right) => left.months - right.months)
          .map((period) => ({
            months: period.months,
            label: period.title,
            priceLabel: `${period.amountRub} ₽`,
            effectiveMonthlyLabel:
              period.months > 1
                ? `${Math.round(period.amountRub / period.months)} ₽/мес.`
                : undefined,
          })),
        recommended: isMulti,
      };
    });

  return {
    csrfToken: csrfForSession(session),
    plans,
    addons: catalog.addons
      .filter((addon) => addon.enabled)
      .map((addon) => {
        const eligibleSubscriptions = snapshot.subscriptions
          .filter((subscription) =>
            addon.id === "slot_addon"
              ? subscription.actions.slotAddon
              : subscription.actions.trafficAddon,
          )
          .map((subscription) => ({
            id: subscription.uuid,
            kind: subscriptionKind(subscription.tariffId),
            label: subscription.title,
          }));
        const description =
          addon.id === "slot_addon"
            ? `Ещё ${addon.deviceDelta} ${pluralize(addon.deviceDelta, [
                "устройство",
                "устройства",
                "устройств",
              ])} для выбранной подписки. В Мультиподписке слот добавится к обоим режимам, а мобильный лимит вырастет ещё на 10 ГБ.`
            : `${formatBytes(addon.trafficDeltaBytes)} дополнительного трафика для мобильного VPN или мобильной части Мультиподписки.`;
        return {
          id: addon.id,
          title: addon.title,
          description,
          amountLabel: `${addon.amountRub} ₽`,
          eligibleSubscriptions,
        };
      }),
    paymentMethods: catalog.paymentMethods
      .map((method) => {
        const normalized = method.id.toLowerCase();
        const id =
          normalized === "sbp" || normalized === "2"
            ? ("sbp" as const)
            : normalized === "crypto" || normalized === "13"
              ? ("crypto" as const)
              : null;
        return id
          ? {
              id,
              label: paymentMethodLabel(id),
              description:
                method.feePercent > 0
                  ? `Комиссия уже учтена: ${method.feePercent}%`
                  : "Без доплаты на странице оплаты",
            }
          : null;
      })
      .filter((method): method is NonNullable<typeof method> => method !== null),
    renewableSubscriptions: snapshot.subscriptions
      .filter((subscription) => subscription.actions.renew)
      .map((subscription) => ({
        id: subscription.uuid,
        label: subscription.title,
        kind: subscriptionKind(subscription.tariffId),
        tariffId: subscription.tariffId,
      })),
  };
}

export async function getOrdersView(): Promise<OrdersView> {
  const { session, snapshot } = await loadAccountContext();
  return {
    csrfToken: csrfForSession(session),
    orders: snapshot.orders.map((order) => mapOrder(order, session.userKey)),
  };
}

export async function getSessionsView(): Promise<SessionsView> {
  const session = await requireSession();
  const sessions = await listSessions(session);
  return {
    csrfToken: csrfForSession(session),
    sessions: sessions.map(
      (item): SessionView => ({
        id: item.publicId,
        deviceLabel: item.deviceLabel,
        lastActiveLabel: DATE_TIME_FORMATTER.format(item.lastSeenAt),
        createdLabel: DATE_TIME_FORMATTER.format(item.createdAt),
        current: item.current,
      }),
    ),
  };
}

export async function getServerAccountContext(): Promise<AccountContext> {
  return loadAccountContext();
}

export function findOrderByPublicId(
  snapshot: BridgeSnapshot,
  userKey: string,
  requestedPublicId: string,
): BridgeOrder | null {
  return (
    snapshot.orders.find(
      (order) => publicOrderId(userKey, order.id) === requestedPublicId,
    ) ?? null
  );
}
