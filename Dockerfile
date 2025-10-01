# syntax=docker/dockerfile:1.7

########## build stage ##########
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Gradle wrapper/설정 먼저 복사 → 의존성 캐시
COPY gradlew settings.gradle build.gradle ./
COPY gradle/wrapper ./gradle/wrapper
RUN chmod +x ./gradlew
# Gradle 자체 동작 확인(캐시 warm-up 겸용)
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version

# (선택) 의존성만 프리페치: 네트워크/리포 상황에 따라 생략 가능
# RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon || true

# 실제 소스는 마지막에 복사 → 캐시 히트 최대화
COPY src ./src

# Spring Boot fat jar 만들기(테스트 스킵은 선택)
RUN --mount=type=cache,target=/root/.gradle ./gradlew clean bootJar -x test --no-daemon --scan


########## runtime stage ##########
FROM eclipse-temurin:21-jre
WORKDIR /app

# JAR 복사 + 소유권을 한 번에 처리(중복 레이어 방지)
COPY --from=build --chown=10001:0 /workspace/build/libs/*.jar /app/app.jar

# 비루트 실행 (useradd / chown 불필요)
USER 10001

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]