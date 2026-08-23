import { z } from "zod";

const deviceName = z.string().trim().min(1).max(120).optional();

export const googleAuthSchema = z
  .object({
    idToken: z.string().min(100).max(16_384),
    nonce: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
    deviceName,
  })
  .strict();

export const passwordAuthSchema = z
  .object({
    levikId: z.string().min(1).max(32),
    password: z.string().min(1).max(1_024),
    deviceName,
  })
  .strict();

export const passwordEnrollmentSchema = z
  .object({
    displayName: z.string().trim().min(1).max(120),
    password: z.string().min(12).max(1_024),
    deviceName,
  })
  .strict();

export const recoveryAuthSchema = z
  .object({
    levikId: z.string().min(1).max(32),
    code: z.string().min(1).max(32),
    deviceName,
  })
  .strict();

export const passkeyAuthenticationOptionsSchema = z
  .object({ levikId: z.string().min(1).max(32).optional() })
  .strict();

export const passkeyAuthenticationVerifySchema = z
  .object({
    ceremonyId: z.string().uuid(),
    response: z.unknown(),
    deviceName,
  })
  .strict();

export const activationCompleteSchema = z
  .object({ code: z.string().min(1).max(32) })
  .strict();

export const identityMutationSchema = z.discriminatedUnion("provider", [
  z
    .object({
      provider: z.literal("google"),
      idToken: z.string().min(100).max(16_384),
      nonce: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
      label: z.string().trim().min(1).max(120).optional(),
    })
    .strict(),
  z
    .object({
      provider: z.literal("password"),
      password: z.string().min(1).max(1_024),
    })
    .strict(),
  z.object({ provider: z.literal("telegram") }).strict(),
]);

export const identityDeleteSchema = z
  .object({ identityId: z.string().uuid() })
  .strict();

export const passkeyRegistrationOptionsSchema = z
  .object({ name: z.string().trim().min(1).max(120) })
  .strict();

export const passkeyRegistrationVerifySchema = z
  .object({
    ceremonyId: z.string().uuid(),
    response: z.unknown(),
    name: z.string().trim().min(1).max(120),
  })
  .strict();

export const passkeyRenameSchema = z
  .object({ name: z.string().trim().min(1).max(120) })
  .strict();

export const emptyObjectSchema = z.object({}).strict();

export const supportCreateSchema = z
  .object({
    category: z.enum(["account", "connection", "subscription", "privacy", "other"]),
    subject: z.string().trim().min(3).max(160),
    message: z.string().trim().min(1).max(8_000),
    diagnostics: z
      .object({
        appVersion: z.string().trim().min(1).max(120).optional(),
        platform: z.string().trim().min(1).max(120).optional(),
        errorCode: z.string().trim().min(1).max(120).optional(),
      })
      .strict()
      .optional(),
  })
  .strict();

export const supportReplySchema = z
  .object({ message: z.string().trim().min(1).max(8_000) })
  .strict();

export const supportStatusMutationSchema = z
  .object({
    status: z.enum([
      "open",
      "waiting_for_support",
      "waiting_for_user",
      "closed",
    ]),
  })
  .strict();

export const deletionConfirmSchema = z
  .object({ confirmationToken: z.string().regex(/^[A-Za-z0-9_-]{43}$/) })
  .strict();
