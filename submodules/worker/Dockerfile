# Multi-stage build for the worker service.
# Build context is `submodules/` so the `includeBuild("../commons")` composite
# build resolves: both `commons/` and `worker/` are copied with their relative
# layout intact.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY commons ./commons
COPY worker ./worker

WORKDIR /build/worker
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

# Runtime: JRE only. curl is added so the compose healthcheck can hit /health.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
 && addgroup -S app && adduser -S -G app app
WORKDIR /app

# app.jar stays root-owned + world-readable; the non-root user only reads it.
COPY --from=builder /build/worker/build/libs/*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
