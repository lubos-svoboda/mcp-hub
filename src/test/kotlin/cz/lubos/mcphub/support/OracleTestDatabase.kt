package cz.lubos.mcphub.support

import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.PoolDefaults
import cz.lubos.mcphub.config.ProbeProperties
import org.testcontainers.DockerClientFactory
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.net.ServerSocket
import java.sql.DriverManager

/**
 * One Oracle instance shared by every integration test; starting it costs far too much to pay
 * twice. The sample schema is created once, right after the container is up.
 */
object OracleTestDatabase {

    const val ENVIRONMENT_NAME = "ORACLE_TEST_DB"
    const val USERNAME = "hub"
    const val PASSWORD = "hub-secret-do-not-leak"
    const val SCHEMA = "HUB"

    const val SAMPLE_TABLE = "SAMPLE_VALUES"
    const val SAMPLE_FUNCTION = "SAMPLE_DOUBLE"
    const val SAMPLE_VIEW = "SAMPLE_VALUES_VIEW"
    const val CZECH_LABEL = "Přípojný bod Ostrava-Poruba, šířka 12 µm"

    private val longNote = "Poznámka s diakritikou. ".repeat(300)
    private val hostPort = freePort()

    private val container =
        OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .apply {
                // A fixed host port survives a stop and start of the container. A randomly
                // assigned one would not, and the JDBC URL would stop pointing anywhere.
                portBindings = listOf("$hostPort:1521")
            }

    val jdbcUrl: String by lazy { container.jdbcUrl }

    init {
        container.start()
        createSampleSchema()
    }

    fun stopDatabase() {
        DockerClientFactory.instance().client().stopContainerCmd(container.containerId).exec()
    }

    fun startDatabase() {
        DockerClientFactory.instance().client().startContainerCmd(container.containerId).exec()
    }

    fun hubProperties(
        maxRows: Int = 500,
        maxTextValueCharacters: Int = 4_000,
    ) = HubProperties(
        probe = ProbeProperties(intervalSeconds = 3_600),
        defaults = EnvironmentDefaults(
            maxRows = maxRows,
            queryTimeoutSeconds = 30,
            maxTextValueCharacters = maxTextValueCharacters,
            connectTimeoutSeconds = 5,
            pool = PoolDefaults(maximumSize = 2, minimumIdle = 0, validationTimeoutSeconds = 3),
        ),
        environments = mapOf(
            ENVIRONMENT_NAME to EnvironmentProperties(
                description = "Integration test database",
                type = EnvironmentType.ORACLE,
                url = jdbcUrl,
                username = USERNAME,
                password = PASSWORD,
                schema = SCHEMA,
            ),
        ),
    )

    private fun createSampleSchema() {
        DriverManager.getConnection(jdbcUrl, USERNAME, PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    create table $SAMPLE_TABLE (
                      id number(10) constraint sample_values_pk primary key,
                      label varchar2(400),
                      amount number(38,10),
                      recorded_at date,
                      note clob,
                      code varchar2(10 char)
                    )
                    """.trimIndent(),
                )
                statement.execute("create index sample_values_label_ix on $SAMPLE_TABLE (label)")
                statement.execute("create view $SAMPLE_VIEW as select id, label from $SAMPLE_TABLE where label is not null")
                statement.execute(
                    """
                    create or replace function $SAMPLE_FUNCTION(value_in number) return number is
                    begin
                      return value_in * 2;
                    end;
                    """.trimIndent(),
                )
            }

            connection.prepareStatement(
                """
                insert into $SAMPLE_TABLE (id, label, amount, recorded_at, note)
                values (?, ?, ?, to_date(?, 'yyyy-mm-dd hh24:mi:ss'), ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, 1)
                statement.setString(2, CZECH_LABEL)
                statement.setBigDecimal(3, BigDecimal("12345678901234567890.123"))
                statement.setString(4, "2026-03-15 08:00:45")
                statement.setString(5, longNote)
                statement.executeUpdate()
            }

            connection.createStatement().use { statement ->
                statement.execute("insert into $SAMPLE_TABLE (id) values (2)")
                statement.execute("insert into $SAMPLE_TABLE (id, label) values (3, 'Třetí záznam')")
            }
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { socket -> socket.localPort }
}
