package com.laa66.spatial;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Two structural properties, enforced rather than reviewed. Runs under {@code unitTest}: it reads
 * class files, so it needs no Spring context and no Docker.
 *
 * <p>The rules exist because of a measurement, not a preference. {@code PostgisUnlockAdapter} is
 * package-private, which stops anything outside its package naming, constructing or subclassing it
 * — but {@code UnlockRepository} is public and must stay public, because the application service
 * depends on it, so visibility alone does not stop a class from injecting the port and calling
 * {@code grant} with a radius of its own. A row written that way records
 * {@code granted_by = 'PROXIMITY'} without any proximity having been measured, is indistinguishable
 * from an honest row, has no detector, and cannot be re-locked. From M4 on, every such row is money
 * spent.
 *
 * <p><strong>Deliberately two rules and not a tower.</strong> What was considered and rejected:
 * <ul>
 * <li>An allowlist of classes permitted to depend on {@code UnlockRepository}. It is the only shape
 * that would also catch a new {@code domain.service} class calling {@code grant}, but it needs
 * three exemptions — the service, the implementing adapter, and {@code SpatialConfig}, whose bean
 * method takes the port as a parameter — which bakes the wiring class into an architecture rule. It
 * would also be edited by the very person adding the new caller, so it buys little over review.
 * <li>Naming {@code UnlockRepository} specifically instead of the package. It would not cover the
 * ports M3-05 and M4 add, and would go quietly out of date rather than loudly.
 * <li>"Nothing outside {@code domain.service} may depend on {@code domain.port.out}". Broader, but
 * it has to exempt the config and every implementing adapter; the web layer is where a bypass would
 * actually get written, because it is the only layer holding a request and in a position to skip
 * the policy on the way past.
 * <li>A full layered/onion ruleset, naming conventions, cycle checks. None of them defend this
 * property, and a rule that fails for an unrelated reason is a rule people delete.
 * </ul>
 */
class ArchitectureTest {

	/**
	 * Production classes only. Test classes legitimately depend on outbound ports — the endpoint
	 * integration test mocks {@code UserPreferences} to drive the toggle — so including them would
	 * make the first rule fail for a reason it is not about.
	 */
	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.laa66.spatial");

	/**
	 * <strong>Checked first, because an architecture rule that quietly tests nothing is the same
	 * failure as configuration that quietly does nothing</strong> — Flyway in M1-05,
	 * {@code spring.http.client.*} in M3-03 — in a shape that looks like coverage.
	 *
	 * <p>Measured, not assumed: ArchUnit 1.5.1 defaults {@code archRule.failOnEmptyShould} to true,
	 * so pointing the importer at a package that does not exist makes both rules below fail with
	 * "failed to check any classes" rather than pass. The vacuous-pass trap is therefore closed by
	 * the library, and this test covers what that guard does not — that the import contains the
	 * specific classes the rules are about (a populated import of the wrong packages would satisfy
	 * {@code failOnEmptyShould} on a {@code that()} clause that matched something irrelevant), and
	 * that the test-exclusion really excluded the tests, since Gradle lays them out under
	 * {@code build/classes/java/test} and the first rule would otherwise fail on an endpoint test
	 * that legitimately mocks an outbound port.
	 */
	@Test
	void theImportActuallySeesTheClassesTheRulesAreAbout() {
		assertThat(PRODUCTION_CLASSES).isNotEmpty();

		assertThat(PRODUCTION_CLASSES.stream().map(JavaClass::getName)).contains(
				"com.laa66.spatial.infrastructure.web.UnlockLocationsController",
				"com.laa66.spatial.infrastructure.persistence.PostgisUnlockAdapter",
				"com.laa66.spatial.domain.port.out.UnlockRepository",
				"com.laa66.spatial.domain.service.UnlockLocationsService");

		assertThat(PRODUCTION_CLASSES.stream().map(JavaClass::getName))
				.as("test classes must be excluded, or the web rule fails on a mocked port")
				.noneMatch(name -> name.endsWith("Test") || name.endsWith("IntTest"));
	}

	/**
	 * The bypass this suite exists to prevent: a controller holding a request, injecting the
	 * repository and writing a {@code poi_unlock} row without the radius, the accuracy gate or the
	 * proximity-preference read that {@code UnlockLocationsService} owns.
	 *
	 * <p>Stated as a layer rule rather than as "nothing may touch {@code UnlockRepository}" so that
	 * the ports M3-05 and M4 add are covered the day they are added, without anyone remembering to
	 * extend this.
	 */
	@Test
	void theHttpBoundaryCannotReachAnOutboundPort() {
		noClasses()
				.that().resideInAPackage("..infrastructure.web..")
				.should().dependOnClassesThat().resideInAPackage("..domain.port.out..")
				.because("a controller must drive an inbound port; reaching a repository directly "
						+ "would write unlock rows without the proximity policy")
				.check(PRODUCTION_CLASSES);
	}

	/** The direction that makes ports mean anything: the domain defines them, adapters implement them. */
	@Test
	void theDomainDoesNotDependOnInfrastructure() {
		noClasses()
				.that().resideInAPackage("..domain..")
				.should().dependOnClassesThat().resideInAPackage("..infrastructure..")
				.because("the domain owns its ports and must stay framework-free and adapter-free")
				.check(PRODUCTION_CLASSES);
	}
}
