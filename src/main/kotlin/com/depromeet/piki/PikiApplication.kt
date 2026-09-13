package com.depromeet.piki

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PikiApplication

fun main(args: Array<String>) {
    runApplication<PikiApplication>(*args)
}
