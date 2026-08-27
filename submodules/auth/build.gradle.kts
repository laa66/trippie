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

	// Startup Flyway owns the auth_db schema. Boot 4 moved FlywayAutoConfiguration into the
	// dedicated spring-boot-flyway module, so flyway-core alone on the classpath does NOT
	// migrate on startup — spring-boot-flyway is required (the same trap M1-01 documents).
	// spring-boot-starter-jdbc supplies the DataSource + HikariCP that Flyway migrates against;
	// M2-03 layers JPA on top of it. flyway-database-postgresql + the driver are runtime-only.
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	// spring-boot-flyway pulls flyway-core transitively; this declares the API the M2-02
	// migration IT drives directly at test-compile.
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Activates the `test` profile (application-test.yaml): startup Flyway off + DB health off,
	// so the M2-01 context/health tests run without a live database. The migration IT talks to
	// Testcontainers directly via the Flyway API, so it is unaffected by these relaxations.
	environment("SPRING_PROFILES_ACTIVE", "test")
}
