package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.HubProperties
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class EntraAccountRegistry(
    hubProperties: HubProperties,
    tokenClientFactory: EntraTokenClientFactory,
    clock: Clock = Clock.systemUTC(),
) {

    private val accountsByName: Map<String, EntraAccount> =
        hubProperties.entraAccounts.mapValues { (accountName, accountProperties) ->
            require(accountProperties.tenantId.isNotBlank()) { "Entra account $accountName needs a tenant-id." }
            EntraAccount(accountName, accountProperties, tokenClientFactory.create(accountProperties), clock)
        }

    fun all(): Collection<EntraAccount> = accountsByName.values

    fun requireAccount(accountName: String): EntraAccount =
        accountsByName[accountName] ?: throw IllegalArgumentException(
            "Unknown Entra account '$accountName'. Configured accounts: " +
                accountsByName.keys.sorted().joinToString().ifEmpty { "none" } + ".",
        )
}
