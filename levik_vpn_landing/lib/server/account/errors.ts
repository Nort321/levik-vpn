import "server-only";

export class AccountApiError extends Error {
  constructor(
    public readonly code: string,
    public readonly status: number,
    public readonly retryable = false,
  ) {
    super("The account request could not be completed");
    this.name = "AccountApiError";
  }
}

export function isPostgresError(
  error: unknown,
  code: string,
  constraint?: string,
): boolean {
  if (typeof error !== "object" || error === null) {
    return false;
  }
  const candidate = error as { code?: unknown; constraint?: unknown };
  return (
    candidate.code === code &&
    (constraint === undefined || candidate.constraint === constraint)
  );
}
