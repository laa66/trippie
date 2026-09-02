package com.laa66.auth.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Direct unit test of the eight-slug membership rule, in isolation from {@code @NotNull} (which
 * owns the null case at the web boundary).
 */
class CategorySlugsValidatorTest {

	private final CategorySlugsValidator validator = new CategorySlugsValidator();

	@Test
	void allEightKnownSlugs_areValid() {
		List<String> allEight = List.of("public_art", "monuments", "heritage", "sacred",
				"museums", "viewpoints", "architecture", "attractions");

		assertThat(validator.isValid(allEight, null)).isTrue();
	}

	@Test
	void emptyList_isValid() {
		assertThat(validator.isValid(List.of(), null)).isTrue();
	}

	@Test
	void oneUnknownSlug_isInvalid() {
		assertThat(validator.isValid(List.of("museums", "not-a-real-slug"), null)).isFalse();
	}

	@Test
	void nullList_isValid_soNotNullReportsInstead() {
		assertThat(validator.isValid(null, null)).isTrue();
	}
}
