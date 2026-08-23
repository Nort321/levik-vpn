FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS build-deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM build-deps AS builder
WORKDIR /app
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM deps AS migrator
WORKDIR /app
COPY db ./db
COPY scripts ./scripts
USER node
CMD ["node", "scripts/migrate.mjs"]

FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS maintenance-worker
WORKDIR /app
ENV NODE_ENV=production
COPY --chown=node:node scripts/maintenance-worker.mjs ./scripts/
USER node
CMD ["node", "scripts/maintenance-worker.mjs"]

FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS monitor-probe
WORKDIR /app
ENV NODE_ENV=production
COPY --chown=node:node scripts/monitor-probe.mjs ./scripts/
COPY --chown=node:node config/monitor-services.json ./config/
USER node
CMD ["node", "scripts/monitor-probe.mjs"]

FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS geoip-updater
WORKDIR /app
ENV NODE_ENV=production
RUN mkdir -p /var/lib/leviknet/geoip \
  && chown -R node:node /var/lib/leviknet
COPY --chown=node:node scripts/geoip-updater.mjs ./scripts/
USER node
CMD ["node", "scripts/geoip-updater.mjs"]

FROM node:24-alpine@sha256:a0b9bf06e4e6193cf7a0f58816cc935ff8c2a908f81e6f1a95432d679c54fbfd AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV HOSTNAME=0.0.0.0
ENV PORT=3000
RUN addgroup --system --gid 1001 nodejs \
  && adduser --system --uid 1001 nextjs \
  && mkdir -p /var/lib/leviknet/downloads \
  && chown -R nextjs:nodejs /var/lib/leviknet
COPY --from=builder --chown=nextjs:nodejs /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
