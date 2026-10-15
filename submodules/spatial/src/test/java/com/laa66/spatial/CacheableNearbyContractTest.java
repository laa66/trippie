package com.laa66.spatial;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import com.laa66.spatial.domain.model.NearbyPointForUser;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.in.FindNearbyLocationsForUser;
import com.laa66.spatial.domain.port.out.LocationRepository;
import com.laa66.spatial.domain.port.out.UnlockRepository;

/**
 * <strong>The unit M6 will cache must not contain unlock state, and this is the only thing that
 * enforces it.</strong> Runs under {@code unitTest}: reflection plus class files, no Spring context
 * and no Docker.
 *
 * <p>M6's frozen cache key is {@code spatial:nearby:{geohash6}:{radiusBucket}:{sortedCategoryCsv}}
 * — no user dimension, deliberately, because what it caches is a pure function of position, radius
 * and categories. The moment a per-user field exists anywhere inside what
 * {@link FindNearbyLocations} returns, that payload gets stored under a key that cannot tell users
 * apart and handed to whoever asks next: one user's unlocks served to another, which is a
 * correctness bug and a privacy leak in the same mistake. The split that avoids it —
 * {@link FindNearbyLocationsForUser} decorating outside the cacheable port — is invisible at
 * runtime and easy to undo in a refactor that looks like a simplification ("why two ports?", "the
 * flag belongs on the point"). Hence an explicit guard rather than a note in a review.
 *
 * <p>Three properties, because there are three distinct ways to break it:
 * <ol>
 * <li>the cacheable port's <em>signature</em> could grow a subject;
 * <li>the types it returns could grow an unlock field, directly or through a new nested type;
 * <li>its implementation could read unlock state itself, leaving the shapes innocent.
 * </ol>
 */
class CacheableNearbyContractTest {

	/**
	 * Every record component reachable from {@link FindNearbyLocations#find}, frozen. Reachability
	 * rather than a field list, so a new nested type carrying the flag is caught as well as a new
	 * field on an existing one.
	 */
	private static final List<String> FROZEN_CACHEABLE_SHAPE = List.of(
			"NearbyQuery.lat: double",
			"NearbyQuery.lon: double",
			"NearbyQuery.radiusMeters: int",
			"NearbyQuery.categories: java.util.Set<com.laa66.spatial.domain.model.Category>",
			"NearbyQuery.limit: int",
			"NearbyResult.items: java.util.List<com.laa66.spatial.domain.model.LocationPoint>",
			"NearbyResult.truncated: boolean",
			"LocationPoint.osmId: java.lang.String",
			"LocationPoint.name: java.lang.String",
			"LocationPoint.category: com.laa66.spatial.domain.model.Category",
			"LocationPoint.lat: double",
			"LocationPoint.lon: double",
			"LocationPoint.distanceMeters: double");

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.laa66.spatial");

	/**
	 * (1) The cacheable port takes the cache key's inputs and nothing else. A
	 * {@code find(UUID, NearbyQuery)} here, or a {@code userId} on {@link NearbyQuery}, is the most
	 * direct version of the mistake: the decorator's key would then be computed from only part of
	 * its arguments, so two users would collide on it.
	 */
	@Test
	void theCacheablePortTakesOnlyTheCacheKeysInputs() {
		Method[] methods = FindNearbyLocations.class.getDeclaredMethods();

		assertThat(methods).hasSize(1);
		assertThat(methods[0].getName()).isEqualTo("find");
		assertThat(methods[0].getParameterTypes())
				.as("a second argument here is a cache-key input the frozen key does not carry")
				.containsExactly(com.laa66.spatial.domain.model.NearbyQuery.class);
		assertThat(methods[0].getReturnType()).isEqualTo(com.laa66.spatial.domain.model.NearbyResult.class);
	}

