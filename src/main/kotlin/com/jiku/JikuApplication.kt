package com.jiku

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

@Modulithic
@SpringBootApplication
@ConfigurationPropertiesScan
class JikuApplication

fun main(args: Array<String>) {
    runApplication<JikuApplication>(*args)
}
