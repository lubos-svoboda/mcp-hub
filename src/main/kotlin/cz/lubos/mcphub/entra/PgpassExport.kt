package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.HubProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Writes the current token of every Entra account with a `pgpass-file` into that file, so that other
 * PostgreSQL clients reach the same databases without a sign-in of their own. The token is renewed
 * ahead of time here too, so the file never goes stale while nobody uses the hub, and the file is
 * compared with what it should hold every time, so lines another program removed come back.
 */
@Component
class PgpassExport(hubProperties: HubProperties, private val entraAccountRegistry: EntraAccountRegistry) {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val exportsByAccount: Map<String, AccountExport> = build(hubProperties)

    /** Accounts may share a file, so writes are serialised per file rather than per account. */
    private val locksByFile = ConcurrentHashMap<Path, Any>()

    @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    fun exportAll() = exportsByAccount.keys.forEach(::export)

    /** Brings the account's lines in its file up to date: the current token, or none once signed out. */
    fun export(accountName: String) {
        val accountExport = exportsByAccount[accountName] ?: return
        val token = try {
            entraAccountRegistry.requireAccount(accountName).accessToken()
        } catch (signedOut: EntraSignInRequiredException) {
            null
        } catch (failure: Exception) {
            logger.warn("Entra account {} could not renew the token for its password file: {}", accountName, failure.message)
            return
        }
        val entries = token?.let { current ->
            accountExport.logins.map { (host, port, user) -> PgpassEntry(host, port, user, current.accessToken) }
        }.orEmpty()

        synchronized(locksByFile.computeIfAbsent(accountExport.path.toAbsolutePath().normalize()) { Any() }) {
            try {
                if (!accountExport.file.update(accountExport.managed, entries)) {
                    return
                }
                if (token == null) {
                    logger.info("Removed the token of Entra account {} from {}", accountName, accountExport.path)
                } else {
                    logger.info("Wrote the token of Entra account {} for {} login(s) to {}", accountName, entries.size, accountExport.path)
                }
            } catch (failure: Exception) {
                logger.warn("Entra account {} could not write {}: {}", accountName, accountExport.path, failure.message)
            }
        }
    }

    private fun build(hubProperties: HubProperties): Map<String, AccountExport> =
        hubProperties.entraAccounts
            .filterValues { it.pgpassFile != null }
            .mapValues { (accountName, accountProperties) ->
                val path = Path.of(requireNotNull(accountProperties.pgpassFile))
                require(path.isAbsolute) { "Entra account $accountName needs an absolute pgpass-file, not $path." }
                val logins = hubProperties.environments
                    .filterValues { it.authentication == AuthenticationMethod.ENTRA && it.entraAccount == accountName }
                    .map { (environmentName, environment) ->
                        val address = URI(environment.url.removePrefix("jdbc:"))
                        val host = requireNotNull(address.host) {
                            "Environment $environmentName writes its token to a password file, which needs one " +
                                "host in its URL, as in jdbc:postgresql://host:5432/database."
                        }
                        Login(host, address.port.takeIf { it > 0 } ?: DEFAULT_PORT, requireNotNull(environment.username))
                    }
                    .distinct()
                AccountExport(path, PgpassFile(path), logins)
            }

    private data class Login(val host: String, val port: Int, val user: String)

    private class AccountExport(val path: Path, val file: PgpassFile, val logins: List<Login>) {
        val managed: Set<Pair<String, String>> = logins.map { it.host to it.user }.toSet()
    }

    private companion object {
        const val DEFAULT_PORT = 5432
    }
}
