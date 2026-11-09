package com.laa66.worker.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PoiPromptTest {

	@Test
	void theNameIsStripped() {
		assertThat(new PoiPrompt("  Hala Stulecia \n", PoiCategory.ARCHITECTURE, "pl").name()).isEqualTo("Hala Stulecia");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  \t" })
	void aPoiWithoutANameIsRejected(String name) {
		assertThatIllegalArgumentException().isThrownBy(() -> new PoiPrompt(name, PoiCategory.MUSEUMS, "pl"));
	}

	@Test
	void theNameLimitIsInclusive() {
		String atLimit = "a".repeat(PoiPrompt.MAX_NAME_LENGTH);

		assertThat(new PoiPrompt(atLimit, PoiCategory.MUSEUMS, "pl").name()).isEqualTo(atLimit);
		assertThatIllegalArgumentException().isThrownBy(() -> new PoiPrompt(atLimit + "a", PoiCategory.MUSEUMS, "pl"))
				.withMessageContaining("limit is " + PoiPrompt.MAX_NAME_LENGTH);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "en", "PL", "" })
	void onlyPolishIsSupported(String lang) {
		assertThatIllegalArgumentException().isThrownBy(() -> new PoiPrompt("Ratusz", PoiCategory.MUSEUMS, lang));
	}

	@Test
	void theCategoryIsOneOfSpatialsEightSlugs() {
		assertThat(PoiCategory.values()).extracting(PoiCategory::slug).containsExactly("public_art", "monuments",
				"heritage", "sacred", "museums", "viewpoints", "architecture", "attractions");
		assertThat(PoiCategory.fromSlug("sacred")).isEqualTo(PoiCategory.SACRED);
		assertThatIllegalArgumentException().isThrownBy(() -> PoiCategory.fromSlug("restaurant"));
	}
}
