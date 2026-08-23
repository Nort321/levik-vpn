FROM python:3.12-slim@sha256:57cd7c3a7a273101a6485ba99423ee568157882804b1124b4dd04266317710de

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY scripts ./scripts
COPY assets ./assets
COPY config.json ./config.json

RUN mkdir -p /app/assets \
    && if [ ! -f /app/assets/levik-banner.png ]; then python /app/scripts/generate_banner.py /app/assets/levik-banner.png; fi

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -m app.healthcheck

CMD ["python", "-m", "app.main"]
