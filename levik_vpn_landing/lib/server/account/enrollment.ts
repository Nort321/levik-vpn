import "server-only";

import { writeAuditEventWithClient } from "@/lib/server/audit";
import { createAccount, type AccountRecord } from "@/lib/server/account/model";
import {
  hashPassword,
  setPasswordCredentialWithClient,
} from "@/lib/server/account/password";
import { replaceRecoveryCodesWithClient } from "@/lib/server/account/recovery";
import {
  createAccountSessionWithClient,
  type AccountSessionContext,
  type NewAccountSession,
} from "@/lib/server/account/session";
import { withTransaction } from "@/lib/server/db";

export async function enrollPasswordAccount(input: {
  displayName: string;
  password: string;
  sessionContext: AccountSessionContext;
}): Promise<{
  account: AccountRecord;
  session: NewAccountSession;
  recoveryCodes: string[];
}> {
  // Keep memory-hard work outside the transaction, then commit every durable
  // enrollment artifact atomically so a partial account cannot be exposed.
  const password = await hashPassword(input.password);
  return withTransaction(async (client) => {
    const account = await createAccount(client, input.displayName);
    await setPasswordCredentialWithClient(
      client,
      account.accountId,
      account.levikId,
      password,
    );
    const recoveryCodes = await replaceRecoveryCodesWithClient(
      client,
      account.accountId,
    );
    const session = await createAccountSessionWithClient(
      client,
      account.accountId,
      "password",
      input.sessionContext,
    );
    await writeAuditEventWithClient(client, {
      eventType: "account.enrollment.password",
      outcome: "success",
      accountId: account.accountId,
      metadata: { authMethod: "password" },
    });
    return { account, session, recoveryCodes };
  });
}
