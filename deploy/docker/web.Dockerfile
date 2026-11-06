# The website: the prerendered site built for DOMAIN, nginx in front of the app,
# and the certificates (web-entrypoint.sh). Build from the repo root:
#
#   docker build --platform linux/amd64 -f deploy/docker/web.Dockerfile \
#       --build-arg DOMAIN=androidmod.site --build-arg APP_COMMIT=$(git rev-parse --short HEAD) .
#
# Linux/amd64 like the app image.

FROM node:22 AS site
WORKDIR /src/client
# The lockfile alone first, so a code change does not reinstall node_modules.
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
# Declared after npm ci, right where they are used: a changed ARG invalidates
# every later RUN's cache, and these change with every deploy.
ARG DOMAIN=androidmod.site
# No .git in the build context, so the commit the bundle reports comes from here.
ARG APP_COMMIT=unknown
RUN VITE_SITE_HOST=$DOMAIN APP_COMMIT=$APP_COMMIT npm run build

FROM nginx:stable-alpine

# The base image's default.conf would answer on port 80 alongside ours.
RUN apk add --no-cache certbot openssl bash gettext \
    && rm /etc/nginx/conf.d/default.conf \
    && mkdir -p /etc/nginx/snippets /var/www/certbot

COPY --from=site /src/client/dist/ /usr/share/nginx/androidmod/
COPY deploy/docker/nginx/http.conf /etc/nginx/conf.d/androidmod-http.conf
COPY deploy/docker/nginx/tls.conf /etc/nginx/snippets/androidmod-tls.conf
COPY deploy/docker/nginx/acme.conf /etc/nginx/snippets/androidmod-acme.conf
# The old host's headers snippet, unchanged: one CSP for both deploys.
COPY deploy/nginx/androidmod-headers.conf /etc/nginx/snippets/androidmod-headers.conf
COPY deploy/docker/nginx/site.conf.template deploy/docker/nginx/www.conf.template \
     deploy/docker/nginx/bootstrap.conf.template deploy/docker/nginx/proxy.conf.template \
     /etc/nginx/androidmod-templates/
COPY deploy/docker/web-entrypoint.sh /usr/local/bin/web-entrypoint
RUN chmod 755 /usr/local/bin/web-entrypoint

# The base image stops nginx with SIGQUIT; the entrypoint is PID 1 here and
# turns TERM into `nginx -s quit` itself.
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/web-entrypoint"]

# Last: every RUN after an ARG sees it, so a new commit declared any higher would
# run `apk add` again on every deploy.
ARG APP_COMMIT=unknown
LABEL org.opencontainers.image.revision=$APP_COMMIT
