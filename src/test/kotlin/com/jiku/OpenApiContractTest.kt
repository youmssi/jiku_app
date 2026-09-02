package com.jiku

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JIKU-72: keeps the committed OpenAPI contract honest.
 *
 * The frontend generates its TypeScript types from this document, so a backend
 * change that is not reflected here silently reintroduces the hand-mirrored DTO
 * drift the contract exists to prevent (technical debt R2). Without a check,
 * nothing notices until a field is `undefined` in production.
 *
 * The guard lives in the test suite rather than in a workflow so the existing
 * `./gradlew build` — and therefore the existing CI job — catches it, with no
 * pipeline change required.
 *
 * When this fails, regenerate:
 *
 *     ./gradlew regenerateOpenApi
 *
 * then commit `openapi/openapi.json`, and regenerate the frontend types from it
 * (see `web/openapi/README.md`).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenApiContractTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `the committed contract matches the live one`() {
        val live = liveContract()

        // Writing mode: `./gradlew regenerateOpenApi` sets this so the same code
        // that verifies the contract is the code that produces it — the two can
        // never disagree about formatting.
        if (System.getProperty(REGENERATE) != null) {
            Files.createDirectories(CONTRACT_PATH.parent)
            Files.writeString(CONTRACT_PATH, live)
            return
        }

        assertTrue(
            Files.exists(CONTRACT_PATH),
            "$CONTRACT_PATH is missing. Generate it with ./gradlew regenerateOpenApi",
        )
        assertEquals(
            Files.readString(CONTRACT_PATH).replace("\r\n", "\n").trim(),
            live.trim(),
            "The committed OpenAPI contract is stale. Run ./gradlew regenerateOpenApi, commit " +
                "openapi/openapi.json, and regenerate the frontend types (web/openapi/README.md).",
        )
    }

    @Test
    fun `every versioned endpoint is described`() {
        val paths = MAPPER.readTree(liveContract()).get("paths")

        assertTrue(paths.size() > 0, "The contract describes no endpoints at all")
        // A controller reachable under the API prefix but absent from the document
        // is invisible to the generated client, so the frontend would fall back to
        // a hand-written type for it — exactly the drift this guards against.
        assertTrue(
            paths.propertyNames().asSequence().all { it.startsWith("/api/") },
            "Every documented path should sit under the API prefix; found: " +
                paths
                    .propertyNames()
                    .asSequence()
                    .filterNot { it.startsWith("/api/") }
                    .toList(),
        )
    }

    /**
     * Pretty-printed with sorted keys and a fixed `\n` newline so the committed
     * file has one stable shape everywhere.
     *
     * Both halves matter. Sorted keys because springdoc does not guarantee map
     * ordering, so an unsorted document would look changed on every regeneration.
     * The explicit newline because Jackson's default pretty printer uses the
     * *platform* separator — a contract generated on Windows would then never
     * match one generated on the Linux runner, and this guard would fail in CI
     * for everyone, permanently.
     */
    private fun liveContract(): String {
        val body =
            mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        return MAPPER
            .writer()
            .with(PRINTER)
            .writeValueAsString(MAPPER.readTree(body))
            .replace("\r\n", "\n")
    }

    private companion object {
        const val REGENERATE = "jiku.openapi.regenerate"
        val CONTRACT_PATH: Path = Path.of("openapi", "openapi.json")
        val MAPPER: ObjectMapper =
            JsonMapper
                .builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build()

        /** Two-space indent with an explicit `\n`, so the output is platform-independent. */
        val PRINTER: DefaultPrettyPrinter =
            DefaultPrettyPrinter().apply {
                val indenter = DefaultIndenter("  ", "\n")
                indentObjectsWith(indenter)
                indentArraysWith(indenter)
            }
    }
}
