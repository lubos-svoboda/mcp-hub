package cz.lubos.mcphub.target

import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.database.CredentialRefusal

/**
 * Anything the hub connects to and reports the state of. A database reaches this through a
 * pooled JDBC connection, Grafana over HTTP; the probe above them knows neither.
 */
interface ProbeTarget {
    val name: String
    val description: String
    val type: EnvironmentType
    val readOnly: Boolean

    /** Throws when the target cannot be reached. The exception carries the reason. */
    // Returns a note when the target answers but lacks something the tools need; null when all is well.
    fun checkReachable(): String?

    /** Set once the target refused its credentials and stopped signing in, so the account is not locked. */
    fun credentialRefusal(): CredentialRefusal? = null

    /** Lets a target that stopped signing in try once more; only an explicit request calls this. */
    fun allowOneSignInAttempt() {}
}

/** Implemented by each source of targets, so the probe does not have to know them by name. */
interface TargetRegistry {
    fun targets(): Collection<ProbeTarget>
}
