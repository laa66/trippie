package com.laa66.spatial.infrastructure.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.domain.port.in.FindLocationDetail;

/**
 * Inbound HTTP adapter for the by-id detail read. Drives {@link FindLocationDetail} only.
 *
 * <p><strong>User-free, and that is a contract and not an omission.</strong> There is no
 * {@code X-User-Id} on this method and no subject on the port behind it, so the response is a pure
 * function of the id: cacheable as it stands, and readable by the content worker, which has no user
 * to assert. The alternative — a worker sending some {@code X-User-Id} to reach a gated read — is
 * the bypass {@code ArchitectureTest} exists to prevent, one layer up.
 *
 * <p><strong>Two path variables, one id, and the URL is the one the contract names.</strong>
 * {@code osm_id} is {@code "<type>/<id>"} — {@code node/42}, {@code way/7} — written by the Go
 * loader, so the public id contains a slash. A single {@code {osmId}} template matches one path
 * segment and would never match a real id; percent-encoding the slash is worse than it looks, since
 * Tomcat rejects {@code %2F} in a path by default while MockMvc does not, which is a test suite that
 * passes against an endpoint that 400s in production. So the id is spelled as the two segments it
 * already is: {@code GET /locations/node/42} is exactly the URL {@code GET /locations/{osmId}}
 * describes, and a caller holding an id concatenates it onto the path with no encoding step.
 *
 * <p>The id is reassembled and looked up verbatim — a bogus type is simply not in the table, so
 * {@code /locations/banana/42} is a 404 like any other unknown id. Nothing is validated here
 * beyond that, because nothing downstream interprets the id: it is a bound parameter of a
 * primary-key lookup.
 *
 * <p>Collisions with the two existing {@code /locations/*} routes are therefore structural rather
 * than a matter of pattern precedence — {@code /locations/nearby} and {@code /locations/unlock} are
 * one segment, this is two — and {@code LocationRoutingTest} pins it either way.
 */
@RestController
public class LocationDetailController {

	private final FindLocationDetail findLocationDetail;

	public LocationDetailController(FindLocationDetail findLocationDetail) {
		this.findLocationDetail = findLocationDetail;
	}

	@GetMapping("/locations/{osmType}/{osmRef}")
	public LocationDetailResponse detail(@PathVariable String osmType, @PathVariable String osmRef) {
		return findLocationDetail.find(osmType + "/" + osmRef)
				.map(LocationDetailResponse::from)
				.orElseThrow(LocationNotFoundException::new);
	}
}
