package com.laa66.worker.domain.port.out;

import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.ProviderCredentialRejectedException;
import com.laa66.worker.domain.model.TextGenerationException;

/**
 * Outbound port: one paid provider call that turns a POI into a guide text.
 *
 * <p>Returns a non-blank text within the worker's length limit, or throws a
 * {@link TextGenerationException} whose {@code failedClass} says whether retrying can help. It never
 * returns a partial text: a truncated or filtered answer is a failure.
 *
 * <p>A refused credential is a {@link ProviderCredentialRejectedException} instead: a configuration
 * error, not a failure of this POI. Callers let it propagate, so the delivery policy can stop the
 * consumer and keep the task in the queue.
 */
public interface TextGenerator {

	GeneratedText generate(PoiPrompt prompt);
}
