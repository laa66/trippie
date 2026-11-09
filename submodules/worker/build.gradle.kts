plugins {
	java
	id("org.springframework.boot") version "4.0.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.laa66"
version = "0.0.1-SNAPSHOT"

repositories {
	mavenCentral()
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

// No datasource, no Flyway, no JDBC driver: content is the only writer of content_db, and the worker
// reaches it over content's internal HTTP endpoint (M4-11). Web + actuator exist only for the
// compose healthcheck.
dependencies {
	implementation("com.laa66:commons")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// The starter pulls spring-boot-amqp, the module RabbitAutoConfiguration lives in since Boot 4
	// (verified in content at M4-07).
	implementation("org.springframework.boot:spring-boot-starter-amqp")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.testcontainers:testcontainers-rabbitmq")
	// Explicit version: ArchUnit is not in the Spring Boot BOM (see content/build.gradle.kts).
	testImplementation("com.tngtech.archunit:archunit:1.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Activates the `test` profile (application-test.yaml): the broker on a closed port, so context
	// tests run without Docker.
	environment("SPRING_PROFILES_ACTIVE", "test")
}

// Split by class name: ITs (*IntegrationTest / *IntTest) vs everything else; used by `make unit-test` / `make int-test`.
val integrationTestPatterns = listOf("**/*IntegrationTest.class", "**/*IntTest.class")

// The manual smoke against the real, paid Gemini API: excluded from every routine test task.
val liveSmokePattern = "**/*LiveSmoke.class"

tasks.named<Test>("test") {
	exclude(liveSmokePattern)
}

tasks.register<Test>("unitTest") {
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	exclude(integrationTestPatterns)
	exclude(liveSmokePattern)
}

tasks.register<Test>("integrationTest") {
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	include(integrationTestPatterns)
	// Docker daemon state is not a tracked input; never report ITs as UP-TO-DATE.
	outputs.upToDateWhen { false }
}

// MANUAL, PAID: one real Gemini call with the key file (default ../../infra/secrets/gemini.key,
// override with -PkeyFile=...). Run by hand only, see the M4-10 smoke procedure in PLAN.md.
tasks.register<Test>("geminiSmoke") {
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	filter { includeTestsMatching("*GeminiLiveSmoke") }
	systemProperty("trippie.gemini.smoke", "true")
	systemProperty("trippie.gemini.smoke.key-file",
		file(project.findProperty("keyFile")?.toString() ?: "../../infra/secrets/gemini.key").absolutePath)
	outputs.upToDateWhen { false }
	testLogging { showStandardStreams = true }
}

// Gradle silently skips a Test task whose filter matches no class (BUILD SUCCESSFUL, zero tests).
listOf("unitTest", "integrationTest").forEach { name ->
	val resultsDir = layout.buildDirectory.dir("test-results/$name")
	val guard = tasks.register("${name}Guard") {
		doLast {
			val xmls = resultsDir.get().asFile.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
			if (xmls.isNullOrEmpty()) throw GradleException("$name produced no test results: its filter matched no test class")
		}
	}
	tasks.named(name) { finalizedBy(guard) }
}
