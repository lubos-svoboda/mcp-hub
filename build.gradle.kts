plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "cz.lubos"
// A release is a git tag such as v0.1.0, and a commit after it reads like 0.1.0-3-gd2fd529.
// A Docker build sees no .git and is given the version as -PreleaseVersion; with neither, the
// build is not a release and says so.
version = providers.gradleProperty("releaseVersion")
	.orElse(gitDescribedVersion())
	.getOrElse("0.0.0-dev")

fun gitDescribedVersion(): Provider<String> =
	if (!layout.projectDirectory.dir(".git").asFile.exists()) {
		providers.provider { null }
	} else {
		providers.exec {
			commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--dirty")
			isIgnoreExitValue = true
		}.standardOutput.asText.map { it.trim().removePrefix("v") }.filter(String::isNotEmpty)
	}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// The version lives only here; application.yaml carries a token that is replaced with it.
tasks.processResources {
	val projectVersion = project.version.toString()
	inputs.property("version", projectVersion)
	filesMatching("application.yaml") {
		filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to mapOf("version" to projectVersion))
	}
}

// Without this, build/libs also holds a plain library jar that the Dockerfile's COPY
// would match alongside the executable one.
tasks.jar {
	enabled = false
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
		mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
	}
}

dependencies {
	implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	// Grafana answers are large and the useful part is small; jq narrows them at the source.
	implementation("net.thisptr:jackson-jq:1.6.5")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.oracle.database.jdbc:ojdbc17")
	// The thin driver only knows a handful of character sets on its own. Without this a
	// database using, for example, EE8ISO8859P2 refuses every connection with ORA-17056.
	runtimeOnly("com.oracle.database.nls:orai18n:23.26.3.0.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:oracle-free")
	testImplementation("org.testcontainers:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// Tests needing real servers in Docker take minutes, so they stay out of the everyday loop.
// Run them with `gradlew integrationTest`.
tasks.test {
	useJUnitPlatform {
		excludeTags("integration")
	}
}

tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Runs the tests that need a real database or Grafana in Docker."
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("integration")
	}
	shouldRunAfter(tasks.test)
}
