package com.depromeet.piki

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

// 인증은 JWT 필터가 직접 한다. 제외하지 않으면 기동마다 쓸 곳 없는 기본 계정이 생기고 그 비밀번호 원문이 로그에 남는다 (#1111).
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class PikiApplication

fun main(args: Array<String>) {
    runApplication<PikiApplication>(*args)
}
