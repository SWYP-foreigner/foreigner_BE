# ===== Build stage (JDK 21) =====
FROM gradle:8.14.3-jdk21 AS builder
WORKDIR /app

# 캐시 효율을 위해 빌드 스크립트/래퍼 먼저 복사
COPY gradlew .
COPY gradle gradle
COPY settings.gradle .
COPY build.gradle .
RUN chmod +x gradlew
# 플러그인/의존 캐시 워밍업(실패해도 무시 가능) - 선택 사항
# RUN ./gradlew -q help --no-daemon || true

# 소스 마지막에 복사 → 변경이 있을 때만 캐시 무효
COPY src src

# 필요 시 테스트 스킵
RUN ./gradlew clean bootJar -x test --no-daemon

# ===== Runtime stage (경량 JRE 21) =====
FROM eclipse-temurin:21-jre
WORKDIR /app

# 산출물 이름이 바뀌어도 대응되는 와일드카드
COPY --from=builder /app/build/libs/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
