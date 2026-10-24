package com.laa66.spatial;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import com.laa66.spatial.domain.port.in.FindLocationDetail;
import com.laa66.spatial.infrastructure.web.LocationDetailController;

/**
 * <strong>The by-id detail read has no user, and that is a contract two other things rest on.</strong>
 * Runs under {@code unitTest}: reflection plus class files, no Spring context and no Docker.
 *
 * <p>Two consumers depend on it. The content worker (M4-11) has no user at all, so its POI lookup
 * must be a read that never had one — the alternative, sending some {@code X-User-Id} to reach a
 * gated read, is the class of bypass {@code ArchitectureTest} exists to block one layer up. And the
 * response is cacheable only while it is a pure function of {@code osmId}: a user dimension here is
 * the same mistake {@code CacheableNearbyContractTest} guards on the nearby path, where identity in
 * a cached payload is a correctness bug and a privacy leak in one.
 *
 * <p><strong>Why this is its own class, and why the nets are what they are.</strong> The first
 * version of this guard lived in the controller slice test and screened the handler's parameters for
 * {@code @RequestHeader} and for {@code UUID}. It was measured to be worthless against the shortest
 * real route to the bug: a handler taking {@code HttpServletRequest} and reading the header off it
 * passed the whole suite green — {@code unitTest} 110/0 and {@code integrationTest} 75/0 — because
 * ambient identity has no annotation, no suggestive parameter type and no field. That is the same
 * finding {@code CacheableNearbyContractTest} records about name-matching nets, so the shape of the
 * answer is borrowed from it: pin the <em>signature</em>, then forbid the <em>ambient</em> sources
 * structurally.
 */
class UserFreeDetailReadContractTest {

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.laa66.spatial");

	/**
	 * (1) The port's signature. This is the half the worker depends on literally: a {@code UUID}
	 * here and the only way to call it is to invent a user.
	 */
	@Test
	void theDetailPortTakesTheIdAndNothingElse() {
		Method find = port();

		assertThat(find.getParameterTypes()).containsExactly(String.class);
		assertThat(find.getParameterTypes()).doesNotContain(UUID.class);
	}

	/**
	 * (2) The handler's signature, pinned as an exact list rather than screened. A screen answers
	 * "is there a parameter that looks like identity", which is a question about imagination; an
	 * exact list answers "is there any parameter other than the two path segments", which is the
	 * property. {@code HttpServletRequest}, {@code WebRequest}, {@code HttpHeaders}, a
	 * {@code Principal} — all of them fail here now, without being named.
	 */
	@Test
	void theDetailHandlerTakesExactlyTheTwoPathSegments() {
		Method handler = handler();

		assertThat(handler.getParameterTypes())
				.as("a third parameter on this handler is a second input to a read documented as a "
						+ "pure function of the id")
				.containsExactly(String.class, String.class);
		assertThat(handler.getParameters())
				.noneMatch(parameter -> parameter.isAnnotationPresent(RequestHeader.class));
	}

	/**
	 * (3) The ambient sources, which no signature has to admit to. The servlet request and
	 * {@code RequestContextHolder} are the two ways {@code X-User-Id} reaches a method with nothing
	 * in its parameters saying so — the second one not even needing a parameter. Stated over the
	 * controller <em>and</em> over every implementation of the port, so a service that reads the
	 * header on the thread is caught as well as a handler that asks for the request.
	 *
	 * <p>Neither has a legitimate use on this path: the controller's inputs are two path variables
	 * and the implementations are framework-free domain classes. ArchUnit 1.5.1 defaults
	 * {@code failOnEmptyShould} to true, so this fails rather than passes if the {@code that()}
	 * clauses ever match nothing.
	 */
	@Test
	void neitherTheHandlerNorThePortsImplementationsCanReachAmbientIdentity() {
		noClasses()
				.that().belongToAnyOf(LocationDetailController.class)
				.or().implement(FindLocationDetail.class)
				.should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..",
						"org.springframework.web.context.request..")
				.because("the by-id read is user-free, and the servlet request is how a user would "
						+ "arrive without any signature admitting it")
				.check(PRODUCTION_CLASSES);
	}

	private static Method handler() {
		return Stream.of(LocationDetailController.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("detail"))
				.reduce((a, b) -> {
					throw new IllegalStateException("more than one detail(..) on the controller");
				})
				.orElseThrow(() -> new IllegalStateException("no detail(..) on the controller"));
	}

	private static Method port() {
		return Stream.of(FindLocationDetail.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("find"))
				.reduce((a, b) -> {
					throw new IllegalStateException("more than one find(..) on the detail port");
				})
				.orElseThrow(() -> new IllegalStateException("no find(..) on the detail port"));
	}
}
