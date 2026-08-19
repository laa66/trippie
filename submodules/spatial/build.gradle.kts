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

	// Nearby read-path: JdbcClient + native SQL (spring-boot-starter-jdbc) and startup Flyway.
	// Boot 4 moved FlywayAutoConfiguration into the dedicated spring-boot-flyway module, so
	// flyway-core alone does NOT migrate on startup — both are required for spatial to own and
	// apply the location_point schema when it boots against Postgres. Compose wiring is M1-08.
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	// Boot 4 split @WebMvcTest slice support out of spring-boot-starter-test into its own
	// starter (M1-06, first controller slice test in this module).
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// spring-boot-flyway pulls flyway-core in transitively; this declares the API the M1-01
	// migration IT uses directly (it drives the Flyway API at test-compile).
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Activates the `test` profile (application-test.yaml) without touching any test class:
	// startup Flyway off + DB health off so the context/health tests run without a database.
	environment("SPRING_PROFILES_ACTIVE", "test")
}
