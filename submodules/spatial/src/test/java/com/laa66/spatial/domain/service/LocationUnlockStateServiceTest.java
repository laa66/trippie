package com.laa66.spatial.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.laa66.spatial.domain.port.out.UnlockRepository;

class LocationUnlockStateServiceTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");

	private final UnlockRepository unlockRepository = mock(UnlockRepository.class);

	private final LocationUnlockStateService service = new LocationUnlockStateService(unlockRepository);

	@Test
	void aGrantedPoiIsUnlocked() {
		when(unlockRepository.findUnlocked(eq(USER), any())).thenReturn(Set.of("node/42"));

		assertThat(service.isUnlocked(USER, "node/42")).isTrue();
	}

	@Test
	void anUngrantedPoiIsNotUnlocked() {
		when(unlockRepository.findUnlocked(eq(USER), any())).thenReturn(Set.of());

		assertThat(service.isUnlocked(USER, "node/42")).isFalse();
	}

	/**
	 * The repository is asked about exactly the user and the one id in the request. Captured rather
	 * than stubbed loosely, because the failure this guards — a hardcoded or dropped user — answers
	 * about somebody else's rows while every status code stays 200.
	 */
	@Test
	void exactlyTheRequestedUserAndIdAreQueried() {
		when(unlockRepository.findUnlocked(any(), any())).thenReturn(Set.of());

		service.isUnlocked(USER, "way/7");

		ArgumentCaptor<UUID> userId = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<Collection<String>> osmIds = ArgumentCaptor.captor();
		verify(unlockRepository).findUnlocked(userId.capture(), osmIds.capture());
		assertThat(userId.getValue()).isEqualTo(USER);
		assertThat(osmIds.getValue()).containsExactly("way/7");
	}

	/**
	 * A repository answering about a POI nobody asked about does not make the requested one unlocked.
	 * The guard is against {@code !result.isEmpty()} standing in for {@code contains(osmId)} — which
	 * is green on every single-id test above.
	 */
	@Test
	void aForeignIdInTheAnswerDoesNotUnlockTheRequestedOne() {
		when(unlockRepository.findUnlocked(any(), any())).thenReturn(Set.of("node/999"));

		assertThat(service.isUnlocked(USER, "node/42")).isFalse();
	}
}
