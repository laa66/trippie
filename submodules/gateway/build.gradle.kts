plugins {
	java
	id("org.springframework.boot") version "4.0.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.laa66"
version = "0.0.1-SNAPSHOT"

extra["springCloudVersion"] = "2025.1.0"

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
	implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// Reactive OAuth2 resource server (flow 03): pulls spring-security-config + oauth2-jose +
	// nimbus-jose-jwt. In a WebFlux app the reactive stack (ServerHttpSecurity /
	// ReactiveJwtDecoder) auto-activates, not the servlet variant.
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
