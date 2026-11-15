package com.laa66.content.domain.service;

import java.time.Instant;
import java.util.Objects;

import com.laa66.content.domain.model.FailedClass;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.in.RecordGenerationResult;
import com.laa66.content.domain.port.out.ContentRepository;

/**
 * Hands the worker's calls to the repository unchanged: the whole policy (fence, idempotency,
 * limits) lives in its statements. The service exists because the web layer may not reach an
 * outbound port ({@code ArchitectureTest}).
 */
public class RecordGenerationResultService implements RecordGenerationResult {

	private final ContentRepository contentRepository;

	public RecordGenerationResultService(ContentRepository contentRepository) {
		this.contentRepository = Objects.requireNonNull(contentRepository, "contentRepository");
	}

	@Override
	public boolean isLeaseLive(PoiContentKey key, Instant leasedAt) {
		return contentRepository.isLeaseLive(key, leasedAt);
	}

	@Override
	public void complete(PoiContentKey key, Instant leasedAt, String text, String promptVersion) {
		contentRepository.completeGeneration(key, leasedAt, text, promptVersion);
	}

	@Override
	public void fail(PoiContentKey key, Instant leasedAt, FailedClass failedClass, String failedDetail) {
		contentRepository.failGeneration(key, leasedAt, failedClass, failedDetail);
	}
}
