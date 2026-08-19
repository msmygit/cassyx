# syntax=docker/dockerfile:1.7
#
# cassyx frontend — multi-stage build.
#
#   deps    installed node_modules, cached on package-lock.json alone
#   dev     Vite dev server with HMR (target used by `make dev` / compose profile dev)
#   build   type-check + production bundle
#   runtime nginx serving the static bundle  ← default target
#
# Build the production image:   docker build -t cassyx-frontend .
# Build the dev image:          docker build --target dev -t cassyx-frontend:dev .

# ---------------------------------------------------------------------------- deps
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# `npm ci` needs the lockfile only; this layer is reused until dependencies change.
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

# ----------------------------------------------------------------------------- dev
# Vite HMR target. The source tree is expected to be bind-mounted over /app/src.
FROM node:22-alpine AS dev
WORKDIR /app
ENV NODE_ENV=development
# Bind mounts on macOS/Windows do not deliver inotify events reliably; poll instead.
ENV CASSYX_POLL_WATCH=true
COPY --from=deps /app/node_modules ./node_modules
COPY . .
EXPOSE 5173
# --host is required for the port to be reachable from outside the container.
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "5173"]

# ---------------------------------------------------------------- deps (cross-safe)
# Same install as `deps`, but pinned to the BUILD platform. Kept separate rather
# than pinning `deps` itself, because `dev` needs node_modules native to the
# platform it will RUN on, while `build` only needs them to run the compiler.
FROM --platform=$BUILDPLATFORM node:22-alpine AS deps-build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

# --------------------------------------------------------------------------- build
# Pinned to $BUILDPLATFORM: the release pipeline builds linux/amd64 AND
# linux/arm64, and without this the arm64 leg runs npm/tsc/vite under QEMU on an
# amd64 runner: minutes of emulation to produce a bundle that is pure JS/CSS/HTML
# and therefore byte-identical either way. Only the nginx `runtime` stage below is
# genuinely per-architecture. (esbuild and rollup ship native binaries; emulating
# them is also the flakiest part of a cross build.)
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
ENV NODE_ENV=production
COPY --from=deps-build /app/node_modules ./node_modules
COPY . .
# VITE_* values are baked in at build time. Default is empty = same-origin, which is what the
# runtime image below serves (nginx proxies /api to the backend).
ARG VITE_CASSYX_API_BASE_URL=""
ENV VITE_CASSYX_API_BASE_URL=$VITE_CASSYX_API_BASE_URL
# `npm run build` runs `tsc --noEmit` first, so a type error fails the image build.
RUN npm run build

# ------------------------------------------------------------------------- runtime
FROM nginx:1.27-alpine AS runtime
LABEL org.opencontainers.image.title="cassyx frontend" \
      org.opencontainers.image.source="https://github.com/msmygit/cassyx" \
      org.opencontainers.image.licenses="LicenseRef-cassyx-commercial"

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

# nginx:alpine ships an unprivileged `nginx` user; run as it rather than root.
RUN touch /var/run/nginx.pid \
 && chown -R nginx:nginx /var/run/nginx.pid /var/cache/nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
