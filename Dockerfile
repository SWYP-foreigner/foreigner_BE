FROM gradle:8.4-jdk17 AS builder

WORKDIR /app

COPY gradlew .
COPY settings.gradle .
COPY build.gradle .
COPY gradle gradle

COPY src src
RUN chmod +x gradlew
RUN ./gradlew clean bootJar --no-daemon

FROM openjdk:21-jdk-slim

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar ./app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
