package com.laa66.spatial.infrastructure.web;

/**
 * Wire shape for the single-POI unlock read: {@code { unlocked }}.
 *
 * <p>One field, and no POI id echoed back beside it. The request names the POI in its path, so
 * repeating it here would only create a second place for the two to disagree.
 */
record UnlockStateResponse(boolean unlocked) {
}
