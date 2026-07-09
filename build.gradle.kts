plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.spring") version "2.3.20"
    kotlin("plugin.jpa") version "2.3.20"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

ktlint {
    version.set("1.4.1")
}

group = "com.depromeet"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Spring Boot 4.0.5 의 platform 은 testcontainers 코어 2.0.x 만 포함하고
// junit-jupiter / mysql 모듈은 1.21.x 라인이 최신이라 BOM 불일치가 난다.
// 모듈이 따라올 때까지 testcontainers BOM 을 1.21.4 로 명시 고정.
val testcontainersVersion = "1.21.4"
val awsSdkVersion = "2.44.11"

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
        mavenBom("software.amazon.awssdk:bom:$awsSdkVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Spring Boot 4.0.5 / Jackson 3 호환 라인. Swagger UI 없이 /v3/api-docs 만 제공 (UI는 Stoplight Elements 사용)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Discord 슬래시커맨드(admin 접근 #654) 인터랙션의 Ed25519 서명 검증용. raw 32B 공개키를 BouncyCastle 로 다룬다
    // (JDK EdEC raw-key 디코딩이 까다로움). Spring Boot 4.0.5 BOM 미관리라 버전 명시(Maven Central 최신 안정).
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // admin 백오피스 SSR 뷰(로그인·관리 화면). #505 가 옛 dev 백오피스와 함께 제거했고, prod 운영 백오피스(#249)로 재도입.
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // 알림 템플릿 플레이스홀더 치환 (StringSubstitutor). Spring Boot BOM 미관리라 버전 명시 (Maven Central 최신 안정).
    implementation("org.apache.commons:commons-text:1.15.0")

    // SSRF IP-pin: 가드가 검증한 IP 로만 연결하도록 HttpClient5 의 DnsResolver 를 끼운다 (#440).
    // JDK HttpURLConnection 은 IP pin 시 TLS SNI·인증서를 수동 복구해야 해 깨지기 쉬운데(JDK-8144566 등), HttpClient5 는
    // IP 만 바꾸고 SNI·인증서·Host 헤더는 원 도메인을 유지한다. 버전은 Spring Boot BOM 관리(이미 transitive).
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // 옵저버빌리티: Actuator 로 health/metrics 노출 + Micrometer Prometheus 레지스트리로
    // /actuator/prometheus 텍스트 포맷 export. 수집은 EC2 의 Grafana Alloy → Grafana Cloud (별도 PR).
    // 버전은 Spring Boot BOM 이 관리한다.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // 분산 추적: 요청마다 traceId/spanId 생성 → MDC·로그 correlation·응답 body(traceId) 노출 +
    // OTLP 로 export 해 Grafana Cloud Traces(Tempo)에서 trace waterfall·구간 latency 로 본다(#380). 버전은 BOM 관리.
    //
    // Spring Boot 4 는 autoconfigure 를 모듈로 쪼갰다(brave / opentelemetry 분리). tracing 코어만 있으면
    // NoopTracerAutoConfiguration 이 NOOP Tracer 를 등록해 traceId 가 비어 버린다. 실제 Tracer + export 엔 셋이 필요:
    //  - spring-boot-micrometer-tracing-opentelemetry: Spring Boot 의 OpenTelemetry autoconfig
    //  - micrometer-tracing-bridge-otel: micrometer Tracer ↔ OpenTelemetry 구현
    //  - opentelemetry-exporter-otlp: span 을 OTLP 로 export (endpoint 는 application.yml, 운영만 로컬 Alloy 로 보낸다)
    //
    // brave 가 아니라 OTel 을 택한 이유는 PR 본문 참고: 공식(Spring·Grafana)·업계 표준이고(Zipkin exporter 는 deprecate),
    // 우리 스택(Alloy = OTel Collector, Tempo = OTLP native)과 직결된다. 코드는 micrometer 추상(Tracer)에만
    // 의존해(brave 직접 import 0건) 브리지 교체에 애플리케이션·테스트 코드 변경이 없다.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // JDBC 쿼리를 trace span 으로 계측 (DataSource proxy 자동 설정 → SQL 이 trace 의 한 구간으로 보인다).
    // Spring Boot BOM 미관리라 버전 명시(2.2.1, 현재 최신). 2.x 가 Boot 4 대응 라인이나(이슈 #78) 릴리스 노트가 호환을 명시하진 않아 부팅으로 검증한다.
    implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:2.2.1")

    // 에러 트래킹: prod·staging 의 warn+error 로그를 Sentry 이슈로 그룹핑(#608). Spring Boot 4 전용 스타터를 쓴다
    // (jakarta 스타터는 Boot 3 용). 우리 예외는 GlobalExceptionHandler 가 잡아 log.error/warn(.., e) 로 남기고
    // 컨테이너로 안 올라가므로, Sentry MVC 미처리예외 통합이 아니라 logback appender(sentry-logback)가 그 로그
    // 이벤트(예외 동봉)를 캡처하는 게 주 경로다 — 그래서 sentry-logback 을 명시 의존으로 둔다.
    // 트레이싱은 Sentry 가 아니라 기존 OTel→Tempo 가 담당하므로 traces-sample-rate=0(application.yml).
    // Spring Boot BOM 미관리라 버전 명시(8.44.1, Maven Central 최신 안정).
    implementation("io.sentry:sentry-spring-boot-4:8.44.1")
    implementation("io.sentry:sentry-logback:8.44.1")

    // 크롭한 상품 이미지를 S3 에 업로드 (#144). 버전은 BOM 이 관리.
    implementation("software.amazon.awssdk:s3")

    // FCM 푸시 (#242) — Firebase Admin SDK. Spring Boot BOM 미관리라 버전 명시 (Maven Central 최신 안정).
    // 자체 Jackson 2(com.fasterxml) 를 번들하나 우리 Jackson 3(tools.jackson)와 그룹이 달라 공존한다.
    implementation("com.google.firebase:firebase-admin:9.9.0")

    // JJWT - Jackson 2.x 와 3.x 는 그룹 ID 가 달라 공존 가능 (com.fasterxml vs tools.jackson)
    val jjwtVersion = "0.13.0"
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // JDK 21+부터 동적 에이전트 로딩이 제한됨. Mockito(ByteBuddy)가 런타임에 에이전트를 붙이므로 명시적 허용 필요.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
