package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.query.ReadOnlySession
import cz.lubos.mcphub.support.OracleTestDatabase
import cz.lubos.mcphub.support.OracleTestDatabase.ENVIRONMENT_NAME
import cz.lubos.mcphub.support.OracleTestDatabase.SAMPLE_FUNCTION
import cz.lubos.mcphub.support.OracleTestDatabase.SAMPLE_TABLE
import cz.lubos.mcphub.support.OracleTestDatabase.SAMPLE_VIEW
import cz.lubos.mcphub.support.OracleTestDatabase.SCHEMA
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class CatalogReaderIT {

    private val catalogReader = CatalogReader(ReadOnlySession(), OracleCatalog(), PostgresCatalog())

    @Test
    fun `a table is described with its column types, nullability, key and index`() {
        val description = withEnvironment { environment ->
            catalogReader.describeTables(environment, listOf(SAMPLE_TABLE)).single()
        }

        assertThat(description.owner).isEqualTo(SCHEMA)
        assertThat(description.table).isEqualTo(SAMPLE_TABLE)

        // The type is rendered the way it reads in DDL, not as four separate dictionary columns.
        assertThat(description.columns.map { it.name to it.type }).contains(
            "ID" to "NUMBER(10)",
            "LABEL" to "VARCHAR2(400)",
            "AMOUNT" to "NUMBER(38,10)",
            "RECORDED_AT" to "DATE",
            // Declared in characters; data_length alone would report the 40 bytes it may take.
            "CODE" to "VARCHAR2(10 CHAR)",
        )
        assertThat(description.columns.single { it.name == "ID" }.nullable).isFalse()
        assertThat(description.columns.single { it.name == "LABEL" }.nullable).isTrue()

        val primaryKey = description.constraints.single { it.type == "PRIMARY KEY" }
        assertThat(primaryKey.columns).containsExactly("ID")

        assertThat(description.indexes.map { it.name }).contains("SAMPLE_VALUES_LABEL_IX")
    }

    @Test
    fun `a search finds both the table and the function by a fragment of their name`() {
        val matches = withEnvironment { environment ->
            catalogReader.search(environment, "SAMPLE", SchemaObjectKind.entries.toSet(), 100)
        }

        assertThat(matches).anyMatch { it.kind == SchemaObjectKind.TABLE && it.name == SAMPLE_TABLE }
        assertThat(matches).anyMatch { it.kind == SchemaObjectKind.PROGRAM && it.name == SAMPLE_FUNCTION }
        assertThat(matches.map { it.owner }).containsOnly(SCHEMA)
    }

    @Test
    fun `a view is found as a view and never as a table`() {
        val matches = withEnvironment { environment ->
            catalogReader.search(environment, "SAMPLE", SchemaObjectKind.entries.toSet(), 100)
        }

        val view = matches.single { it.name == SAMPLE_VIEW }
        assertThat(view.kind).isEqualTo(SchemaObjectKind.VIEW)
        assertThat(view.detail).isEqualTo("VIEW")
    }

    /** A column is matched on its own name, not on the name of the table holding it. */
    @Test
    fun `a column search reports the table, the column and the rendered type`() {
        val matches = withEnvironment { environment ->
            catalogReader.search(environment, "AMOUNT", setOf(SchemaObjectKind.COLUMN), 100)
        }

        val amount = matches.single { it.name == "$SAMPLE_TABLE.AMOUNT" }
        assertThat(amount.owner).isEqualTo(SCHEMA)
        assertThat(amount.detail).isEqualTo("NUMBER(38,10)")
    }

    /**
     * ROWNUM is assigned before ORDER BY, so a limit placed next to the ordering keeps whichever
     * rows the database read first. The sample table declares ID and LABEL first, AMOUNT and CODE
     * come first alphabetically.
     */
    @Test
    fun `a limited search keeps the first matches in alphabetical order`() {
        val matches = withEnvironment { environment ->
            catalogReader.search(environment, "", setOf(SchemaObjectKind.COLUMN), 2)
        }

        assertThat(matches.map { it.name }).containsExactly("$SAMPLE_TABLE.AMOUNT", "$SAMPLE_TABLE.CODE")
    }

    @Test
    fun `the source of a program comes back as one text rather than one row per line`() {
        val function = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_FUNCTION, null, fromLine = 1, maxLines = 1_000).single()
        }

        assertThat(function.type).isEqualTo("FUNCTION")
        assertThat(function.totalLines).isGreaterThan(1)
        assertThat(function.fromLine).isEqualTo(1)
        assertThat(function.toLine).isEqualTo(function.totalLines)
        assertThat(function.hasMore).isFalse()
        assertThat(function.source).contains("return value_in * 2")
    }

    @Test
    fun `a range shorter than the object reports what is left and reads on from there`() {
        val firstTwoLines = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_FUNCTION, null, fromLine = 1, maxLines = 2).single()
        }

        assertThat(firstTwoLines.toLine).isEqualTo(2)
        assertThat(firstTwoLines.hasMore).isTrue()
        assertThat(firstTwoLines.source).doesNotContain("return value_in * 2")

        val remainder = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_FUNCTION, null, fromLine = 3, maxLines = 1_000).single()
        }

        // The total is the whole object either way, so a caller can tell how far it has read.
        assertThat(remainder.totalLines).isEqualTo(firstTwoLines.totalLines)
        assertThat(remainder.hasMore).isFalse()
        assertThat(remainder.source).contains("return value_in * 2")
    }

    @Test
    fun `the source of a view is its defining query`() {
        val view = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_VIEW, null, fromLine = 1, maxLines = 1_000).single()
        }

        assertThat(view.owner).isEqualTo(SCHEMA)
        assertThat(view.type).isEqualTo("VIEW")
        assertThat(view.hasMore).isFalse()
        assertThat(view.source).containsIgnoringCase("where label is not null")
    }

    @Test
    fun `an unknown object yields no source rather than an error`() {
        val sources = withEnvironment { environment ->
            catalogReader.readSource(environment, "NO_SUCH_OBJECT_ANYWHERE", null, fromLine = 1, maxLines = 1_000)
        }

        assertThat(sources).isEmpty()
    }

    private fun <T> withEnvironment(work: (DatabaseEnvironment) -> T): T =
        EnvironmentRegistry(OracleTestDatabase.hubProperties()).use { registry ->
            work(requireNotNull(registry.find(ENVIRONMENT_NAME)))
        }
}
