"use server";

import { randomUUID } from "node:crypto";

import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { z } from "zod";

import {
  clearSessionBrowserToken,
  setLoginBrowserToken,
} from "@/lib/server/browser-auth";
import {
  requireVpnSession,
  type VpnSession,
} from "@/lib/server/account/bridge-session";
import { revokeBridgeGrant } from "@/lib/server/bridge/auth";
import {
  activateBridgeTrial,
  claimBridgeFreeProxy,
  createBridgeOrder,
  revokeBridgeDevice,
  rotateBridgeSubscriptionKey,
  setBridgeSubscriptionShield,
  type CreateBridgeOrderInput,
} from "@/lib/server/bridge/cabinet";
import { BridgeError } from "@/lib/server/bridge/core";
import { storeEphemeralCredential } from "@/lib/server/credential-store";
import { getEnvironment } from "@/lib/server/env";
import { beginDeviceLogin } from "@/lib/server/login-service";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import {
  assertCsrfToken,
  assertSameOriginRequest,
  clientAddressFromHeaders,
  RequestSecurityError,
} from "@/lib/server/security";
import {
  markGrantRevocationFailed,
  markGrantRevoked,
  revokeSessions,
} from "@/lib/server/session-store";
import { writeAuditEvent } from "@/lib/server/audit";
import { publicOrderId } from "@/lib/web/view-models";

const uuidSchema = z.string().uuid();
const idempotencyKeySchema = z
  .string()
  .regex(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
const identifierSchema = z.string().regex(/^[A-Za-z0-9_.:-]{1,100}$/);

const createOrderFormSchema = z.discriminatedUnion("kind", [
  z
    .object({
      csrf: z.string().min(20).max(200),
      kind: z.literal("purchase"),
      tariffId: identifierSchema,
      periodMonths: z.coerce.number().int().refine((value) =>
        [1, 3, 6, 12].includes(value),
      ),
      paymentMethod: z.enum(["sbp", "crypto"]),
      idempotencyKey: idempotencyKeySchema,
    })
    .strict(),
  z
    .object({
      csrf: z.string().min(20).max(200),
      kind: z.literal("renewal"),
      tariffId: identifierSchema,
      targetSubscriptionId: uuidSchema,
      periodMonths: z.coerce.number().int().refine((value) =>
        [1, 3, 6, 12].includes(value),
      ),
      paymentMethod: z.enum(["sbp", "crypto"]),
      idempotencyKey: idempotencyKeySchema,
    })
    .strict(),
  z
    .object({
      csrf: z.string().min(20).max(200),
      kind: z.enum(["slot_addon", "traffic_addon"]),
      targetSubscriptionId: uuidSchema,
      paymentMethod: z.enum(["sbp", "crypto"]),
      idempotencyKey: idempotencyKeySchema,
    })
    .strict(),
]);

const deviceMutationSchema = z
  .object({
    csrf: z.string().min(20).max(200),
    subscriptionId: uuidSchema,
    deviceId: z.string().min(1).max(200),
  })
  .strict();

const subscriptionMutationSchema = z
  .object({
    csrf: z.string().min(20).max(200),
    subscriptionId: uuidSchema,
  })
  .strict();

const shieldMutationSchema = z
  .object({
    csrf: z.string().min(20).max(200),
    subscriptionId: uuidSchema,
    enabled: z.enum(["true", "false"]),
  })
  .strict();

const csrfOnlySchema = z
  .object({
    csrf: z.string().min(20).max(200),
  })
  .strict();

const revokeSessionSchema = z
  .object({
    csrf: z.string().min(20).max(200),
    sessionId: uuidSchema,
  })
  .strict();

function boundedFormData(
  formData: FormData,
  allowedFields: ReadonlySet<string>,
): Record<string, string> {
  const result: Record<string, string> = {};
  let entries = 0;
  for (const [key, value] of formData.entries()) {
    entries += 1;
    if (
      entries > 16 ||
      !allowedFields.has(key) ||
      typeof value !== "string" ||
      value.length > 4_096 ||
      Object.hasOwn(result, key)
    ) {
      throw new RequestSecurityError("Invalid form payload", 400);
    }
    result[key] = value;
  }
  return result;
}

async function authorizeMutation(
  formData: FormData,
  options: {
    scope: string;
    limit: number;
    windowSeconds: number;
    csrf: FormDataEntryValue | null;
    includeIpLimit?: boolean;
  },
): Promise<VpnSession> {
  await assertSameOriginRequest();
  const session = await requireVpnSession();
  assertCsrfToken(session.rawToken, options.csrf);
  const requestHeaders = await headers();
  const limits = [
    consumeRateLimit({
      scope: `${options.scope}-user`,
      identifier: session.userKey,
      limit: options.limit,
      windowSeconds: options.windowSeconds,
    }),
  ];
  if (options.includeIpLimit) {
    limits.push(
      consumeRateLimit({
        scope: `${options.scope}-ip`,
        identifier: clientAddressFromHeaders(requestHeaders),
        limit: options.limit,
        windowSeconds: options.windowSeconds,
      }),
    );
  }
  const results = await Promise.all(limits);
  if (results.some((result) => !result.allowed)) {
    throw new RequestSecurityError("Rate limit exceeded", 429);
  }
  return session;
}

async function completeGrantRevocations(
  revocations: Awaited<ReturnType<typeof revokeSessions>>,
): Promise<void> {
  for (const revocation of revocations) {
    try {
      await revokeBridgeGrant(
        revocation.grant,
        revocation.idempotencyKey,
      );
      await markGrantRevoked(revocation.tokenHash);
    } catch (error) {
      await markGrantRevocationFailed(
        revocation.tokenHash,
        error instanceof BridgeError ? error.code : "bridge_unavailable",
      ).catch(() => {});
    }
  }
}

export async function beginTelegramLoginAction(): Promise<void> {
  await assertSameOriginRequest();
  const requestHeaders = await headers();
  const clientAddress = clientAddressFromHeaders(requestHeaders);
  const limit = await consumeRateLimit({
    scope: "auth-start-ip",
    identifier: clientAddress,
    limit: 10,
    windowSeconds: 10 * 60,
  });
  if (!limit.allowed) {
    redirect("/login");
  }

  const attempt = await beginDeviceLogin();
  await setLoginBrowserToken(attempt.browserToken);
  redirect("/login");
}

export async function refreshLoginAttemptAction(): Promise<void> {
  return beginTelegramLoginAction();
}

export async function createOrderAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set([
      "csrf",
      "kind",
      "tariffId",
      "targetSubscriptionId",
      "periodMonths",
      "paymentMethod",
      "idempotencyKey",
    ]),
  );
  const parsed = createOrderFormSchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "order-create",
    limit: 5,
    windowSeconds: 10 * 60,
    csrf: parsed.csrf,
    includeIpLimit: true,
  });
  if (!getEnvironment().FEATURE_PAYMENTS_ENABLED) {
    throw new RequestSecurityError("Payments are temporarily disabled", 503);
  }

  let input: CreateBridgeOrderInput;
  if (parsed.kind === "purchase") {
    input = {
      kind: "access_purchase",
      tariffId: parsed.tariffId,
      months: parsed.periodMonths,
      paymentMethodId: parsed.paymentMethod,
    };
  } else if (parsed.kind === "renewal") {
    input = {
      kind: "access_renewal",
      subscriptionUuid: parsed.targetSubscriptionId,
      tariffId: parsed.tariffId,
      months: parsed.periodMonths,
      paymentMethodId: parsed.paymentMethod,
    };
  } else {
    input = {
      kind: parsed.kind,
      subscriptionUuid: parsed.targetSubscriptionId,
      paymentMethodId: parsed.paymentMethod,
    };
  }

  const order = await createBridgeOrder(
    session.grant,
    input,
    parsed.idempotencyKey,
  );
  await writeAuditEvent({
    eventType: "order.create",
    outcome: "success",
    userKey: session.userKey,
    metadata: { resourceType: order.kind },
  });
  revalidatePath("/dashboard");
  revalidatePath("/dashboard/orders");
  redirect(`/payment/${publicOrderId(session.userKey, order.id)}`);
}

