from __future__ import annotations

from app.config import load_settings


def main() -> None:
    settings = load_settings()
    if not settings.data.get("brand"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
