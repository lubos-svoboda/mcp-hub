package cz.lubos.mcphub

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class McpHubApplicationTests {

	@Value("\${spring.ai.mcp.server.version}")
	lateinit var serverVersion: String

	@Test
	fun contextLoads() {
	}

	@Test
	fun `the MCP server reports the Gradle project version`() {
		// A release such as 0.1.0, a commit after one such as 0.1.0-3-gd2fd529, or 0.0.0-dev.
		assertThat(serverVersion).matches("\\d+\\.\\d+\\.\\d+(-.+)?")
	}

}
