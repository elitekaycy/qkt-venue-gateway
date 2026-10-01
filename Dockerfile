# One qkt-venue-gateway: one venue account, configured by GATEWAY_* variables (README, "Configuration").

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :app:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data gateway \
    && mkdir -p /data && chown gateway /data
COPY --from=build /src/app/build/install/qkt-venue-gateway /opt/qkt-venue-gateway
USER gateway
# Inside a container the gateway listens on every interface; publish the port on the host as you need.
ENV GATEWAY_LISTEN=0.0.0.0:8443 \
    GATEWAY_STATE_DIR=/data
VOLUME /data
EXPOSE 8443
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD \
    token="${GATEWAY_TRADER_TOKEN:-$(cat "$GATEWAY_TRADER_TOKEN_FILE" 2>/dev/null)}" \
    && curl -fsS -o /dev/null -H "Authorization: Bearer $token" "http://127.0.0.1:${GATEWAY_LISTEN##*:}/v1/health"
ENTRYPOINT ["/opt/qkt-venue-gateway/bin/qkt-venue-gateway"]
