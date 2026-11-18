# 2026-11-18 CVE-2026-114938 대응: 패치된 Corretto 베이스로 다시 빌드
FROM amazoncorretto:25-alpine AS builder

WORKDIR /src
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM amazoncorretto:25-alpine AS runner

WORKDIR /app
RUN addgroup -S -g 1001 spring && adduser -S -u 1001 spring -G spring
COPY --from=builder /src/build/libs/*.jar app.jar

USER 1001:1001
EXPOSE 8000

ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
