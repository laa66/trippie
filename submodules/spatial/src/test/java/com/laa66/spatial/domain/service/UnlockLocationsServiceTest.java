package com.laa66.spatial.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.laa66.spatial.domain.model.InsufficientAccuracyException;
import com.laa66.spatial.domain.model.UnlockAttempt;
import com.laa66.spatial.domain.model.UnlockGrant;
import com.laa66.spatial.domain.model.UnlockResult;
import com.laa66.spatial.domain.model.UnlockSource;
import com.laa66.spatial.domain.port.out.UnlockRepository;
import com.laa66.spatial.domain.port.out.UserPreferences;

/**
 * The unlock policy without a database: which source a grant gets, which radius it carries, when
 * the accuracy gate fires, and how the repository's answer is partitioned. Runs under
 * {@code unitTest}, so these assertions hold on a machine with no Docker.
 */
class UnlockLocationsServiceTest {

	private static final UUID USER = UUID.fromString("33333333-3333-4333-8333-333333333333");

	private final UnlockRepository unlockRepository = mock(UnlockRepository.class);
	private final UserPreferences userPreferences = mock(UserPreferences.class);
	private final UnlockLocationsService service = new UnlockLocationsService(unlockRepository, userPreferences);

	// --- the preference read ---------------------------------------------------------------

	/**
	 * Read once per attempt, on the write path, before anything is granted. Twice would be two
	 * answers for one row, and caching it across attempts would make a toggle whose purpose is
	 * manual testing lag behind the user.
	 */
	@Test
	void preferenceIsReadExactlyOncePerAttempt() {
		when(userPreferences.requireProximity(USER)).thenReturn(true);
		when(unlockRepository.grant(any())).thenReturn(Set.of());

		service.unlock(attempt(10, "node/a"));

		verify(userPreferences, times(1)).requireProximity(USER);
	}

	@Test
	void requiredProximityGrantsFromTheProximitySourceAtTheFrozenRadius() {
		when(userPreferences.requireProximity(USER)).thenReturn(true);
		when(unlockRepository.grant(any())).thenReturn(Set.of("node/a"));

		service.unlock(attempt(10, "node/a"));

		UnlockGrant grant = capturedGrant();
		assertThat(grant.source()).isEqualTo(UnlockSource.PROXIMITY);
		assertThat(grant.radiusMeters()).isEqualTo(50);
		assertThat(grant.userId()).isEqualTo(USER);
		assertThat(grant.lat()).isEqualTo(51.11);
		assertThat(grant.lon()).isEqualTo(17.03);
		assertThat(grant.osmIds()).containsExactly("node/a");
	}

	@Test
	void waivedProximityGrantsFromTheExemptSource() {
		when(userPreferences.requireProximity(USER)).thenReturn(false);
		when(unlockRepository.grant(any())).thenReturn(Set.of("node/a"));

		service.unlock(attempt(10, "node/a"));

		assertThat(capturedGrant().source()).isEqualTo(UnlockSource.EXEMPT);
	}

	// --- the accuracy gate ------------------------------------------------------------------

	@Test
	void accuracyAtTheThresholdIsAccepted() {
		when(userPreferences.requireProximity(USER)).thenReturn(true);
		when(unlockRepository.grant(any())).thenReturn(Set.of("node/a"));

		assertThat(service.unlock(attempt(100, "node/a")).unlocked()).containsExactly("node/a");
	}

	/** One metre past the threshold, and nothing reaches the repository at all. */
	@Test
	void accuracyPastTheThresholdIsRejectedWithoutTouchingTheRepository() {
		when(userPreferences.requireProximity(USER)).thenReturn(true);

		assertThatThrownBy(() -> service.unlock(attempt(101, "node/a")))
				.isInstanceOf(InsufficientAccuracyException.class);

		verifyNoInteractions(unlockRepository);
	}

	/**
	 * The gate belongs to proximity enforcement, so turning the requirement off lifts it too. It
	 * exists to stop an unmeasured position standing in for a measured one, and an exempt grant does
	 * not consult the position; keeping it would also break the toggle as the manual-testing route,
	 * since a DevTools geolocation override reports accuracy around 150 m.
	 */
	@Test
	void anExemptUserIsNotHeldToTheAccuracyThreshold() {
		when(userPreferences.requireProximity(USER)).thenReturn(false);
		when(unlockRepository.grant(any())).thenReturn(Set.of("node/a"));

		assertThat(service.unlock(attempt(5000, "node/a")).unlocked()).containsExactly("node/a");
		assertThat(capturedGrant().source()).isEqualTo(UnlockSource.EXEMPT);
	}

	// --- partitioning ------------------------------------------------------------------------

	@Test
	void requestedIdsTheRepositoryDidNotReturnAreRefused() {
		when(userPreferences.requireProximity(USER)).thenReturn(true);
		when(unlockRepository.grant(any())).thenReturn(Set.of("node/a"));

		UnlockResult result = service.unlock(attempt(10, "node/a", "node/b", "node/c"));

		assertThat(result.unlocked()).containsExactly("node/a");
		assertThat(result.refused()).containsExactlyInAnyOrder("node/b", "node/c");
	}

	/** Anything outside the request cannot reach the response, whatever the repository returns. */
	@Test
	void anIdOutsideTheRequestIsNotReportedAsUnlocked() {
		when(userPreferences.requireProximity(USER)).thenReturn(true);
		when(unlockRepository.grant(any())).thenReturn(Set.of("node/a", "node/unrequested"));

		UnlockResult result = service.unlock(attempt(10, "node/a"));

		assertThat(result.unlocked()).containsExactly("node/a");
		assertThat(result.refused()).isEmpty();
	}

	private UnlockAttempt attempt(double accuracyMeters, String... osmIds) {
		return new UnlockAttempt(USER, 51.11, 17.03, accuracyMeters, new LinkedHashSet<>(List.of(osmIds)));
	}

	private UnlockGrant capturedGrant() {
		ArgumentCaptor<UnlockGrant> captor = ArgumentCaptor.forClass(UnlockGrant.class);
		verify(unlockRepository).grant(captor.capture());
		return captor.getValue();
	}
}
