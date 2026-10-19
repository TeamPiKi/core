# ────────────────────────────────────────────
# Stage 1: builder — Gradle 로 JAR 빌드
# ────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# 의존성 레이어만 먼저 복사해서 캐시 활용
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN ./gradlew dependencies --no-daemon

# 소스 복사 후 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon

# ────────────────────────────────────────────
# Stage 2: extractor — Layered JAR 분리
# ────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS extractor
WORKDIR /workspace
COPY --from=builder /workspace/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ────────────────────────────────────────────
# Stage 3: runtime — 최소 실행 이미지
# ────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# non-root 유저 생성
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# 레이어 순서대로 복사 (변경 빈도 낮은 것 → 높은 것)
COPY --from=extractor /workspace/dependencies/ ./
COPY --from=extractor /workspace/spring-boot-loader/ ./
COPY --from=extractor /workspace/snapshot-dependencies/ ./
COPY --from=extractor /workspace/application/ ./

USER appuser

# 리슨 포트는 배포가 env PORT 로 넘긴다.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
