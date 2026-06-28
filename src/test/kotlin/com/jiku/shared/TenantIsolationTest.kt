package com.jiku.shared

import com.jiku.TestcontainersConfiguration
import jiku.tenantfixture.TenantScopedTestEntity
import jiku.tenantfixture.TenantScopedTestRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the JIKU-6 guarantee: tenant scoping is automatic at the persistence
 * layer. Neither test query contains an explicit `tenant_id` predicate — the
 * filter is applied entirely by Hibernate from [TenantContext].
 *
 * Runs without a surrounding transaction so each repository call opens its own
 * Hibernate session and re-resolves the current tenant; the fixture schema is
 * created by Hibernate for this test only.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@EntityScan(basePackageClasses = [TenantScopedTestEntity::class])
@EnableJpaRepositories(basePackageClasses = [TenantScopedTestRepository::class])
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
class TenantIsolationTest {
    @Autowired
    lateinit var repository: TenantScopedTestRepository

    @AfterEach
    fun cleanUp() {
        listOf("tenant-a", "tenant-b").forEach {
            TenantContext.set(it)
            repository.deleteAll()
        }
        TenantContext.clear()
    }

    @Test
    fun `a row created under one tenant is invisible to another tenant`() {
        TenantContext.set("tenant-a")
        repository.saveAndFlush(TenantScopedTestEntity(name = "tenant-a secret"))

        TenantContext.set("tenant-b")
        assertTrue(repository.findAll().isEmpty(), "tenant B must not see tenant A's row")

        TenantContext.set("tenant-a")
        assertEquals(1, repository.findAll().size, "tenant A must see its own row")
    }

    @Test
    fun `persisting without an active tenant context is rejected`() {
        TenantContext.clear()
        assertThrows<Exception> {
            repository.saveAndFlush(TenantScopedTestEntity(name = "orphan"))
        }
    }
}
