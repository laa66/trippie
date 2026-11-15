package com.laa66.worker.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.PoiDetail;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.support.SpatialStub;

class SpatialPoiDetailsAdapterTest {

	private final SpatialStub spatial = new SpatialStub();

	private final SpatialPoiDetailsAdapter adapter =
			new SpatialPoiDetailsAdapter(RestClient.builder().baseUrl(spatial.baseUrl()).build());

	@AfterEach
	void stop() {
		spatial.close();
	}

	/** The URL the contract names: two segments, the slash not encoded. */
	@Test
	void aKnownPoiIsReadFromItsTwoSegmentPathAndOnlyNameAndCategoryAreKept() {
		spatial.poi("node/42", "Ratusz", "architecture");

		assertThat(adapter.find("node/42")).contains(new PoiDetail("Ratusz", "architecture"));
		assertThat(spatial.paths()).containsExactly("/locations/node/42");
	}

	/** Spatial's own 404 carries its {@code application/problem+json} body. */
	@Test
	void spatials404IsAnUnknownPoi() {
		assertThat(adapter.find("node/7")).isEmpty();
	}

	/** User decision 2026-11-12: spatial's problem 404 for an unknown route is not an unknown POI. */
	@Test
	void aProblem404WithAnotherDetailStopsTheConsumer() {
		SpatialPoiDetailsAdapter wrongPrefix =
				new SpatialPoiDetailsAdapter(RestClient.builder().baseUrl(spatial.baseUrl() + "/api").build());

		assertThat(catchThrowable(() -> wrongPrefix.find("node/42")))
				.isExactlyInstanceOf(ConsumerMustStopException.class)
				.hasMessage("spatial answered HTTP 404 to the detail of node/42, not spatial's answer to an unknown POI");
		assertThat(spatial.paths()).containsExactly("/api/locations/node/42");
	}

	/** Review M4-11 round 2, LOW-3: a problem 404 whose detail cannot be read is not spatial's. */
	@ParameterizedTest
	@ValueSource(strings = { "", "<html>Not Found</html>", "null" })
	void aProblem404WithoutAReadableDetailStopsTheConsumer(String body) {
		spatial.answerEvery(404, "application/problem+json", body);

		assertThat(catchThrowable(() -> adapter.find("node/42"))).isExactlyInstanceOf(ConsumerMustStopException.class);
	}

	/** Review M4-11, LOW-3: not spatial, so every task would end here; the message carries no URL. */
	@ParameterizedTest
	@ValueSource(ints = { 404, 405 })
	void a404WithoutSpatialsProblemBodyOrA405StopsTheConsumer(int status) {
		spatial.unavailable(status);

		Throwable failure = catchThrowable(() -> adapter.find("node/42"));

		assertThat(failure).isExactlyInstanceOf(ConsumerMustStopException.class)
				.hasMessage("spatial answered HTTP " + status + " to the detail of node/42, not spatial's answer to an unknown POI");
		assertThat(((ConsumerMustStopException) failure).remedy())
				.isEqualTo("point SPATIAL_URI at spatial and restart the worker");
	}

	@ParameterizedTest
	@ValueSource(strings = { "node", "node/42/unlocked", "node/..", "../42", "node/4%2F2", "" })
	void anIdThatIsNotTwoSafeSegmentsIsUnknownWithoutACall(String osmId) {
		assertThat(adapter.find(osmId)).isEmpty();
		assertThat(spatial.paths()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 503, 400, 301 })
	void anyOtherStatusIsSpatialNotAnswering(int status) {
		spatial.unavailable(status);

		Throwable failure = catchThrowable(() -> adapter.find("node/42"));

		assertThat(failure).isInstanceOf(TransientTaskException.class)
				.hasMessage("spatial answered HTTP " + status + " to the detail of node/42");
	}

	/** The message becomes failed_detail, so it names the POI and never the URL. */
	@Test
	void aRefusedConnectionIsTransientAndItsMessageCarriesNoUrl() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		SpatialPoiDetailsAdapter unreachable = new SpatialPoiDetailsAdapter(
				RestClient.builder().baseUrl("http://127.0.0.1:" + closedPort).build());

		Throwable failure = catchThrowable(() -> unreachable.find("node/42"));

		assertThat(failure).isInstanceOf(TransientTaskException.class).hasNoCause()
				.hasMessage("spatial gave no detail of node/42");
	}
}
