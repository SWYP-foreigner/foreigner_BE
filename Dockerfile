# syntax=docker/dockerfile:1.7
########## build stage ##########
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle/wrapper ./gradle/wrapper
RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew clean bootJar -x test --no-daemon --scan

########## runtime stage ##########
FROM eclipse-temurin:21-jre
WORKDIR /app

# (1) healthcheck 용 curl 설치
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# JAR 복사
COPY --from=build --chown=10001:0 /workspace/build/libs/*.jar /app/app.jar

# 비루트로 실행
USER 10001

EXPOSE 8080

# (2) 컨테이너 헬스체크: /actuator/health 가 "UP" 이어야 healthy
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar"]
