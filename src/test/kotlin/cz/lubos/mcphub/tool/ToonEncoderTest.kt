package cz.lubos.mcphub.tool

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ToonEncoderTest {

    @Test
    fun `a table is written with its column names once and one line per row`() {
        val response = QueryResponse(
            environment = "ORACLE_TEST_DB",
            readOnly = true,
            columns = listOf("ID", "LABEL"),
            rows = listOf(listOf("1", "Přípojný bod"), listOf("2", null)),
            rowCount = 2,
            notes = listOf("Only the first 2 rows are shown; more rows match this query."),
        )

        assertThat(ToonEncoder.encode(response)).isEqualTo(
            """
            environment: ORACLE_TEST_DB
            readOnly: true
            rowCount: 2
            notes[1]: Only the first 2 rows are shown; more rows match this query.
            rows[2]{ID,LABEL}:
              1,Přípojný bod
              2,null
            """.trimIndent(),
        )
    }

    @Test
    fun `an empty answer still names its columns`() {
        val response = QueryResponse("POSTGRES_TEST_DB", true, listOf("id"), emptyList(), 0, emptyList())

        assertThat(ToonEncoder.encode(response)).endsWith("notes[0]:\nrows[0]{id}:")
    }

    @ParameterizedTest
    @ValueSource(strings = ["a,b", "say \"hi\"", "12:30", "line\nbreak", " leading", "trailing ", "", "null", "true", "back\\slash", "{\"a\":1}"])
    fun `a value that could be misread is quoted and reads back exactly`(text: String) {
        val written = ToonEncoder.value(text)

        assertThat(written).startsWith("\"")
        assertThat(ObjectMapper().readValue(written, String::class.java)).isEqualTo(text)
    }

    @Test
    fun `a plain value and a real null are written bare`() {
        assertThat(ToonEncoder.value("Přípojný bod 12.5")).isEqualTo("Přípojný bod 12.5")
        assertThat(ToonEncoder.value(null)).isEqualTo("null")
        assertThat(ToonEncoder.value("null")).isEqualTo("\"null\"")
    }
}
