package com.depromeet.piki.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container

// 공식 권장: 컨테이너 정의를 별도 보유 클래스로 분리하고 @ImportTestcontainers 로 끌어다 쓴다.
// docs: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
//
// Java 인터페이스 패턴 대신 Kotlin object 를 쓰는 이유:
// Kotlin interface 의 companion object 멤버는 인터페이스 자체의 static field 로 노출되지 않아
// @ImportTestcontainers 의 reflection scan 이 컨테이너를 발견하지 못한다.
// Kotlin object 의 @JvmStatic val 은 진짜 static field 로 노출되어 정상 동작한다.
object IntegrationContainers {
    @Container
    @ServiceConnection
    @JvmStatic
    val MYSQL: MySQLContainer<*> =
        MySQLContainer("mysql:8.4").apply {
            withDatabaseName("piki")
            withUsername("piki")
            withPassword("piki")
        }

    @Container
    @ServiceConnection
    @JvmStatic
    val REDIS: GenericContainer<*> = GenericContainer("redis:8.10-alpine").withExposedPorts(6379)
}
