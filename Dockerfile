# syntax=docker/dockerfile:1.7
########## runtime stage ##########
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# [핵심] Runner에서 빌드 완료된 JAR 파일을 컨테이너 안으로 복사
# build/libs 경로는 context(.)를 기준으로 찾습니다.
COPY build/libs/*.jar /app/app.jar

USER 10001
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=10 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java","-jar","/app/app.jar"]