package com.laa66.content.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.PoiContentKey;

/**
 * What the adapter does around its statements, on a stubbed {@link DataSource}: the arguments it
 * refuses before touching the database, and what it logs for a row count. What the statements
 * themselves do is {@link PoiContentResultIntegrationTest}'s, against a real Postgres.
 */
@ExtendWith(OutputCaptureExtension.class)
class PostgresContentAdapterTest {

	private static final PoiContentKey KEY = new PoiContentKey("node/42", ContentMode.TEXT, "pl");
	private static final Instant TOKEN = Instant.parse("2026-10-30T12:00:00.123456Z");

	@Test
	void aNullTokenIsNamedAndRefusedOnBothWritesBeforeTheDatabase() {
		DataSource dataSource = mock(DataSource.class);
		PostgresContentAdapter adapter = new PostgresContentAdapter(dataSource);

		assertThatNullPointerException()
				.isThrownBy(() -> adapter.completeGeneration(KEY, null, "a guide", "v1"))
				.withMessage("leasedAt");
		assertThatNullPointerException()
				.isThrownBy(() -> adapter.failGeneration(KEY, null, FailedClass.TRANSIENT, "detail"))
				.withMessage("leasedAt");

		verifyNoInteractions(dataSource);
	}

	@Test
	void aNullPromptVersionIsNamedAndRefusedOnCompletionBeforeTheDatabase() {
		DataSource dataSource = mock(DataSource.class);
		PostgresContentAdapter adapter = new PostgresContentAdapter(dataSource);

		assertThatNullPointerException()
				.isThrownBy(() -> adapter.completeGeneration(KEY, TOKEN, "a guide", null))
				.withMessage("promptVersion");

		verifyNoInteractions(dataSource);
	}

	@Test
	void aCompletionThatWritesNoRowWarnsWithTheRowAndWithoutTheText(CapturedOutput output) throws SQLException {
		adapterWritingRows(0).completeGeneration(KEY, TOKEN, "paid-for text", "v1");

		assertThat(output.getOut())
				.containsPattern("WARN.*completion refused for osmId=node/42 mode=TEXT lang=pl")
				.doesNotContain("paid-for text")
				.doesNotContain(".123456");
	}

	@Test
	void aCompletionThatWritesItsRowDoesNotWarn(CapturedOutput output) throws SQLException {
		adapterWritingRows(1).completeGeneration(KEY, TOKEN, "paid-for text", "v1");

		assertThat(output.getOut()).doesNotContain("completion refused");
	}

	@Test
	void aFailureThatWritesNoRowDoesNotWarn(CapturedOutput output) throws SQLException {
		adapterWritingRows(0).failGeneration(KEY, TOKEN, FailedClass.TRANSIENT, "provider detail");

		assertThat(output.getOut())
				.doesNotContainPattern("WARN.*osmId=node/42")
				.doesNotContain("provider detail");
	}

	private static PostgresContentAdapter adapterWritingRows(int rows) throws SQLException {
		DataSource dataSource = mock(DataSource.class, RETURNS_DEEP_STUBS);
		when(dataSource.getConnection().prepareStatement(anyString()).executeUpdate()).thenReturn(rows);
		return new PostgresContentAdapter(dataSource);
	}
}
