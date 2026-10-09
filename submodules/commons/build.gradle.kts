plugins {
	`java-library`
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

tasks.withType<Test> {
	useJUnitPlatform()
}

// Same name-based split as the other modules (see e.g. ../auth/build.gradle.kts), without the
// empty-result guard: there is no src/test yet, so there is nothing to match.
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
	outputs.upToDateWhen { false }
}
