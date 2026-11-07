package com.laa66.worker.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.worker.domain.port.in.TaskConsumer;
import com.laa66.worker.domain.service.GenerationTaskHandler;
import com.laa66.worker.domain.service.ProviderCallPacer;

@Configuration
public class WorkerConfig {

	@Bean
	ProviderCallPacer.Ticker ticker() {
		return System::nanoTime;
	}

	@Bean
	ProviderCallPacer.Sleeper sleeper() {
		return Thread::sleep;
	}

	/** One per process: the spacing is a property of the provider account, not of a caller. */
	@Bean
	ProviderCallPacer providerCallPacer(ProviderCallPacer.Ticker ticker, ProviderCallPacer.Sleeper sleeper) {
		return new ProviderCallPacer(ticker, sleeper);
	}

	@Bean
	TaskConsumer taskConsumer() {
		return new GenerationTaskHandler();
	}
}
