# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY index.html tsconfig.json vite.config.ts eslint.config.js ./
COPY src/ src/
RUN npm run lint && npm run typecheck && npm test && npm run build

FROM nginxinc/nginx-unprivileged:1.28-alpine AS runtime
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/default.conf.template /etc/nginx/templates/frontend.conf.template
COPY --chmod=0555 nginx/start-frontend.sh /usr/local/bin/start-frontend
COPY --from=build --chown=101:101 /workspace/dist/ /usr/share/nginx/html/
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=6 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
STOPSIGNAL SIGQUIT
ENTRYPOINT ["/usr/local/bin/start-frontend"]
CMD ["nginx", "-g", "daemon off;"]
