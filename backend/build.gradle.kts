plugins {
	java
	id("org.springframework.boot") version "4.0.5" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
	group = "com.laa66"
	version = "0.0.1-SNAPSHOT"

	repositories {
		mavenCentral()
	}
}

subprojects {
	apply(plugin = "java")
	apply(plugin = "io.spring.dependency-management")

	java {
		toolchain {
			languageVersion = JavaLanguageVersion.of(21)
		}
	}

	dependencies {
		// lombok
		compileOnly("org.projectlombok:lombok")
		annotationProcessor("org.projectlombok:lombok")

		// testing
		testCompileOnly("org.projectlombok:lombok")
        testAnnotationProcessor("org.projectlombok:lombok")
		testImplementation("org.springframework.boot:spring-boot-starter-test")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	}

	tasks.withType<Test> {
		useJUnitPlatform()
	}

}