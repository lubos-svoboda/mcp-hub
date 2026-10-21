package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.support.OracleTestDatabase
import cz.lubos.mcphub.support.OracleTestDatabase.CZECH_LABEL
import cz.lubos.mcphub.support.OracleTestDatabase.ENVIRONMENT_NAME
import cz.lubos.mcphub.support.OracleTestDatabase.SAMPLE_TABLE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

@Tag("integration")
class QueryRunnerIT {

    @Test
    fun `every relevant column type is rendered without TO_CHAR or NVL in the query`() {
        val result = runQuery("select label, amount, recorded_at from $SAMPLE_TABLE where id = 1")

        assertThat(result.columns).containsExactly("LABEL", "AMOUNT", "RECORDED_AT")
        val row = result.rows.single()
        assertThat(row[0]).isEqualTo(CZECH_LABEL)

        // More significant digits than a double can hold, so reading it as a number would lose some.
        assertThat(row[1]).startsWith("12345678901234567890.123")

        // Oracle DATE carries no time zone. Read through an instant it would shift by whatever
        // zone the JVM runs in; this is the stored wall-clock value.
        assertThat(row[2]).isEqualTo("2026-03-15T08:00:45")
    }

    /** RAW is a plain value, not a locator, and reading it as a BLOB fails with ORA-17004. */
    @Test
    fun `a binary value is summarised by its size`() {
        val result = runQuery("select hextoraw('0102') as binary_value from dual")

        assertThat(result.rows.single().single()).isEqualTo("<binary, 2 bytes>")
    }

    @Test
    fun `a null column stays null instead of turning into an empty string`() {
        val result = runQuery("select label, amount, recorded_at, note from $SAMPLE_TABLE where id = 2")

        assertThat(result.rows.single()).containsExactly(null, null, null, null)
    }

    /** The marker counts what was left out of the CLOB, which is more than the driver was asked for. */
    @Test
    fun `a long CLOB is shortened to the default and says how much was left out`() {
        val result = runQuery("select note, dbms_lob.getlength(note) as note_length from $SAMPLE_TABLE where id = 1")

        val (note, noteLength) = result.rows.single()
        assertThat(note).isEqualTo(requireNotNull(noteStart()).take(200) + "…(+${noteLength!!.toInt() - 200})")
        assertThat(result.shortenedColumns).containsExactly("NOTE")
    }

    @Test
    fun `a text value is shortened to the length the call asks for`() {
        val result = runQuery("select label from $SAMPLE_TABLE where id = 1", textValueCharacters = 10)

        assertThat(result.rows.single().single()).isEqualTo(CZECH_LABEL.take(10) + "…(+${CZECH_LABEL.length - 10})")
        assertThat(result.shortenedColumns).containsExactly("LABEL")
    }

    @Test
    fun `text value characters of zero read up to the ceiling`() {
        val result = runQuery("select note from $SAMPLE_TABLE where id = 1", maxTextValueCharacters = 1_000, textValueCharacters = 0)

        assertThat(result.rows.single().single()).startsWith(requireNotNull(noteStart()).take(1_000) + "…(+")
    }

    @Test
    fun `a text value within the limit is left whole`() {
        val result = runQuery("select label from $SAMPLE_TABLE where id = 1")

        assertThat(result.rows.single().single()).isEqualTo(CZECH_LABEL)
        assertThat(result.shortenedColumns).isEmpty()
    }

    @Test
    fun `only the row limit comes back and the result says more rows exist`() {
        val result = runQuery("select id from $SAMPLE_TABLE order by id", maxRows = 2)

        assertThat(result.rows).hasSize(2)
        assertThat(result.truncatedAtRowLimit).isTrue()
    }

    @Test
    fun `the call may ask for fewer rows than the default`() {
        val result = runQuery("select id from $SAMPLE_TABLE order by id", rows = 1)

        assertThat(result.rows).containsExactly(listOf("1"))
        assertThat(result.truncatedAtRowLimit).isTrue()
    }

    @Test
    fun `a write hidden inside an anonymous block is refused by the database`() {
        val failure = assertThrows<SQLException> {
            runQuery("begin insert into $SAMPLE_TABLE (id) values (99); end;")
        }

        // ORA-01456: may not perform insert/delete/update operation inside a READ ONLY transaction
        assertThat(failure.message).contains("ORA-01456")
    }

    /**
     * The test database runs on AL32UTF8, which the thin driver handles unaided. A database on a
     * regional character set needs the orai18n library or it refuses every connection with
     * ORA-17056 — a failure this test cannot reproduce.
     */
    @Test
    fun `czech text survives being sent to the database and coming back`() {
        val result = runQuery("select label from $SAMPLE_TABLE where label = '$CZECH_LABEL'")

        assertThat(result.rows.single().single()).isEqualTo(CZECH_LABEL)
    }

    /** A registry per query keeps the tests independent; an empty pool costs almost nothing. */
    private fun runQuery(
        sql: String,
        maxRows: Int = 500,
        maxTextValueCharacters: Int = 4_000,
        rows: Int? = null,
        textValueCharacters: Int? = null,
    ): QueryResult {
        val hubProperties = OracleTestDatabase.hubProperties(maxRows, maxTextValueCharacters)
        return EnvironmentRegistry(hubProperties).use { registry ->
            val environment = requireNotNull(registry.find(ENVIRONMENT_NAME))
            QueryRunner(ReadOnlySession(), ResultMapper())
                .run(environment, sql, QueryLimits.requested(environment.settings, rows, textValueCharacters))
        }
    }

    /** The first 1000 characters, cut by the database itself, so the expected text does not pass through the code under test. */
    private fun noteStart(): String? =
        runQuery("select dbms_lob.substr(note, 1000, 1) as note from $SAMPLE_TABLE where id = 1", textValueCharacters = 0)
            .rows.single().single()
}
