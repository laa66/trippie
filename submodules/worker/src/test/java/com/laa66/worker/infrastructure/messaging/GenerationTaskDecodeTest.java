package com.laa66.worker.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.laa66.worker.domain.model.ContentMode;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.PoiContentKey;

class GenerationTaskDecodeTest {

	/** Byte for byte what content's RabbitEventPublisher writes (its IT decodes the same shape). */
	static final String CONTENT_BODY =
			"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},\"leasedAt\":\"2026-10-31T12:00:00.123456Z\"}";

	@Test
	void contentsBodyDecodesWithEveryDigitOfTheToken() {
		GenerateContentTask task = decode(CONTENT_BODY);

		assertThat(task.key()).isEqualTo(new PoiContentKey("node/42", ContentMode.TEXT, "pl"));
		assertThat(task.leasedAt()).isEqualTo(Instant.parse("2026-10-31T12:00:00.123456Z"));
		assertThat(task.leasedAt().getNano()).isEqualTo(123_456_000);
	}

	@Test
	void nanosecondsSurviveTooSoTheWorkerRoundsNothing() {
		GenerateContentTask task = decode(CONTENT_BODY.replace(".123456Z", ".123456789Z"));

		assertThat(task.leasedAt().getNano()).isEqualTo(123_456_789);
	}

	@Test
	void anUnknownModeIsPermanent() {
		assertThatThrownBy(() -> decode(CONTENT_BODY.replace("TEXT", "VIDEO"))).isInstanceOf(PermanentTaskException.class);
	}

	@Test
	void aMissingTokenIsPermanent() {
		assertThatThrownBy(() -> decode("{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"}}"))
				.isInstanceOf(PermanentTaskException.class);
	}

	@Test
	void aMissingKeyFieldIsPermanent() {
		assertThatThrownBy(() -> decode(CONTENT_BODY.replace("\"lang\":\"pl\"", "\"lang\":null")))
				.isInstanceOf(PermanentTaskException.class);
	}

	@Test
	void garbageIsPermanent() {
		assertThatThrownBy(() -> decode("not json")).isInstanceOf(PermanentTaskException.class);
	}

	private static GenerateContentTask decode(String body) {
		return GenerationTaskListener.decode(body.getBytes(StandardCharsets.UTF_8));
	}
}
