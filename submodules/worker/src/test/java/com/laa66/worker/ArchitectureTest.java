package com.laa66.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/** Same shape as content's: no Spring context, no Docker, and a guard on the import itself. */
class ArchitectureTest {

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.laa66.worker");

	@Test
	void theImportActuallySeesTheClassesTheRulesAreAbout() {
		assertThat(PRODUCTION_CLASSES.stream().map(JavaClass::getName)).contains(
				"com.laa66.worker.WorkerApplication",
				"com.laa66.worker.domain.model.GenerateContentTask",
				"com.laa66.worker.domain.port.in.TaskConsumer",
				"com.laa66.worker.domain.service.ProviderCallPacer",
				"com.laa66.worker.domain.port.out.TextGenerator",
				"com.laa66.worker.infrastructure.client.GeminiTextGenerator",
				"com.laa66.worker.infrastructure.messaging.GenerationTaskListener");

		assertThat(PRODUCTION_CLASSES.stream().map(JavaClass::getName))
				.doesNotContain("com.laa66.worker.ArchitectureTest")
				.noneMatch(name -> name.endsWith("Test") || name.endsWith("Tests"));
	}

	@Test
	void theDomainDoesNotDependOnInfrastructure() {
		noClasses()
				.that().resideInAPackage("..domain..")
				.should().dependOnClassesThat().resideInAPackage("..infrastructure..")
				.because("the domain owns its ports and must stay adapter-free")
				.check(PRODUCTION_CLASSES);
	}

	@Test
	void theDomainDoesNotKnowTheBroker() {
		noClasses()
				.that().resideInAPackage("..domain..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.rabbitmq..")
				.because("the broker and its retry machinery sit behind TaskConsumer")
				.check(PRODUCTION_CLASSES);
	}
}
