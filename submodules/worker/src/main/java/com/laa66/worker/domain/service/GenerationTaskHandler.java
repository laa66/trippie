package com.laa66.worker.domain.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiDetail;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.domain.port.in.TaskConsumer;
import com.laa66.worker.domain.port.out.GenerationResults;
import com.laa66.worker.domain.port.out.PoiDetails;
import com.laa66.worker.domain.port.out.TextGenerator;

/**
 * One attempt at a task: pre-check, POI detail, provider, write-back, in that order. Every retry of a
 * delivery runs all of it again.
 *
 * <p><strong>The pre-check is a cost control, not an optimisation.</strong> It runs before every
 * attempt, so a redelivered task, a task whose lease was completed, failed, released or taken over,
 * and a task whose lease expired between two attempts spend nothing and are acknowledged. It asks
 * content's internal, user-free read; the gated {@code GET /content} would need a user the worker
 * does not have.
 *
 * <p>The {@link TextGenerator} is the paced one ({@code GeminiConfig}): this class does not know the
 * pacer and must not pace a second time. It does not assume every provider failure is a
 * {@link TextGenerationException} either: whatever the generator throws travels on unchanged, a
 * {@link ConsumerMustStopException} above all (a refused key here; content or spatial misconfigured
 * in their adapters), which the delivery policy turns into a stopped consumer and a requeued task.
 *
 * <p>A text the provider was paid for is kept until its write-back lands. A write-back that fails
 * transiently is retried with the delivery, and the retry writes the kept text instead of paying for
 * another one. One slot is enough: the consumer is serial. The kept text is lost, and the POI paid
 * for again by a later POST, when the retry's pre-check finds the lease no longer live (expired or
 * taken over): the pre-check runs before the kept text is written, and its "no" acknowledges the
 * delivery. Accepted by the user (review M4-11, LOW-1, 2026-11-12). The slot does not outlive the
 * process either: a write-back that stops the consumer (content answering 404 or 405) requeues the
 * task, the fix is a restart, and the restarted worker pays for that POI again. A stop met while
 * recording a failure ({@link #giveUp}) requeues the task unrecorded as well, so after the restart
 * its delivery has the full attempt budget again and may pay once more for a POI that failed.
 *
 * <p>Failures are recorded once, when the delivery gives up ({@link #giveUp}), not on each attempt:
 * a {@code FAILED} row ends the lease, and the next attempt's pre-check would then stop a retry that
 * might have succeeded.
 */
public class GenerationTaskHandler implements TaskConsumer {

	private static final Logger log = LoggerFactory.getLogger(GenerationTaskHandler.class);

	private final GenerationResults results;

	private final PoiDetails poiDetails;

	private final TextGenerator textGenerator;

	private volatile Generated kept;

	private record Generated(GenerateContentTask task, GeneratedText text) {
	}

	public GenerationTaskHandler(GenerationResults results, PoiDetails poiDetails, TextGenerator textGenerator) {
		this.results = Objects.requireNonNull(results, "results");
		this.poiDetails = Objects.requireNonNull(poiDetails, "poiDetails");
		this.textGenerator = Objects.requireNonNull(textGenerator, "textGenerator");
	}

	@Override
	public void consume(GenerateContentTask task) {
		if (!results.isLeaseLive(task)) {
			log.info("Generation task skipped, its lease is no longer live: osmId={} mode={} lang={} leasedAt={}",
					task.key().osmId(), task.key().mode(), task.key().lang(), task.leasedAt());
			return;
		}
		Generated generated = kept;
		GeneratedText text = generated != null && generated.task().equals(task) ? generated.text() : generate(task);
		results.complete(task, text);
		kept = null;
		log.info("Generation task completed: osmId={} mode={} lang={} promptVersion={}",
				task.key().osmId(), task.key().mode(), task.key().lang(), text.promptVersion());
	}

	private GeneratedText generate(GenerateContentTask task) {
		PoiDetail detail = poiDetails.find(task.key().osmId())
				.orElseThrow(() -> new PermanentTaskException("spatial does not know POI " + task.key().osmId()));
		GeneratedText text = textGenerator.generate(prompt(task, detail));
		kept = new Generated(task, text);
		return text;
	}

	private static PoiPrompt prompt(GenerateContentTask task, PoiDetail detail) {
		try {
			return new PoiPrompt(detail.name(), PoiCategory.fromSlug(detail.category()), task.key().lang());
		}
		catch (IllegalArgumentException ex) {
			throw new PermanentTaskException("POI cannot be described: " + ex.getMessage());
		}
	}

	@Override
	public void giveUp(GenerateContentTask task, Throwable failure) {
		kept = null;
		Classified classified = classify(failure);
		log.warn("Generation task failed: osmId={} mode={} lang={} class={} detail={}",
				task.key().osmId(), task.key().mode(), task.key().lang(), classified.failedClass(), classified.detail());
		try {
			results.fail(task, classified.failedClass(), classified.detail());
		}
		catch (ConsumerMustStopException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// The delivery is over either way; the row's lease expires and the next POST takes it.
			log.error("Generation failure not recorded on content: osmId={} mode={} lang={}",
					task.key().osmId(), task.key().mode(), task.key().lang(), ex);
		}
	}

	record Classified(FailedClass failedClass, String detail) {
	}

	/**
	 * The first classified exception in the chain decides, as in the retry policy. The details come
	 * from this module's own exceptions, which never carry a URL or the credential; an unclassified
	 * exception may, so only its type is recorded, and it is {@code PERMANENT}, like the single
	 * attempt the policy gave it.
	 */
	static Classified classify(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof TransientTaskException) {
				return new Classified(FailedClass.TRANSIENT, cause.getMessage());
			}
			if (cause instanceof PermanentTaskException) {
				return new Classified(FailedClass.PERMANENT, cause.getMessage());
			}
			if (cause instanceof TextGenerationException generation) {
				return new Classified(generation.failedClass(), generation.getMessage());
			}
		}
		Throwable root = failure;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return new Classified(FailedClass.PERMANENT, "unclassified failure: " + root.getClass().getName());
	}
}
