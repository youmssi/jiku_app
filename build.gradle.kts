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
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    // Verifies Google ID tokens against Google's JWKS (JIKU-51). Library only —
    // no resource-server starter, so nothing is auto-configured.
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.apache.commons:commons-csv:1.14.1")
    // Error tracking (JIKU-70). Core SDK only — the Spring Boot starter is
    // deliberately avoided so nothing is auto-configured and the reporting path
    // stays behind the ErrorTracker port.
    implementation("io.sentry:sentry:8.16.0")
    // Invoice documents (JIKU-69). A buyer's accounts department cannot process
    // the plain-text receipt the platform issued before this.
    implementation("com.github.librepdf:openpdf:2.2.2")
    // Ticket QR codes as images, for the WhatsApp ticket message (JIKU-143).
    implementation("com.google.zxing:core:3.5.3")
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

// Generates META-INF/build-info.properties so the BuildProperties bean is
// populated at runtime — used by /api/v1/health (JIKU-60) to report the
// running version without hardcoding it anywhere.
springBoot {
    buildInfo()
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The suite now spins up 40+ distinct @SpringBootTest contexts (each with its
    // own Testcontainers Postgres + Hibernate metamodel) in a single test JVM.
    // Without an explicit heap the JVM falls back to a container-percentage
    // default that's comfortably exceeded by the accumulated context cache,
    // producing an OutOfMemoryError partway through the run on the standard
    // GitHub-hosted runner (7GB RAM) — reproducible locally with a small -Xmx.
    maxHeapSize = "4g"
    // application.yaml imports the developer's local .env (spring.config.import),
    // even for test runs. That's convenient for bootRun but makes the test suite
    // non-deterministic: e.g. setting MAIL_TRANSPORT=smtp locally to exercise
    // Mailpit silently switches integration tests onto a real SMTP send instead
    // of the safe no-delivery logging sender they're written against. JVM system
    // properties outrank .env-imported config in Spring's resolution order, so
    // pinning the transports here keeps the suite identical regardless of what's
    // in the developer's environment. Dedicated adapter tests (SmtpEmailSenderTest,
    // MetaCloudWhatsAppSenderTest, ...) construct their senders directly and are
    // unaffected.
    systemProperty("jiku.mail.transport", "log")
    systemProperty("jiku.whatsapp.transport", "log")
    // Gradle's default console reporter only prints "FAILED <location>" for a
    // failing test, dropping the exception message and any stdout — next to
    // useless when a failure only reproduces in CI. Surface both so a failure
    // is diagnosable straight from the CI log, no artifact download needed.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// Rewrites openapi/openapi.json from the running controllers (JIKU-72). The same
// test that verifies the contract produces it, so the two can never disagree about
// formatting. Needs Docker, like any Testcontainers-backed test.
//
//   ./gradlew regenerateOpenApi
//
// Then commit openapi/openapi.json and regenerate the frontend types from it
// (see web/openapi/README.md).
tasks.register<Test>("regenerateOpenApi") {
    group = "documentation"
    description = "Regenerates the committed OpenAPI contract from the controllers."
    // Le plugin jvm-test-suite accroche TOUTE tâche de type Test au cycle `check`,
    // donc `./gradlew build` exécutait cette régénération avant la vérification :
    // le contrat était réécrit puis comparé à lui-même, et le garde-fou ne pouvait
    // jamais échouer. Ce garde ne laisse la tâche agir que si elle est demandée
    // explicitement en ligne de commande, quel que soit ce qui la câble ailleurs.
    onlyIf {
        gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "regenerateOpenApi" }
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.jiku.OpenApiContractTest") }
    systemProperty("jiku.openapi.regenerate", "true")
    systemProperty("jiku.mail.transport", "log")
    systemProperty("jiku.whatsapp.transport", "log")
    maxHeapSize = "2g"
    // The point of the task is to rewrite a file, so it must never be considered
    // up to date on an unchanged input.
    outputs.upToDateWhen { false }
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
