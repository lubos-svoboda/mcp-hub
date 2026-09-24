package cz.lubos.mcphub.database

import cz.lubos.mcphub.entra.EntraAccount
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Hands the pool connections signed in with the account's current access token, which stands in
 * for the password. Every connection is tagged with the signed-in user, because the database user
 * is often a group and would not say who connected.
 */
class EntraConnectionSource(
    private val url: String,
    private val username: String,
    private val entraAccount: EntraAccount,
    private val driverProperties: Map<String, String>,
) : DataSource {

    private var logWriter: PrintWriter? = null
    private var loginTimeoutSeconds = 0

    override fun getConnection(): Connection {
        val token = entraAccount.accessToken()
        val properties = Properties().apply {
            driverProperties.forEach(::setProperty)
            setProperty("user", username)
            setProperty("password", token.accessToken)
            setProperty("ApplicationName", applicationName(token.user))
        }
        return DriverManager.getConnection(url, properties)
    }

    override fun getConnection(username: String?, password: String?): Connection =
        throw SQLFeatureNotSupportedException("This data source signs in with an Entra access token only.")

    override fun getLogWriter(): PrintWriter? = logWriter

    override fun setLogWriter(out: PrintWriter?) {
        logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        loginTimeoutSeconds = seconds
    }

    override fun getLoginTimeout(): Int = loginTimeoutSeconds

    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()

    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else throw SQLFeatureNotSupportedException("Not a wrapper of $iface")

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

    companion object {
        fun applicationName(user: String) = "mcp-hub/$user"
    }
}
