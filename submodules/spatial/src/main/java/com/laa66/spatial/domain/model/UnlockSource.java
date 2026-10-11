package com.laa66.spatial.domain.model;

/**
 * Why an unlock row exists. The CHECK on {@code poi_unlock.granted_by} is the enforcement point
 * for this contract, so the constant names must stay identical to the two values it allows.
 *
 * <p>{@code PROXIMITY} is a measured fix inside the unlock radius; {@code EXEMPT} is a grant made
 * because the user turned the proximity requirement off. The two are not interchangeable and the
 * distinction cannot be recovered later — there is no re-lock — so it is persisted at grant time.
 */
public enum UnlockSource {

	PROXIMITY,
	EXEMPT
}
