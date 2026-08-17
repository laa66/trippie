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

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	// Test-scope only: this module owns the migration and the schema it describes, but no
	// production code touches a datasource yet, and putting these on the runtime classpath
	// would make the service refuse to boot until compose provides Postgres (M1-08).
	//
	// HANDOFF M1-05 — promoting this stack to production takes MORE than a scope change.
	// Boot 4 moved FlywayAutoConfiguration out of spring-boot-autoconfigure into its own
	// `spring-boot-flyway` module, so flyway-core alone does NOT migrate on startup: the
	// service would come up green on an empty spatial_db and the first nearby query would
	// fail with `relation "location_point" does not exist`. The full set is:
	//   implementation("org.springframework.boot:spring-boot-flyway")
	//   implementation("org.springframework.boot:spring-boot-starter-jdbc")
	//   runtimeOnly("org.flywaydb:flyway-core")
	//   runtimeOnly("org.flywaydb:flyway-database-postgresql")
	//   runtimeOnly("org.postgresql:postgresql")
	// plus spring.datasource.* in application.yaml. Compose wiring stays M1-08.
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.flywaydb:flyway-database-postgresql")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.postgresql:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
