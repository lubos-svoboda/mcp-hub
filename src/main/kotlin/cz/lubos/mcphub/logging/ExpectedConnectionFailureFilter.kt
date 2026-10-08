package cz.lubos.mcphub.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import cz.lubos.mcphub.database.CredentialsRefusedException
import cz.lubos.mcphub.entra.EntraSignInRequiredException
import org.slf4j.Marker
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Drops the pool's warning with a stack trace for failures the hub raised on purpose, a signed-out
 * Entra account or credentials the guard stopped using, and for a network that does not reach the
 * database, such as a disconnected VPN. The pool retries every few seconds, so each would repeat the
 * same trace for as long as it lasts; ConnectionProbe logs the state once instead. Every other
 * warning of the pool is kept.
 */
class ExpectedConnectionFailureFilter : TurboFilter() {

    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level,
        format: String?,
        params: Array<out Any?>?,
        throwable: Throwable?,
    ): FilterReply {
        if (!logger.name.startsWith(POOL_LOGGERS)) {
            return FilterReply.NEUTRAL
        }
        // SLF4J passes a trailing exception among the parameters rather than as the throwable.
        val failure = throwable ?: params?.lastOrNull() as? Throwable ?: return FilterReply.NEUTRAL
        return if (generateSequence(failure) { it.cause }.any(::isExpected)) FilterReply.DENY else FilterReply.NEUTRAL
    }

    private companion object {
        const val POOL_LOGGERS = "com.zaxxer.hikari"

        fun isExpected(failure: Throwable) =
            failure is EntraSignInRequiredException || failure is CredentialsRefusedException || isNetworkFailure(failure)

        /** Both drivers keep the JDK's network exception as a cause, whatever their own error says. */
        fun isNetworkFailure(failure: Throwable) =
            failure is UnknownHostException || failure is ConnectException || failure is NoRouteToHostException ||
                failure is SocketTimeoutException
    }
}
