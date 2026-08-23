const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("ru-RU", {
  day: "2-digit",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "Europe/Moscow",
});

export function formatAccountDate(value: string | null): string {
  if (!value) return "Ещё не использовался";
  return DATE_TIME_FORMATTER.format(new Date(value));
}
