FROM amazoncorretto:25-alpine

ARG JAR_FILE=build/libs/*.jar

WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY ${JAR_FILE} app.jar

USER spring:spring
EXPOSE 8000

ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
