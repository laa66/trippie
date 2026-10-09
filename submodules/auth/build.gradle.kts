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

dependencies {
	implementation("com.laa66:commons")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// JPA is the default persistence for auth (M2 frozen decision). starter-data-jpa also
	// supplies the DataSource + HikariCP that startup Flyway migrates against, so no separate
	// starter-jdbc is needed. Boot 4 moved FlywayAutoConfiguration into the dedicated
	// spring-boot-flyway module, so flyway-core alone on the classpath does NOT migrate on
	// startup — spring-boot-flyway is required (the same trap M1-01 documents).
	// flyway-database-postgresql + the driver are runtime-only.
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	// OTP store (SHA-256-hashed codes with a native TTL) lives in Redis.
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	// Boundary validation of the register payload (@Email / @Size) -> 400 ProblemDetail.
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// BCrypt via DelegatingPasswordEncoder. NOT spring-boot-starter-security — its filter chain
	// would 401 every endpoint (the auth-side analog of the Flyway-module trap). Version is
	// managed by the Boot dependency-management BOM, so no explicit version here.
	implementation("org.springframework.security:spring-security-crypto")

	// Access-token minting + JWKS are hand-rolled on Nimbus JOSE (M2 frozen decision — NOT Spring
	// Authorization Server, NOT starter-security). Boot 4's BOM does not manage this coordinate
	// (it ships with spring-security-oauth2-jose, which auth does not pull), so the version is
	// pinned explicitly here.
	implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	// @WebMvcTest moved to its own starter/autoconfigure package in Boot 4 (M1-06 finding).
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// spring-boot-flyway pulls flyway-core transitively; this declares the API the M2-02
	// migration IT drives directly at test-compile.
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	// Core Testcontainers for the redis singleton (GenericContainer) used by the M2-03 IT.
	testImplementation("org.testcontainers:testcontainers")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Activates the `test` profile (application-test.yaml): startup Flyway off + DB health off,
	// so the M2-01 context/health tests run without a live database. The migration IT talks to
	// Testcontainers directly via the Flyway API, so it is unaffected by these relaxations.
	environment("SPRING_PROFILES_ACTIVE", "test")
}

// Split by class name: ITs (*IntegrationTest / *IntTest) vs everything else; used by `make unit-test` / `make int-test`.
val integrationTestPatterns = listOf("**/*IntegrationTest.class", "**/*IntTest.class")

tasks.register<Test>("unitTest") {
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	exclude(integrationTestPatterns)
}

tasks.register<Test>("integrationTest") {
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	include(integrationTestPatterns)
	// Docker daemon state is not a tracked input; never report ITs as UP-TO-DATE.
	outputs.upToDateWhen { false }
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

// M2-13: generates the local dev ES256 signing key (JWK JSON) for `make auth-keys`, reusing
// Nimbus's ECKeyGenerator (the same one JwtKeyLoaderTest uses) so the output is guaranteed
// byte-shape compatible with JwtKeyLoader. The generator class lives in test sources (nimbus is
// already a main/runtime dependency for minting, but this CLI has no place in the runtime image);
// running it does not compile or execute any actual tests. Never invoked at application runtime.
tasks.register<JavaExec>("genAuthKey") {
	group = "application"
	description = "Generates the dev ES256 signing JWK. Usage: ./gradlew genAuthKey -Pout=<path>"
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass.set("com.laa66.auth.tooling.GenerateDevSigningKey")
	args = listOfNotNull(project.findProperty("out") as String?)
}
