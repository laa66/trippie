package com.laa66.worker.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PoiPromptTest {

	@Test
	void theNameIsStripped() {
		assertThat(new PoiPrompt("  Hala Stulecia \n", PoiCategory.ARCHITECTURE, "pl", Map.of()).name()).isEqualTo("Hala Stulecia");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "  \t" })
	void aPoiWithoutANameIsRejected(String name) {
		assertThatIllegalArgumentException().isThrownBy(() -> new PoiPrompt(name, PoiCategory.MUSEUMS, "pl", Map.of()));
	}

	@Test
	void theNameLimitIsInclusive() {
		String atLimit = "a".repeat(PoiPrompt.MAX_NAME_LENGTH);

		assertThat(new PoiPrompt(atLimit, PoiCategory.MUSEUMS, "pl", Map.of()).name()).isEqualTo(atLimit);
		assertThatIllegalArgumentException().isThrownBy(() -> new PoiPrompt(atLimit + "a", PoiCategory.MUSEUMS, "pl", Map.of()))
				.withMessageContaining("limit is " + PoiPrompt.MAX_NAME_LENGTH);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "en", "PL", "" })
	void onlyPolishIsSupported(String lang) {
		assertThatIllegalArgumentException().isThrownBy(() -> new PoiPrompt("Ratusz", PoiCategory.MUSEUMS, lang, Map.of()));
	}

	@Test
	void factValuesAreStrippedAndBlankOnesDropped() {
		Map<PoiFact, String> facts = new HashMap<>();
		facts.put(PoiFact.ARCHITECT, "  Max Berg \n");
		facts.put(PoiFact.MATERIAL, " ");
		facts.put(PoiFact.ARTIST, null);

		assertThat(prompt(facts).facts()).containsExactly(entry(PoiFact.ARCHITECT, "Max Berg"));
	}

	@Test
	void aFactIsKeptWholeUpToTheBoundAndCutOverIt() {
		String atLimit = "a".repeat(PoiPrompt.MAX_FACT_LENGTH);

		assertThat(prompt(Map.of(PoiFact.INSCRIPTION, atLimit)).facts().get(PoiFact.INSCRIPTION)).isEqualTo(atLimit);
		assertThat(prompt(Map.of(PoiFact.INSCRIPTION, atLimit + "bcd")).facts().get(PoiFact.INSCRIPTION)).isEqualTo(atLimit);
	}

	/** The bound counts code points, so an emoji at the cut is not split into a lone surrogate. */
	@Test
	void theCutNeverSplitsASurrogatePair() {
		String emojis = "\uD83D\uDE00".repeat(PoiPrompt.MAX_FACT_LENGTH + 1);

		String cut = prompt(Map.of(PoiFact.INSCRIPTION, emojis)).facts().get(PoiFact.INSCRIPTION);

		assertThat(cut.codePointCount(0, cut.length())).isEqualTo(PoiPrompt.MAX_FACT_LENGTH);
		assertThat(cut).isEqualTo("\uD83D\uDE00".repeat(PoiPrompt.MAX_FACT_LENGTH));
	}

	@Test
	void theFactsCannotBeChangedAfterTheBounds() {
		Map<PoiFact, String> facts = prompt(Map.of(PoiFact.ARCHITECT, "Max Berg")).facts();

		assertThatThrownBy(() -> facts.put(PoiFact.INSCRIPTION, "x".repeat(10_000)))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private static PoiPrompt prompt(Map<PoiFact, String> facts) {
		return new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, "pl", facts);
	}

	@Test
	void theCategoryIsOneOfSpatialsEightSlugs() {
		assertThat(PoiCategory.values()).extracting(PoiCategory::slug).containsExactly("public_art", "monuments",
				"heritage", "sacred", "museums", "viewpoints", "architecture", "attractions");
		assertThat(PoiCategory.fromSlug("sacred")).isEqualTo(PoiCategory.SACRED);
		assertThatIllegalArgumentException().isThrownBy(() -> PoiCategory.fromSlug("restaurant"));
	}
}
