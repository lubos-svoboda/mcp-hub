package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.query.QueryRunner
import cz.lubos.mcphub.query.ReadOnlySession
import cz.lubos.mcphub.query.ResultMapper
import cz.lubos.mcphub.support.PostgresTestDatabase
import cz.lubos.mcphub.support.PostgresTestDatabase.CZECH_LABEL
import cz.lubos.mcphub.support.PostgresTestDatabase.ENVIRONMENT_NAME
import cz.lubos.mcphub.support.PostgresTestDatabase.SAMPLE_FUNCTION
import cz.lubos.mcphub.support.PostgresTestDatabase.SAMPLE_MATERIALIZED_VIEW
import cz.lubos.mcphub.support.PostgresTestDatabase.SAMPLE_TABLE
import cz.lubos.mcphub.support.PostgresTestDatabase.SAMPLE_VIEW
import cz.lubos.mcphub.support.PostgresTestDatabase.SCHEMA
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

/** The same tools over a second database, so the dialect split is exercised end to end. */
@Tag("integration")
class PostgresCatalogIT {

    private val catalogReader = CatalogReader(ReadOnlySession(), OracleCatalog(), PostgresCatalog())

    @Test
    fun `a table is described with types rendered the way PostgreSQL writes them`() {
        val description = withEnvironment { environment ->
            catalogReader.describeTables(environment, listOf(SAMPLE_TABLE)).single()
        }

        assertThat(description.owner).isEqualTo(SCHEMA)
        assertThat(description.columns.map { it.name to it.type }).contains(
            "id" to "integer",
            "label" to "character varying(400)",
            "amount" to "numeric(38,10)",
            "recorded_at" to "timestamp without time zone",
        )
        assertThat(description.columns.single { it.name == "id" }.nullable).isFalse()

        val primaryKey = description.constraints.single { it.type == "PRIMARY KEY" }
        assertThat(primaryKey.columns).containsExactly("id")
        assertThat(description.indexes.map { it.name }).contains("sample_values_label_ix")
    }

    @Test
    fun `a search finds the table, a column and the function`() {
        val matches = withEnvironment { environment ->
            catalogReader.search(environment, "sample", SchemaObjectKind.entries.toSet(), 100)
        }

        assertThat(matches).anyMatch { it.kind == SchemaObjectKind.TABLE && it.name == SAMPLE_TABLE }
        assertThat(matches).anyMatch { it.kind == SchemaObjectKind.PROGRAM && it.name == SAMPLE_FUNCTION }
    }

    @Test
    fun `views are found as views, telling a materialized one apart, and never as tables`() {
        val matches = withEnvironment { environment ->
            catalogReader.search(environment, "sample", SchemaObjectKind.entries.toSet(), 100)
        }

        val view = matches.single { it.name == SAMPLE_VIEW }
        assertThat(view.kind).isEqualTo(SchemaObjectKind.VIEW)
        assertThat(view.detail).isEqualTo("VIEW")

        val materializedView = matches.single { it.name == SAMPLE_MATERIALIZED_VIEW }
        assertThat(materializedView.kind).isEqualTo(SchemaObjectKind.VIEW)
        assertThat(materializedView.detail).isEqualTo("MATERIALIZED VIEW")
    }

    @Test
    fun `the source of a view is its defining query, and the object type selects the kind of view`() {
        val view = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_VIEW, null, fromLine = 1, maxLines = 1_000).single()
        }

        assertThat(view.owner).isEqualTo(SCHEMA)
        assertThat(view.type).isEqualTo("VIEW")
        assertThat(view.source).containsIgnoringCase("label is not null")

        val materializedView = withEnvironment { environment ->
            catalogReader.readSource(
                environment,
                SAMPLE_MATERIALIZED_VIEW,
                "materialized view",
                fromLine = 1,
                maxLines = 1_000,
            ).single()
        }

        assertThat(materializedView.type).isEqualTo("MATERIALIZED VIEW")
        assertThat(materializedView.source).containsIgnoringCase("count(*)")
    }

    /** PostgreSQL keeps a body as one text, so the line range is cut in our code, not by the query. */
    @Test
    fun `the source of a function is paged even though it is stored as one text`() {
        val firstLine = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_FUNCTION, null, fromLine = 1, maxLines = 1).single()
        }

        assertThat(firstLine.type).isEqualTo("FUNCTION")
        assertThat(firstLine.totalLines).isGreaterThan(1)
        assertThat(firstLine.toLine).isEqualTo(1)
        assertThat(firstLine.hasMore).isTrue()

        val whole = withEnvironment { environment ->
            catalogReader.readSource(environment, SAMPLE_FUNCTION, null, fromLine = 1, maxLines = 1_000).single()
        }

        assertThat(whole.hasMore).isFalse()
        assertThat(whole.source).contains("value_in * 2")
    }

    /** Unlike Oracle, plain EXPLAIN writes nothing, so a plan works inside the read-only transaction. */
    @Test
    fun `a plan is produced for a statement that has not been run`() {
        val plan = withEnvironment { environment ->
            catalogReader.readPlan(environment, "select id from $SAMPLE_TABLE where id = 1")
        }

        assertThat(plan?.plan).contains("Scan")
    }

    @Test
    fun `czech text survives and a write is still refused`() {
        withEnvironment { environment ->
            val queryRunner = QueryRunner(ReadOnlySession(), ResultMapper())

            val result = queryRunner.run(environment, "select label from $SAMPLE_TABLE where id = 1")
            assertThat(result.rows.single().single()).isEqualTo(CZECH_LABEL)

            val failure = assertThrows<SQLException> {
                queryRunner.run(environment, "insert into $SAMPLE_TABLE (id) values (99)")
            }
            assertThat(failure.message).contains("read-only transaction")
        }
    }

    /** A PostgreSQL date has no time of day, and the driver refuses to read one as a date-time. */
    @Test
    fun `a date comes back as an ISO date and a timestamp as a date-time`() {
        val result = withEnvironment { environment ->
            QueryRunner(ReadOnlySession(), ResultMapper()).run(
                environment,
                "select date '2026-03-15' as day, timestamp '2026-03-15 08:00:45' as moment",
            )
        }

        assertThat(result.rows.single()).containsExactly("2026-03-15", "2026-03-15T08:00:45")
    }

    /**
     * The driver reports timestamptz as a plain TIMESTAMP and refuses to read it without its offset;
     * the test container's session runs in UTC, so the offset reads as Z.
     */
    @Test
    fun `a timestamp with time zone comes back with its offset`() {
        val result = withEnvironment { environment ->
            QueryRunner(ReadOnlySession(), ResultMapper()).run(
                environment,
                "select timestamptz '2026-03-15 08:00:45+00' as moment, now() is not null as has_now",
            )
        }

        assertThat(result.rows.single()).containsExactly("2026-03-15T08:00:45Z", "t")
    }

    /** bytea is a plain value, not a large object, and reading it as a BLOB fails outright. */
    @Test
    fun `a binary value is summarised by its size`() {
        val result = withEnvironment { environment ->
            QueryRunner(ReadOnlySession(), ResultMapper()).run(environment, "select '\\x0102'::bytea as binary_value")
        }

        assertThat(result.rows.single().single()).isEqualTo("<binary, 2 bytes>")
    }

    private fun <T> withEnvironment(work: (DatabaseEnvironment) -> T): T =
        EnvironmentRegistry(PostgresTestDatabase.hubProperties()).use { registry ->
            work(requireNotNull(registry.find(ENVIRONMENT_NAME)))
        }
}
