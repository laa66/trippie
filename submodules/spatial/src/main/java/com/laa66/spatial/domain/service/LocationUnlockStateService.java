package com.laa66.spatial.domain.service;

import java.util.List;
import java.util.UUID;

import com.laa66.spatial.domain.port.in.CheckLocationUnlocked;
import com.laa66.spatial.domain.port.out.UnlockRepository;

/**
 * Application service for the single-POI unlock read. Reuses {@link UnlockRepository#findUnlocked}
 * rather than adding a second statement: that method is already scoped to one user by construction
 * — there is no signature on it that can return another user's rows — and a one-element batch hits
 * the same {@code (user_id, osm_id)} primary key as a fifty-element one.
 *
 * <p>Framework-free by design; wired in {@code infrastructure.config}.
 */
public class LocationUnlockStateService implements CheckLocationUnlocked {

	private final UnlockRepository unlockRepository;

	public LocationUnlockStateService(UnlockRepository unlockRepository) {
		this.unlockRepository = unlockRepository;
	}

	@Override
	public boolean isUnlocked(UUID userId, String osmId) {
		return unlockRepository.findUnlocked(userId, List.of(osmId)).contains(osmId);
	}
}
