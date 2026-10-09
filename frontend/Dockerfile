# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY index.html vite.config.js ./
COPY src/ src/
COPY public/ public/
COPY tests/*.test.js tests/
RUN npm run test --if-present
RUN npm run build

FROM nginx:stable-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/ /usr/share/nginx/html/
# The variable-based upstream does not require a running backend during syntax validation.
RUN nginx -t
EXPOSE 80
