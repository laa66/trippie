package com.laa66.spatial.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.spatial.support.AbstractPostgisIntegrationTest;
import com.laa66.spatial.support.PoiSeeder;

/**
 * {@code GET /locations/{osmId}} over a real PostGIS container and the shipped wiring. What only a
 * database can show is here: that {@code description} and {@code tags} — the two columns the nearby
 * projection deliberately leaves off — come back for a row that was really written, and what the
 * JSONB flattening does to values OSM's mappers can actually produce.
 *
 * <p>No {@code UserPreferences} replacement and no unlock anywhere in this class: the endpoint has
 * no user, and a test that needed one would be evidence that it had grown one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationDetailIntegrationTest extends AbstractPostgisIntegrationTest {

	private static final double OBSERVER_LAT = 51.11;
	private static final double OBSERVER_LON = 17.03;

	private static final String POI = "node/42";

	/**
	 * One document covering every value shape the contract has an answer for: a string, a number, a
	 * boolean, an object, an array and a JSON null. The last three are what makes this the test of
	 * the task — a mapper nesting a value must not cost the endpoint a 500.
	 */
	private static final String TAGS = """
			{
			  "wikidata": "Q123",
			  "start_date": 1299,
			  "wheelchair": true,
			  "name:translations": {"de": "Rathaus"},
			  "building:levels": [1, 2],
			  "operator": null
			}
			""";

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGIS::getUsername);
		registry.add("spring.datasource.password", POSTGIS::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	private PoiSeeder poi;

	@BeforeEach
	void seed() {
		poi = new PoiSeeder(dataSource, OBSERVER_LAT, OBSERVER_LON);
		poi.reset();
		poi.seedPoiWithDetail(POI, 10, "Ratusz", "Gothic town hall on the market square.", TAGS);
	}

	/** The two columns this endpoint exists for, read off a seeded row. */
	@Test
	void theDetailCarriesTheSeededDescriptionAndTags() throws Exception {
		mvc.perform(get("/locations/" + POI))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(POI))
				.andExpect(jsonPath("$.name").value("Ratusz"))
				.andExpect(jsonPath("$.category").value("heritage"))
				.andExpect(jsonPath("$.description").value("Gothic town hall on the market square."))
				.andExpect(jsonPath("$.tags.wikidata").value("Q123"));
	}

	/** Scalars arrive as text, whatever their JSON type: the map is string→string on the wire. */
	@Test
	void nonStringScalarsAreProjectedAsText() throws Exception {
		mvc.perform(get("/locations/" + POI))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags.start_date").value("1299"))
				.andExpect(jsonPath("$.tags.wheelchair").value("true"));
	}

	/**
	 * <strong>The accept criterion of the tag half: dropped, not a 500.</strong> A nested object, an
	 * array and a JSON null are all absent from the map, and the request is still a 200 carrying the
	 * scalars beside them.
	 */
	@Test
	void nonScalarTagValuesAreDroppedRatherThanFailingTheRead() throws Exception {
		mvc.perform(get("/locations/" + POI))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags['name:translations']").doesNotExist())
				.andExpect(jsonPath("$.tags['building:levels']").doesNotExist())
				.andExpect(jsonPath("$.tags.operator").doesNotExist())
				.andExpect(jsonPath("$.tags.wikidata").value("Q123"));
	}

	/**
	 * A POI with no tags at all still has a row, which is the {@code LEFT JOIN} in {@code DETAIL_SQL}:
	 * an inner join would turn "no tags" into the 404 that belongs to unknown ids only.
	 */
	@Test
	void aPoiWithoutTagsIsStillFound() throws Exception {
		poi.seedPoiWithDetail("node/43", 20, "Bez tagów", null, "{}");

		// Strict compare, so the null description is pinned as a present key rather than as an absent
		// one: the key set must not depend on the data.
		mvc.perform(get("/locations/node/43"))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"id": "node/43", "name": "Bez tagów", "category": "heritage",
						 "description": null, "tags": {}}
						""", JsonCompareMode.STRICT));
	}

	/**
	 * <strong>The sibling of the criterion above, one level up: a {@code tags} document that is not an
	 * object at all.</strong> Measured before the {@code CASE} in {@code DETAIL_SQL} went in:
	 * {@code jsonb_each} is defined on objects only, so this seeded row answered 500 with
	 * {@code cannot call jsonb_each on a non-object}. The column's type allows it even though today's
	 * loader cannot write it, and "the POI has no usable tags" is the same answer a non-scalar value
	 * already gets.
	 */
	@Test
	void aTagsDocumentThatIsNotAnObjectYieldsNoTagsRatherThanAnError() throws Exception {
		poi.seedPoiWithDetail("node/44", 30, "Tablica", "Array instead of an object.", "[1, 2]");

		mvc.perform(get("/locations/node/44"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Array instead of an object."))
				.andExpect(jsonPath("$.tags").isEmpty());
	}

	/** The same for a bare JSON scalar and for {@code 'null'}, which fail inside {@code jsonb_each} too. */
	@Test
	void aScalarOrNullTagsDocumentYieldsNoTagsRatherThanAnError() throws Exception {
		poi.seedPoiWithDetail("node/45", 40, "Skalar", null, "\"just a string\"");
		poi.seedPoiWithDetail("node/46", 50, "JSON null", null, "null");

		mvc.perform(get("/locations/node/45"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags").isEmpty());
		mvc.perform(get("/locations/node/46"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags").isEmpty());
	}

	@Test
	void anUnknownPoiIsNotFound() throws Exception {
		mvc.perform(get("/locations/node/999")).andExpect(status().isNotFound());
	}

	/**
	 * The shipped wiring requires no {@code X-User-Id} here either, not just the controller slice —
	 * which is what M4-11's worker depends on, since it has no user to send.
	 */
	@Test
	void theShippedEndpointNeedsNoUserHeader() throws Exception {
		mvc.perform(get("/locations/" + POI))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(POI));
	}

	/**
	 * The id in the URL is the full {@code "<type>/<id>"}, so a request carrying only its second
	 * segment matches no route at all. Pinned because the alternative — a single-segment template
	 * silently matching something — is how this endpoint would start answering about the wrong POI.
	 */
	@Test
	void aBareNumericIdIsNotARoute() throws Exception {
		mvc.perform(get("/locations/42")).andExpect(status().isNotFound());
	}
}
