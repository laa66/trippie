package com.laa66.content;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Runs under {@code unitTest}: reads class files, no Spring context, no Docker. ArchUnit 1.5.1
 * fails a rule over an empty set of classes, so a package emptied by a refactor fails loudly
 * instead of passing vacuously.
 */
class ArchitectureTest {

	/** Production classes only: test classes may legitimately mock outbound ports. */
	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.laa66.content");

	/**
	 * Both rules below are "no classes may ...", so they are only as strong as the import they run
	 * over. {@code ..domain.port.out..} holds the content repository port and its adapter lives in
	 * {@code ..infrastructure.persistence..} (M4-03), and {@code ..infrastructure.web..} holds the
	 * controller (M4-06), so both rules have real classes on both sides. ArchUnit's
	 * {@code failOnEmptyShould} catches an empty rule, but nothing would catch the import
	 * itself silently collapsing (a renamed root package, a changed {@code importPackages} argument)
	 * or {@code DO_NOT_INCLUDE_TESTS} failing to exclude tests -- which is what would let a mocked
	 * outbound port in a test class decide the web rule.
	 */
	@Test
	void theImportActuallySeesTheClassesTheRulesAreAbout() {
		assertThat(PRODUCTION_CLASSES).isNotEmpty();

		assertThat(PRODUCTION_CLASSES.stream().map(JavaClass::getName)).contains(
				"com.laa66.content.ContentApplication",
				"com.laa66.content.domain.model.ContentMode",
				"com.laa66.content.domain.port.out.ContentRepository",
				"com.laa66.content.domain.port.out.PoiUnlocks",
				"com.laa66.content.domain.port.out.EventPublisher",
				"com.laa66.content.infrastructure.client.SpatialPoiUnlocksAdapter",
				"com.laa66.content.infrastructure.messaging.RabbitEventPublisher",
				"com.laa66.content.infrastructure.web.ContentController");

		// Self-reference first: this class is a test, it is named here, and it cannot quietly stop
		// existing while this assertion runs -- unlike a list of other class names, which goes
		// vacuous the moment someone renames them. The suffix sweep then covers classes added
		// later, in both spellings: `...Tests` (ContentApplicationTests) was missed by an
		// endsWith("Test") check alone.
		assertThat(PRODUCTION_CLASSES.stream().map(JavaClass::getName))
				.as("DO_NOT_INCLUDE_TESTS must really exclude tests, or a mocked outbound port in a "
						+ "test class would decide the web rule")
				.doesNotContain("com.laa66.content.ArchitectureTest")
				.noneMatch(name -> name.endsWith("Test") || name.endsWith("Tests"));
	}

	@Test
	void theHttpBoundaryCannotReachAnOutboundPort() {
		noClasses()
				.that().resideInAPackage("..infrastructure.web..")
				.should().dependOnClassesThat().resideInAPackage("..domain.port.out..")
				.because("a controller must drive an inbound port; reaching a repository directly "
						+ "would skip the domain's policy")
				.check(PRODUCTION_CLASSES);
	}

	@Test
	void theDomainDoesNotDependOnInfrastructure() {
		noClasses()
				.that().resideInAPackage("..domain..")
				.should().dependOnClassesThat().resideInAPackage("..infrastructure..")
				.because("the domain owns its ports and must stay framework-free and adapter-free")
				.check(PRODUCTION_CLASSES);
	}

	@Test
	void theDomainDoesNotKnowTheBroker() {
		noClasses()
				.that().resideInAPackage("..domain..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework.amqp..", "com.rabbitmq..")
				.because("the broker sits behind EventPublisher, so replacing it is a change of adapter")
				.check(PRODUCTION_CLASSES);
	}
}
