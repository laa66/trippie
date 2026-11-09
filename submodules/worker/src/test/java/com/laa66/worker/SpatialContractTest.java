package com.laa66.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.laa66.worker.domain.model.PoiCategory;

/**
 * The POI detail the prompt is built from (M4-11) names its category by spatial's slug. A slug added
 * there and missing here would turn every POI of that category into a bad detail, so the two lists
 * are read side by side, the way {@link ContentContractTest} reads content.
 */
class SpatialContractTest {

	private static final Path CATEGORY = Path.of("../spatial/src/main/java/com/laa66/spatial/domain/model/Category.java");

	@Test
	void theCategorySlugsAreSpatials() throws IOException {
		Matcher slug = Pattern.compile("(?m)^\\s*[A-Z_]+\\(\"([a-z_]+)\"\\)[,;]").matcher(Files.readString(CATEGORY));
		List<String> spatials = new ArrayList<>();
		while (slug.find()) {
			spatials.add(slug.group(1));
		}

		assertThat(spatials).isNotEmpty()
				.isEqualTo(Arrays.stream(PoiCategory.values()).map(PoiCategory::slug).toList());
	}
}