	/**
	 * (2) Nothing reachable from that signature carries unlock state. Compared against a frozen list
	 * rather than screened by field name, because a blacklist of names ({@code unlocked},
	 * {@code userId}) is exactly as good as the imagination of whoever wrote it — {@code granted} or
	 * {@code visited} would sail past. An addition anywhere in the reachable shape fails here and
	 * has to be argued for, which is the point.
	 */
	@Test
	void nothingReachableFromTheCacheablePortCarriesUnlockState() {
		assertThat(reachableRecordComponents())
				.as("the cacheable payload's shape is frozen together with M6's cache key, which has no "
						+ "user dimension. What to do with this failure: if the new field is genuinely "
						+ "user-free, add it to FROZEN_CACHEABLE_SHAPE and this is a one-line change; if it "
						+ "is per-user, its home is NearbyPointForUser, outside the cacheable port. What "
						+ "this assertion must not become is deleted.")
				.containsExactlyInAnyOrderElementsOf(FROZEN_CACHEABLE_SHAPE);
	}

	/**
	 * Says where the flag <em>did</em> go, which the assertion above cannot: that it exists, is called
	 * what the wire contract calls it, and lives on a type the walk does not reach.
	 *
	 * <p>The {@code doesNotContain} would on its own be satisfied by a walk that found nothing, so the
	 * size is pinned beside it. The frozen-shape assertion above is <em>not</em> vacuously satisfiable
	 * — {@code containsExactlyInAnyOrderElementsOf} over thirteen elements fails on an empty actual —
	 * and an earlier version of this javadoc claimed it was, which was simply wrong.
	 */
	@Test
	void theUnlockFlagExistsAndSitsOutsideThatShape() {
		assertThat(NearbyPointForUser.class.getRecordComponents())
				.extracting(RecordComponent::getName)
				.contains("unlocked");

		assertThat(reachableTypes())
				.as("NearbyQuery, NearbyResult, LocationPoint, Category: a walk reaching fewer types "
						+ "than this proves nothing about what it never visited")
				.hasSize(4);

		assertThat(reachableTypes())
				.as("the type that carries the flag must not be reachable from the cacheable port")
				.doesNotContain(NearbyPointForUser.class);
	}

	/**
	 * (3) The shapes can stay innocent while the implementation reads per-user state and, say, sorts
	 * or filters by it — which would make the cached answer depend on who is asking without any field
	 * admitting to it. So whatever implements the cacheable port may not reach a source of identity at
	 * all.
	 *
	 * <p>The rule is stated over "classes implementing {@link FindNearbyLocations}" so that it also
	 * covers M6's cache decorator the day it is written, and ArchUnit's {@code failOnEmptyShould}
	 * default makes it fail rather than pass if that {@code that()} clause ever matches nothing.
	 *
	 * <p><strong>Four nets, in the order of how much they actually hold</strong> — the first version of
	 * this rule was the name-matching net alone, and it was measured to be bypassed by a second
	 * outbound port named {@code PoiAccessReader} that delegated to {@code findUnlocked}: all four
	 * tests here stayed green. A blacklist of names is worth exactly the imagination of whoever wrote
	 * it, which is the objection this test raises against screening fields by name, and it applied
	 * just as well to screening types by name.
	 * <ol>
	 * <li><strong>The outbound-port allowlist.</strong> {@link LocationRepository} is the only
	 * outbound port the cacheable layer has any business holding. Anything else in
	 * {@code domain.port.out} — today's {@link UnlockRepository}, tomorrow's second way to ask the
	 * same question — is caught whatever it is called.
	 * <li><strong>Assignability to {@link UnlockRepository}.</strong> Covers a subtype or wrapper of
	 * that port declared outside {@code domain.port.out}, where the allowlist above cannot see it.
	 * <li><strong>Ambient identity.</strong> {@code RequestContextHolder.currentRequestAttributes()}
	 * reads {@code X-User-Id} off the thread with no signature, no field and no suggestive name,
	 * which is the shortest route to this bug for anyone not thinking in ports. Depending on
	 * {@code org.springframework.web..} from an implementation of this port has no legitimate use —
	 * the domain is framework-free and M6's decorator needs Redis, not the servlet request.
	 * <li><strong>The names.</strong> Kept as the last net: it adds nothing the first two do not
	 * already hold, but it costs nothing and it fires on a type that is about unlocking while being
	 * neither a port nor a subtype of one.
	 * </ol>
	 *
	 * <p>Written as one OR-ed predicate rather than {@code should().andShould()} deliberately: under
	 * {@code noClasses()} a conjunction of two {@code should}s reads as "no class should do both",
	 * which is <em>weaker</em> than either clause alone and would be a trap for exactly the next
	 * person to extend this.
	 *
	 * <p><strong>What this still does not catch</strong>, named rather than implied: a ThreadLocal of
	 * our own, set in a filter and read here, is identity reaching the cacheable layer through code
	 * that no rule here objects to. Nothing structural sees that; it is review's job, and the reason
	 * the behavioural half of this argument lives in {@code NearbyForUserIntegrationTest}.
	 */
	@Test
	void theCacheablePortsImplementationsCannotReachIdentity() {
		noClasses()
				.that().implement(FindNearbyLocations.class)
				.should().dependOnClassesThat(describe(
						"a source of per-user state: an outbound port other than LocationRepository, "
								+ "anything assignable to UnlockRepository, the servlet request, "
								+ "or a type named for unlocking",
						(JavaClass target) -> isForeignOutboundPort(target)
								|| target.isAssignableTo(UnlockRepository.class)
								|| target.getPackageName().startsWith("org.springframework.web")
								|| target.getSimpleName().contains("Unlock")
								|| target.getSimpleName().endsWith("ForUser")))
				.because("this port is what M6's Redis decorator caches under a key with no user "
						+ "dimension, so its answer must not depend on who is asking")
				.check(PRODUCTION_CLASSES);
	}

