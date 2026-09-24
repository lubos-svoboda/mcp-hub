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
		assertThat(serverVersion).matches("\\d+\\.\\d+\\.\\d+")
	}

}
