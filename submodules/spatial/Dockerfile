# Multi-stage build for the spatial service.
# Build context is `submodules/` so the `includeBuild("../commons")` composite
# build resolves: both `commons/` and `spatial/` are copied with their relative
# layout intact.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY commons ./commons
COPY spatial ./spatial

WORKDIR /build/spatial
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

# Runtime: JRE only. curl is added so the compose healthcheck can hit /health.
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app

COPY --from=builder /build/spatial/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
