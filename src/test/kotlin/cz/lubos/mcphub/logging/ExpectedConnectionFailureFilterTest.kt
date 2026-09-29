package cz.lubos.mcphub.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.lubos.mcphub.database.CredentialsRefusedException
import cz.lubos.mcphub.entra.EntraSignInRequiredException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

class ExpectedConnectionFailureFilterTest {

    private val loggerContext = LoggerContext().apply { addTurboFilter(ExpectedConnectionFailureFilter().apply { start() }) }
    private val appender = ListAppender<ILoggingEvent>().apply {
        context = loggerContext
        start()
    }

    /** Logged the way HikariCP's PoolBase logs it, with the exception as the last parameter. */
    private fun poolWarns(failure: Throwable, loggerName: String = "com.zaxxer.hikari.pool.PoolBase") {
        loggerContext.getLogger(loggerName).apply { addAppender(appender) }
            .warn("{} - Pool is empty, failed to create/setup connection ({})", "AZURE_EXAMPLE_DB", "4aa531be", failure)
    }

    @Test
    fun `a signed-out Entra account leaves no trace in the pool log`() {
        poolWarns(EntraSignInRequiredException("Entra account WORK is not signed in"))

        assertThat(appender.list).isEmpty()
    }

    @Test
    fun `credentials the guard stopped using leave no trace in the pool log`() {
        poolWarns(RuntimeException("wrapped", CredentialsRefusedException("ORACLE_SHARED_DB refused its credentials")))

        assertThat(appender.list).isEmpty()
    }

    @Test
    fun `any other failure of the pool is still logged with its trace`() {
        poolWarns(SQLException("Connection refused", "08001"))

        assertThat(appender.list).singleElement().satisfies({ event -> assertThat(event.throwableProxy.message).isEqualTo("Connection refused") })
    }

    @Test
    fun `the same exception logged by anything but the pool is kept`() {
        poolWarns(EntraSignInRequiredException("Entra account WORK is not signed in"), loggerName = "cz.lubos.mcphub.tool.ToolCallAspect")

        assertThat(appender.list).hasSize(1)
    }
}
