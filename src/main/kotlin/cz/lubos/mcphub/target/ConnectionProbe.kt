package cz.lubos.mcphub.target

import cz.lubos.mcphub.config.HubProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.IntervalTask
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Single owner of every target's state. One repeating task covers three things at once: it
 * connects in the background after startup, it keeps pooled connections exercised, and it
 * remembers why a target is unreachable between calls.
 */
@Component
class ConnectionProbe(
    private val registries: List<TargetRegistry>,
    private val hubProperties: HubProperties,
) : SchedulingConfigurer {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val statusByEnvironment = ConcurrentHashMap<String, EnvironmentStatus>()

    init {
        val startedAt = Instant.now()
        allTargets().forEach { target ->
            statusByEnvironment[target.name] = EnvironmentStatus(ConnectionState.CONNECTING, startedAt)
        }
    }

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        taskRegistrar.addFixedDelayTask(
            IntervalTask(
                Runnable { probeAll() },
                Duration.ofSeconds(hubProperties.probe.intervalSeconds),
                Duration.ZERO,
            ),
        )
    }

    fun statusOf(environmentName: String): EnvironmentStatus? = statusByEnvironment[environmentName]

    // Targets are probed one after another, so a timing-out target delays the others by up to
    // its connect timeout. Acceptable while the count stays small.
    fun probeAll() = allTargets().forEach(::probe)

    private fun allTargets(): List<ProbeTarget> = registries.flatMap(TargetRegistry::targets)

    private fun probe(target: ProbeTarget) {
        try {
            target.checkReachable()
            record(target.name, ConnectionState.UP, lastError = null)
        } catch (failure: Exception) {
            record(target.name, ConnectionState.DOWN, describe(failure))
        }
    }

    private fun record(environmentName: String, connectionState: ConnectionState, lastError: String?) {
        val previousStatus = statusByEnvironment[environmentName]
        if (previousStatus != null && previousStatus.connectionState == connectionState) {
            // Same state as before: refresh the error text, keep `since`, and stay quiet in the
            // log. Without this a disconnected network would fill the log with identical traces.
            statusByEnvironment[environmentName] = previousStatus.copy(lastError = lastError)
            return
        }

        val status = EnvironmentStatus(connectionState, Instant.now(), lastError)
        statusByEnvironment[environmentName] = status
        logTransition(environmentName, status)
    }

    private fun logTransition(environmentName: String, status: EnvironmentStatus) {
        when (status.connectionState) {
            ConnectionState.UP -> logger.info("Environment {} is reachable", environmentName)
            ConnectionState.DOWN -> logger.warn("Environment {} is unreachable: {}", environmentName, status.lastError)
            ConnectionState.CONNECTING -> logger.info("Environment {} is connecting", environmentName)
        }
    }

    /** The driver's own wording is kept; only the cause is unwrapped when it adds detail. */
    private fun describe(failure: Exception): String {
        val cause = failure.cause
        if (cause == null || cause.message == null || cause.message == failure.message) {
            return failure.message ?: failure.javaClass.simpleName
        }
        return "${failure.message} (${cause.message})"
    }
}