export async function revokeDeviceAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set(["csrf", "subscriptionId", "deviceId"]),
  );
  const parsed = deviceMutationSchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "device-revoke",
    limit: 10,
    windowSeconds: 10 * 60,
    csrf: parsed.csrf,
  });
  if (!getEnvironment().FEATURE_DEVICE_MUTATIONS_ENABLED) {
    throw new RequestSecurityError(
      "Device mutations are temporarily disabled",
      503,
    );
  }
  await revokeBridgeDevice(
    session.grant,
    {
      subscriptionUuid: parsed.subscriptionId,
      deviceId: parsed.deviceId,
    },
    randomUUID(),
  );
  await writeAuditEvent({
    eventType: "device.revoke",
    outcome: "success",
    userKey: session.userKey,
    metadata: { resourceType: "device" },
  });
  revalidatePath("/dashboard/subscriptions");
  revalidatePath("/dashboard");
}

export async function rotateSubscriptionKeyAction(
  formData: FormData,
): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set(["csrf", "subscriptionId"]),
  );
  const parsed = subscriptionMutationSchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "subscription-key-rotate",
    limit: 5,
    windowSeconds: 60 * 60,
    csrf: parsed.csrf,
  });
  if (!getEnvironment().FEATURE_DEVICE_MUTATIONS_ENABLED) {
    throw new RequestSecurityError(
      "Subscription mutations are temporarily disabled",
      503,
    );
  }
  await rotateBridgeSubscriptionKey(
    session.grant,
    parsed.subscriptionId,
    randomUUID(),
  );
  await writeAuditEvent({
    eventType: "subscription.rotate_key",
    outcome: "success",
    userKey: session.userKey,
    metadata: { resourceType: "subscription" },
  });
  revalidatePath("/dashboard/subscriptions");
  revalidatePath("/dashboard/connect");
}

