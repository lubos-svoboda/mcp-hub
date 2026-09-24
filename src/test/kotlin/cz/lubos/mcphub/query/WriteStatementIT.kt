package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.support.PostgresTestDatabase
import cz.lubos.mcphub.support.PostgresTestDatabase.ENVIRONMENT_NAME
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

@Tag("integration")
class WriteStatementIT {

    private val writeStatementRunner = WriteStatementRunner()
    private val queryRunner = QueryRunner(ReadOnlySession(), ResultMapper())

    @Test
    fun `a write on a writable environment is committed and can be read back`() {
        onWritableEnvironment { environment ->
            // The PostgreSQL driver answers 0 for DDL rather than "no count at all", so that is
            // what comes back. Nothing here invents a nicer number than the driver reported.
            val created = writeStatementRunner.execute(environment, "create table write_probe (id integer)")
            assertThat(created.affectedRows).isEqualTo(0)

            val inserted = writeStatementRunner.execute(environment, "insert into write_probe values (1)")
            assertThat(inserted.affectedRows).isEqualTo(1)

            val readBack = queryRunner.run(environment, "select id from write_probe")
            assertThat(readBack.rows).containsExactly(listOf("1"))

            writeStatementRunner.execute(environment, "drop table write_probe")
        }
    }

    /**
     * The point of the whole split: even where writing is allowed, the reading tool still cannot
     * write, because it runs in a read-only transaction regardless of the environment.
     */
    @Test
    fun `run_sql_query still refuses to write on a writable environment`() {
        onWritableEnvironment { environment ->
            writeStatementRunner.execute(environment, "create table read_only_probe (id integer)")
            try {
                val failure = assertThrows<SQLException> {
                    queryRunner.run(environment, "insert into read_only_probe values (1)")
                }
                assertThat(failure.message).contains("read-only transaction")

                val rows = queryRunner.run(environment, "select id from read_only_probe")
                assertThat(rows.rows).isEmpty()
            } finally {
                writeStatementRunner.execute(environment, "drop table read_only_probe")
            }
        }
    }

    @Test
    fun `a write on a read-only environment is refused before it reaches the database`() {
        onReadOnlyEnvironment { environment ->
            val failure = assertThrows<IllegalArgumentException> {
                writeStatementRunner.execute(environment, "create table never_created (id integer)")
            }

            assertThat(failure.message).contains("read-only")
        }
    }

    @Test
    fun `a statement returning rows is refused by the write tool`() {
        onWritableEnvironment { environment ->
            val failure = assertThrows<IllegalArgumentException> {
                writeStatementRunner.execute(environment, "select 1")
            }

            assertThat(failure.message).contains("run_sql_query")
        }
    }

    private fun onWritableEnvironment(work: (DatabaseEnvironment) -> Unit) =
        EnvironmentRegistry(PostgresTestDatabase.hubProperties(readOnly = false)).use { registry ->
            work(requireNotNull(registry.find(ENVIRONMENT_NAME)))
        }

    private fun onReadOnlyEnvironment(work: (DatabaseEnvironment) -> Unit) =
        EnvironmentRegistry(PostgresTestDatabase.hubProperties()).use { registry ->
            work(requireNotNull(registry.find(ENVIRONMENT_NAME)))
        }
}
