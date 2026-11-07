package com.laa66.worker.domain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.port.in.TaskConsumer;

/**
 * Acknowledges and logs. The real pipeline (pre-check, POI detail, provider call through
 * {@link ProviderCallPacer}, write-back) is M4-11.
 */
public class GenerationTaskHandler implements TaskConsumer {

	private static final Logger log = LoggerFactory.getLogger(GenerationTaskHandler.class);

	@Override
	public void consume(GenerateContentTask task) {
		log.info("Generation task received: osmId={} mode={} lang={} leasedAt={}",
				task.key().osmId(), task.key().mode(), task.key().lang(), task.leasedAt());
	}
}
