package com.jiku

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<JikuApplication>().with(TestcontainersConfiguration::class).run(*args)
}
