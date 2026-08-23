function requestHost(request: Request): string {
  return (request.headers.get("x-forwarded-host") ?? request.headers.get("host") ?? "")
    .split(":")[0]
    .toLowerCase();
}

export function GET(request: Request) {
  const host = requestHost(request);
  const checkHost = host === "check.leviknet.com";
  const noteHost = host === "note.leviknet.com";
  const monitorHost = host === "mon.leviknet.com";
  const origin = checkHost
    ? "https://check.leviknet.com"
    : noteHost
      ? "https://note.leviknet.com"
      : monitorHost
        ? "https://mon.leviknet.com"
      : "https://leviknet.com";
  const body = [
    "User-agent: *",
    "Allow: /",
    checkHost || noteHost || monitorHost ? "Disallow: /api/" : "Disallow: /dashboard/",
    checkHost || noteHost || monitorHost ? "" : "Disallow: /api/",
    `Sitemap: ${origin}/sitemap.xml`,
    "",
  ]
    .filter((line) => line !== "")
    .join("\n");

  return new Response(`${body}\n`, {
    headers: {
      "Cache-Control": "public, max-age=3600",
      "Content-Type": "text/plain; charset=utf-8",
    },
  });
}