	private static boolean isForeignOutboundPort(JavaClass target) {
		return target.getPackageName().startsWith("com.laa66.spatial.domain.port.out")
				&& !target.isEquivalentTo(LocationRepository.class);
	}

	private static List<String> reachableRecordComponents() {
		List<String> components = new ArrayList<>();
		for (Class<?> type : reachableTypes()) {
			if (type.isRecord()) {
				for (RecordComponent component : type.getRecordComponents()) {
					components.add("%s.%s: %s".formatted(
							type.getSimpleName(), component.getName(), component.getGenericType().getTypeName()));
				}
			}
		}
		return components;
	}

	/** Transitive closure of the domain types reachable from the cacheable port's signature. */
	private static Set<Class<?>> reachableTypes() {
		// By name: getDeclaredMethods' order is unspecified, and while the first assertion in this
		// class pins that there is only one method, a helper must not rely on another test's subject.
		Method find = Stream.of(FindNearbyLocations.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("find"))
				.reduce((a, b) -> {
					throw new IllegalStateException("more than one find(..) on the cacheable port");
				})
				.orElseThrow(() -> new IllegalStateException("no find(..) on the cacheable port"));

		Set<Class<?>> reached = new LinkedHashSet<>();
		for (Type parameter : find.getGenericParameterTypes()) {
			collect(parameter, reached);
		}
		collect(find.getGenericReturnType(), reached);
		reached.removeIf(type -> !type.getName().startsWith("com.laa66.spatial"));
		return reached;
	}

	/**
	 * <strong>Every {@link Type} shape is either walked or thrown on, never ignored.</strong> A walk
	 * that silently stops is the one failure mode this whole class cannot detect from the outside: an
	 * {@code items} list turned into a {@code LocationPoint[]} "for performance" used to end the walk
	 * right there, after which a per-user field on the element type passed the frozen-shape assertion.
	 * So arrays recurse through their component type, and an unhandled shape — a wildcard
	 * ({@code List<? extends LocationPoint>}), a type variable, a generic array — fails the test
	 * instead of narrowing it.
	 */
	private static void collect(Type type, Set<Class<?>> reached) {
		if (type instanceof Class<?> clazz) {
			if (clazz.isArray()) {
				collect(clazz.getComponentType(), reached);
				return;
			}
			if (!reached.add(clazz) || !clazz.isRecord()) {
				return;
			}
			for (RecordComponent component : clazz.getRecordComponents()) {
				collect(component.getGenericType(), reached);
			}
		}
		else if (type instanceof ParameterizedType parameterized) {
			collect(parameterized.getRawType(), reached);
			for (Type argument : parameterized.getActualTypeArguments()) {
				collect(argument, reached);
			}
		}
		else {
			throw new IllegalStateException(
					"unhandled type shape in the cacheable port's signature, so this walk no longer "
							+ "covers it: " + type.getTypeName() + " (" + type.getClass().getName() + ")");
		}
	}
}
