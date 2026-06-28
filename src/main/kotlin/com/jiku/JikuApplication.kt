package com.jiku

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

@Modulithic
@SpringBootApplication
class JikuApplication

fun main(args: Array<String>) {
	runApplication<JikuApplication>(*args)
}
