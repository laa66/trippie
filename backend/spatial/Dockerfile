# Stage 1 - build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

COPY spatial/build.gradle.kts ./spatial/

RUN chmod +x gradlew
RUN ./gradlew :spatial:dependencies --no-daemon

COPY spatial/src ./spatial/src

RUN ./gradlew :spatial:bootJar --no-daemon -x test

# Stage 2 - runtime
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app

COPY --from=builder /app/spatial/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]