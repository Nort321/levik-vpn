export type ActionState = {
  ok: boolean;
  message?: string;
  fieldErrors?: Record<string, string[]>;
};

export type LoginPollResult =
  | { state: "pending" }
  | { state: "authenticated"; redirectTo: "/dashboard" }
  | { state: "expired"; message: string }
  | { state: "error"; message: string };

export type LoginAttemptView =
  | {
      state: "idle";
      botUsername: string;
    }
  | {
      state: "pending";
      botUsername: string;
      verificationCode: string;
      telegramOpenPath: "/api/auth/telegram/open";
      expiresAt: string;
      expiresLabel: string;
      pollAfterMs: number;
    }
  | {
      state: "expired" | "error";
      botUsername: string;
      message: string;
    };

export type SubscriptionStatus =
  | "active"
  | "limited"
  | "expired"
  | "disabled";

export type DeviceView = {
  id: string;
  name: string;
  platform?: string;
  lastSeenLabel?: string;
  isCurrent?: boolean;
};

export type SubscriptionComponentView = {
  title: string;
  trafficUsedLabel?: string;
  trafficLimitLabel: string;
  trafficPercent?: number;
  deviceLimit: number;
  devices: DeviceView[];
};

export type SubscriptionView = {
  id: string;
  kind: "regular" | "lte" | "multi";
  title: string;
  subtitle: string;
  status: SubscriptionStatus;
  statusLabel: string;
  expiresLabel: string;
  remainingLabel?: string;
  trafficUsedLabel?: string;
  trafficLimitLabel: string;
  trafficPercent?: number;
  deviceLimit: number;
  devices: DeviceView[];
  components?: {
    regular: SubscriptionComponentView;
    mobile: SubscriptionComponentView;
  };
  shieldSupported: boolean;
  shieldEnabled: boolean;
  canRenew: boolean;
  canRotateKey: boolean;
  canConnect: boolean;
};

export type OrderStatus =
  | "pending"
  | "paid"
  | "delivered"
  | "cancelled"
  | "expired"
  | "failed";

export type OrderView = {
  publicId: string;
  title: string;
  createdLabel: string;
  amountLabel: string;
  paymentMethodLabel: string;
  status: OrderStatus;
  statusLabel: string;
  canContinuePayment: boolean;
  paymentPath?: string;
};

export type ReferralView = {
  invitedCount: number;
  rewardedDays: number;
  rewardDescription: string;
  inviteeBenefitDescription: string;
  sharePath: "/api/referrals/share";
};

export type FreeProxyView = {
  state: "available" | "active" | "limit_reached" | "unavailable";
  stateLabel: string;
  description: string;
  deviceLimit?: number;
  rateLimitLabel?: string;
  openPath?: "/api/proxy/open";
};

export type DashboardNotice = {
  tone: "info" | "success" | "warning";
  title: string;
  message: string;
  action?: {
    kind: "activate_trial";
    label: string;
  };
};

export type DashboardView = {
  csrfToken: string;
  viewer: {
    displayName: string;
    telegramUsername?: string;
    photoUrl?: string;
    isAdmin?: boolean;
  };
  summary: {
    activeSubscriptions: number;
    nearestExpiryLabel: string;
    deviceUsage: Array<{
      kind: "regular" | "lte";
      label: string;
      connected: number;
      limit: number;
    }>;
  };
  subscriptions: SubscriptionView[];
  recentOrders: OrderView[];
  referral: ReferralView | null;
  freeProxy: FreeProxyView;
  notices: DashboardNotice[];
};

export type PlanPeriodView = {
  months: 1 | 3 | 6 | 12;
  label: string;
  priceLabel: string;
  effectiveMonthlyLabel?: string;
  savingLabel?: string;
};

export type PlanView = {
  tariffId: string;
  kind: "regular" | "lte" | "multi";
  title: string;
  eyebrow: string;
  description: string;
  trafficLabel: string;
  deviceLimitLabel: string;
  features: string[];
  periods: PlanPeriodView[];
  recommended: boolean;
};

export type AddonView = {
  id: "slot_addon" | "traffic_addon";
  title: string;
  description: string;
  amountLabel: string;
  eligibleSubscriptions: Array<{
    id: string;
    kind: "regular" | "lte" | "multi";
    label: string;
  }>;
};

export type PlansView = {
  csrfToken: string;
  plans: PlanView[];
  addons: AddonView[];
  paymentMethods: Array<{
    id: "sbp" | "crypto";
    label: string;
    description: string;
  }>;
  renewableSubscriptions: Array<{
    id: string;
    label: string;
    kind: "regular" | "lte" | "multi";
    tariffId: string;
  }>;
};

export type OrdersView = {
  csrfToken: string;
  orders: OrderView[];
};

export type SessionView = {
  id: string;
  deviceLabel: string;
  locationLabel?: string;
  lastActiveLabel: string;
  createdLabel: string;
  current: boolean;
};

export type SessionsView = {
  csrfToken: string;
  sessions: SessionView[];
};
