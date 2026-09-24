package cz.lubos.mcphub.support

import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.PoolDefaults
import cz.lubos.mcphub.config.ProbeProperties
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/** PostgreSQL counterpart of [OracleTestDatabase]; this one starts in seconds. */
object PostgresTestDatabase {

    const val ENVIRONMENT_NAME = "POSTGRES_TEST_DB"
    const val USERNAME = "hub"
    const val PASSWORD = "hub-secret-do-not-leak"
    const val SCHEMA = "public"

    const val SAMPLE_TABLE = "sample_values"
    const val SAMPLE_FUNCTION = "sample_double"
    const val CZECH_LABEL = "Přípojný bod Ostrava-Poruba, šířka 12 µm"

    private val container =
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("hub")
            .withUsername(USERNAME)
            .withPassword(PASSWORD)

    val jdbcUrl: String by lazy { container.jdbcUrl }

    init {
        container.start()
        createSampleSchema()
    }

    fun hubProperties(maxRows: Int = 500, readOnly: Boolean = true) = HubProperties(
        probe = ProbeProperties(intervalSeconds = 3_600),
        defaults = EnvironmentDefaults(
            maxRows = maxRows,
            queryTimeoutSeconds = 30,
            connectTimeoutSeconds = 5,
            pool = PoolDefaults(maximumSize = 2, minimumIdle = 0, validationTimeoutSeconds = 3),
        ),
        environments = mapOf(
            ENVIRONMENT_NAME to EnvironmentProperties(
                description = "Integration test database",
                type = EnvironmentType.POSTGRESQL,
                url = jdbcUrl,
                username = USERNAME,
                password = PASSWORD,
                schema = SCHEMA,
                readOnly = readOnly,
            ),
        ),
    )

    private fun createSampleSchema() {
        DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create table $SAMPLE_TABLE (
                      id integer constraint sample_values_pk primary key,
                      label varchar(400),
                      amount numeric(38,10),
                      recorded_at timestamp,
                      note text
                    )
                    """.trimIndent(),
                )
                statement.execute("create index sample_values_label_ix on $SAMPLE_TABLE (label)")
                statement.execute(
                    """
                    create function $SAMPLE_FUNCTION(value_in numeric) returns numeric as ${'$'}${'$'}
                    begin
                      return value_in * 2;
                    end;
                    ${'$'}${'$'} language plpgsql
                    """.trimIndent(),
                )
                statement.execute(
                    "insert into $SAMPLE_TABLE (id, label, amount, recorded_at) values " +
                        "(1, '$CZECH_LABEL', 12345678901234567890.123, timestamp '2026-03-15 08:00:45')",
                )
                statement.execute("insert into $SAMPLE_TABLE (id) values (2)")
                statement.execute("insert into $SAMPLE_TABLE (id, label) values (3, 'Třetí záznam')")
            }
        }
    }
}
