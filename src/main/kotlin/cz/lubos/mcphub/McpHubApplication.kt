package cz.lubos.mcphub

import cz.lubos.mcphub.config.HubProperties

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// One DataSource per configured environment is built by the registry, so the single
// auto-configured DataSource is not wanted — without it Boot fails on a missing URL.
@SpringBootApplication(exclude = [DataSourceAutoConfiguration::class])
@EnableConfigurationProperties(HubProperties::class)
@EnableScheduling
class McpHubApplication

fun main(args: Array<String>) {
	runApplication<McpHubApplication>(*args)
}
