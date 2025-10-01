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

# (헬스체크용) curl 최소 설치
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 필요 시 애플리케이션 포트/엔드포인트 맞추세요
HEALTHCHECK --interval=10s --timeout=3s --retries=12 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# 산출물 이름이 바뀌어도 대응되는 와일드카드
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

EXPOSE 8080
# 비루트 실행(선택) — 권장
RUN useradd -r -u 10001 appuser && chown -R appuser:appuser /app
USER 10001

ENTRYPOINT ["java","-jar","/app/app.jar"]
