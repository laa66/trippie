package com.laa66.spatial.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyPointForUser;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.model.NearbyResultForUser;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.out.UnlockRepository;

/**
 * The decoration without a database: which query reaches the inner port, which subject reaches the
 * unlock read, and how the two are zipped. Runs under {@code unitTest}, so these assertions hold on
 * a machine with no Docker.
 *
 * <p>The structural half of the same argument — that the inner port cannot grow a user dimension in
 * the first place — is {@code CacheableNearbyContractTest}.
 */
class NearbyLocationsForUserServiceTest {

	private static final UUID USER = UUID.fromString("44444444-4444-4444-8444-444444444444");
	private static final UUID OTHER_USER = UUID.fromString("55555555-5555-4555-8555-555555555555");

	private final FindNearbyLocations findNearbyLocations = mock(FindNearbyLocations.class);
	private final UnlockRepository unlockRepository = mock(UnlockRepository.class);
	private final NearbyLocationsForUserService service =
			new NearbyLocationsForUserService(findNearbyLocations, unlockRepository);

	/**
	 * The inner port is called with the caller's query, unchanged and unaugmented. It is the unit
	 * M6's cache decorator will wrap, so anything added on the way in would become part of a cache
	 * key that is frozen without it.
	 */
	@Test
	void theInnerPortIsCalledWithTheQueryExactlyAsGiven() {
		NearbyQuery query = new NearbyQuery(51.11, 17.03, 800, Set.of(Category.MUSEUMS), 50);
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(), false));
		when(unlockRepository.findUnlocked(any(), anyCollection())).thenReturn(Set.of());

		service.find(USER, query);

		ArgumentCaptor<NearbyQuery> captor = ArgumentCaptor.forClass(NearbyQuery.class);
		verify(findNearbyLocations).find(captor.capture());
		assertThat(captor.getValue()).isEqualTo(query);
	}

	/**
	 * <strong>The user filter, pinned at the one place it is applied.</strong> Dropping the subject
	 * here — or substituting any other — is the mistake that would hand one user another's unlocks,
	 * and it is invisible in a single-user test.
	 */
	@Test
	void theUnlockReadIsScopedToTheCallersSubject() {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(point("node/a", 10)), false));
		when(unlockRepository.findUnlocked(eq(USER), anyCollection())).thenReturn(Set.of("node/a"));
		when(unlockRepository.findUnlocked(eq(OTHER_USER), anyCollection())).thenReturn(Set.of());

		assertThat(unlockedIds(service.find(USER, query()))).containsExactly("node/a");
		assertThat(unlockedIds(service.find(OTHER_USER, query()))).isEmpty();

		verify(unlockRepository).findUnlocked(eq(USER), anyCollection());
		verify(unlockRepository).findUnlocked(eq(OTHER_USER), anyCollection());
	}

	/** One read for the whole page, over exactly the ids the page returned — not one read per item. */
	@Test
	void theUnlockReadIsOneBatchOverTheReturnedIds() {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(
				List.of(point("node/a", 10), point("node/b", 20), point("node/c", 30)), false));
		when(unlockRepository.findUnlocked(any(), anyCollection())).thenReturn(Set.of());

		service.find(USER, query());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(unlockRepository, times(1)).findUnlocked(eq(USER), captor.capture());
		assertThat(captor.getValue()).containsExactly("node/a", "node/b", "node/c");
	}

	@Test
	void eachItemCarriesItsOwnUnlockState() {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(
				List.of(point("node/locked", 10), point("node/unlocked", 20)), false));
		when(unlockRepository.findUnlocked(any(), anyCollection())).thenReturn(Set.of("node/unlocked"));

		NearbyResultForUser result = service.find(USER, query());

		assertThat(result.items()).extracting(item -> item.point().osmId(), NearbyPointForUser::unlocked)
				.containsExactly(tuple("node/locked", false), tuple("node/unlocked", true));
	}

	/**
	 * Decoration adds a field and changes nothing else: order, the projection and {@code truncated}
	 * are the inner result's answer, carried through. The cap is the cacheable layer's business, and
	 * an unlock must not promote or demote a POI within a page.
	 */
	@Test
	void orderProjectionAndTruncatedComeStraightFromTheInnerResult() {
		LocationPoint near = point("node/near", 10);
		LocationPoint far = point("node/far", 900);
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(near, far), true));
		when(unlockRepository.findUnlocked(any(), anyCollection())).thenReturn(Set.of("node/far"));

		NearbyResultForUser result = service.find(USER, query());

		assertThat(result.truncated()).isTrue();
		assertThat(result.items()).extracting(NearbyPointForUser::point).containsExactly(near, far);
	}

	/**
	 * A POI the unlock table knows about but this page does not must not appear from nowhere: the
	 * response is the geo query's page, decorated — never the union of the two queries.
	 */
	@Test
	void anUnlockedPoiOutsideThisPageIsNotAdded() {
		when(findNearbyLocations.find(any())).thenReturn(new NearbyResult(List.of(point("node/a", 10)), false));
		when(unlockRepository.findUnlocked(any(), anyCollection())).thenReturn(Set.of("node/a", "node/elsewhere"));

		NearbyResultForUser result = service.find(USER, query());

		assertThat(result.items()).extracting(item -> item.point().osmId()).containsExactly("node/a");
	}

	private static NearbyQuery query() {
		return new NearbyQuery(51.11, 17.03, 1000, Set.of(), 100);
	}

	private static LocationPoint point(String osmId, double distanceMeters) {
		return new LocationPoint(osmId, "name-" + osmId, Category.MONUMENTS, 51.11, 17.03, distanceMeters);
	}

	private static List<String> unlockedIds(NearbyResultForUser result) {
		return result.items().stream()
				.filter(NearbyPointForUser::unlocked)
				.map(item -> item.point().osmId())
				.toList();
	}
}
