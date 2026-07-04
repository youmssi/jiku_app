plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

group = "com.jiku"
version = "0.0.1-SNAPSHOT"

extra["springModulithVersion"] = "2.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    implementation("org.apache.commons:commons-csv:1.14.1")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Seeds the demo tenant (see DemoDataSeeder) against whatever database the
// POSTGRES_* variables point at, then exits. Safe to re-run: the demo tenant's
// data is reset first. Locally: `docker compose up -d && ./gradlew seedDemoData`.
tasks.register<JavaExec>("seedDemoData") {
    group = "application"
    description = "Seeds the demo tenant with sample events, guests and check-in history."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.jiku.JikuApplicationKt")
    // The seeder starts the full application (its security config needs the servlet
    // web context), seeds, then shuts down. A random port avoids clashing with a
    // bootRun instance that may already hold 8080.
    args("--seed-demo", "--server.port=0")
}

// Code coverage gate. The 70% line-coverage minimum applies to business logic
// (the domain/application code added per story); the application bootstrap and
// module marker packages carry no testable logic and are excluded so the rule
// stays meaningful rather than measuring framework glue.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.jiku.JikuApplication",
                    "com.jiku.JikuApplicationKt",
                )
            }
        }
        verify {
            rule {
                minBound(70)
            }
        }
    }
}
