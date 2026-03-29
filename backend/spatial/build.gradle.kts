plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

dependencies {
	// spring boot
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-web")

	// geometry
	implementation("org.locationtech.jts:jts-core:1.20.0")
	implementation("com.graphhopper.external:jackson-datatype-jts:2.21.0")

	// database
	implementation("org.hibernate.orm:hibernate-spatial")
	runtimeOnly("org.postgresql:postgresql")
	
	// testing
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
