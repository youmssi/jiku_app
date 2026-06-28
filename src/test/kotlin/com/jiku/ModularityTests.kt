package com.jiku

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Verifies the application's module structure: every package directly under
 * [com.jiku] is treated as an application module, and this test fails the build
 * if any module reaches into another module's internals or a dependency cycle
 * is introduced. This is the architectural gate referenced by the engineering
 * rules; it runs as part of the standard test suite and needs no database.
 */
class ModularityTests {

	private val modules = ApplicationModules.of(JikuApplication::class.java)

	@Test
	fun verifiesModuleStructure() {
		modules.verify()
	}
}
