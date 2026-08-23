import "server-only";

import { createDeviceAuthorization } from "@/lib/server/bridge/auth";
import { createLoginAttempt } from "@/lib/server/session-store";

export async function beginDeviceLogin() {
  const authorization = await createDeviceAuthorization();
  return createLoginAttempt(authorization);
}
