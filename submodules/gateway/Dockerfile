# Multi-stage build for the gateway service.
# Build context is `submodules/` so the `includeBuild("../commons")` composite
# build resolves: both `commons/` and `gateway/` are copied with their relative
# layout intact.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY commons ./commons
COPY gateway ./gateway

WORKDIR /build/gateway
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

# Runtime: JRE only. curl is added so the compose healthcheck can hit
# /actuator/health ( /health is reverse-proxied to spatial, so it is NOT the
# gateway's own liveness signal ).
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app

COPY --from=builder /build/gateway/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
