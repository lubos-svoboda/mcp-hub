package cz.lubos.mcphub.query

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryLimitsTest {

    private val settings = HubProperties(
        environments = mapOf(
            "ORACLE_TEST_DB" to EnvironmentProperties(
                description = "Test environment",
                type = EnvironmentType.ORACLE,
                url = "jdbc:oracle:thin:@//localhost:1521/freepdb1",
                defaultRows = 100,
                maxRows = 500,
                defaultTextValueCharacters = 200,
                maxTextValueCharacters = 4_000,
            ),
        ),
    ).resolveSettings().getValue("ORACLE_TEST_DB")

    @Test
    fun `a call asking for nothing gets the environment's defaults`() {
        assertThat(QueryLimits.requested(settings, rows = null, textValueCharacters = null))
            .isEqualTo(QueryLimits(rows = 100, textValueCharacters = 200))
    }

    @Test
    fun `a call may raise or lower both limits within the ceilings`() {
        assertThat(QueryLimits.requested(settings, rows = 400, textValueCharacters = 50))
            .isEqualTo(QueryLimits(rows = 400, textValueCharacters = 50))
    }

    @Test
    fun `a request above a ceiling is lowered to it and says so`() {
        val limits = QueryLimits.requested(settings, rows = 10_000, textValueCharacters = 9_000)

        assertThat(limits.rows).isEqualTo(500)
        assertThat(limits.textValueCharacters).isEqualTo(4_000)
        assertThat(limits.cappedRequests).containsExactly(
            "rows was lowered from 10000 to the ceiling of 500.",
            "textValueCharacters was lowered from 9000 to the ceiling of 4000.",
        )
    }

    @Test
    fun `text value characters of zero ask for the ceiling without a note`() {
        val limits = QueryLimits.requested(settings, rows = null, textValueCharacters = 0)

        assertThat(limits.textValueCharacters).isEqualTo(4_000)
        assertThat(limits.cappedRequests).isEmpty()
    }

    @Test
    fun `a request for no rows or a negative length is refused`() {
        assertThrows<IllegalArgumentException> { QueryLimits.requested(settings, rows = 0, textValueCharacters = null) }
        assertThrows<IllegalArgumentException> { QueryLimits.requested(settings, rows = null, textValueCharacters = -1) }
    }
}