export async function setSubscriptionShieldAction(
  formData: FormData,
): Promise<void> {
  const raw = boundedFormData(
    formData,
    new Set(["csrf", "subscriptionId", "enabled"]),
  );
  const parsed = shieldMutationSchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "subscription-shield",
    limit: 20,
    windowSeconds: 60 * 60,
    csrf: parsed.csrf,
  });
  const enabled = parsed.enabled === "true";
  await setBridgeSubscriptionShield(
    session.grant,
    {
      subscriptionUuid: parsed.subscriptionId,
      enabled,
    },
    randomUUID(),
  );
  await writeAuditEvent({
    eventType: "subscription.shield_change",
    outcome: "success",
    userKey: session.userKey,
    metadata: { resourceType: "subscription", enabled },
  });
  revalidatePath("/dashboard/subscriptions");
  revalidatePath("/dashboard");
}

export async function claimFreeProxyAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf"]));
  const parsed = csrfOnlySchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "free-proxy",
    limit: 3,
    windowSeconds: 24 * 60 * 60,
    csrf: parsed.csrf,
    includeIpLimit: true,
  });
  if (!getEnvironment().FEATURE_FREE_PROXY_ENABLED) {
    throw new RequestSecurityError("Free proxy is temporarily disabled", 503);
  }
  const proxy = await claimBridgeFreeProxy(
    session.grant,
    randomUUID(),
  );
  const expiresAt = new Date(
    Math.min(
      session.grantExpiresAt.getTime(),
      Date.now() + 24 * 60 * 60 * 1_000,
    ),
  );
  await storeEphemeralCredential(
    session.userKey,
    "free_proxy",
    proxy.url,
    expiresAt,
  );
  await writeAuditEvent({
    eventType: "free_proxy.claim",
    outcome: "success",
    userKey: session.userKey,
  });
  revalidatePath("/dashboard");
  redirect("/api/proxy/open");
}

export async function activateTrialAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf"]));
  const parsed = csrfOnlySchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "trial-activate",
    limit: 2,
    windowSeconds: 24 * 60 * 60,
    csrf: parsed.csrf,
    includeIpLimit: true,
  });
  if (!getEnvironment().FEATURE_TRIAL_ENABLED) {
    throw new RequestSecurityError("Trial activation is disabled", 503);
  }
  await activateBridgeTrial(session.grant, randomUUID());
  await writeAuditEvent({
    eventType: "trial.activate",
    outcome: "success",
    userKey: session.userKey,
  });
  revalidatePath("/dashboard");
  revalidatePath("/dashboard/subscriptions");
}

export async function revokeSessionAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf", "sessionId"]));
  const parsed = revokeSessionSchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "web-session-revoke",
    limit: 10,
    windowSeconds: 10 * 60,
    csrf: parsed.csrf,
  });
  if (parsed.sessionId === session.publicId) {
    throw new RequestSecurityError("Use logout for the current session", 400);
  }
  const revoked = await revokeSessions(session, {
    publicId: parsed.sessionId,
  });
  await completeGrantRevocations(revoked);
  await writeAuditEvent({
    eventType: "web_session.revoke",
    outcome: "success",
    userKey: session.userKey,
  });
  revalidatePath("/dashboard/settings");
}

export async function revokeOtherSessionsAction(
  formData: FormData,
): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf"]));
  const parsed = csrfOnlySchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "web-session-revoke",
    limit: 10,
    windowSeconds: 10 * 60,
    csrf: parsed.csrf,
  });
  const revoked = await revokeSessions(session, { others: true });
  await completeGrantRevocations(revoked);
  await writeAuditEvent({
    eventType: "web_session.revoke_others",
    outcome: "success",
    userKey: session.userKey,
  });
  revalidatePath("/dashboard/settings");
}

export async function logoutAction(formData: FormData): Promise<void> {
  const raw = boundedFormData(formData, new Set(["csrf"]));
  const parsed = csrfOnlySchema.parse(raw);
  const session = await authorizeMutation(formData, {
    scope: "logout",
    limit: 10,
    windowSeconds: 10 * 60,
    csrf: parsed.csrf,
  });
  const revoked = await revokeSessions(session, {});
  await clearSessionBrowserToken();
  await completeGrantRevocations(revoked);
  await writeAuditEvent({
    eventType: "auth.logout",
    outcome: "success",
    userKey: session.userKey,
  });
  redirect("/");
}
