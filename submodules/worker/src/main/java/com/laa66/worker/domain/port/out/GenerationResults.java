package com.laa66.worker.domain.port.out;

import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.TransientTaskException;

/**
 * Outbound port: content's internal surface for the worker, the only way to {@code content_db}.
 * Every call sends the task's key and lease token exactly as they were delivered; content's fences
 * do the deciding.
 *
 * <p>Each method throws {@link TransientTaskException} when content gives no answer and
 * {@link PermanentTaskException} when it refuses the request itself.
 */
public interface GenerationResults {

	/**
	 * Whether the task's lease is still the live one: the row is {@code PROCESSING} under this exact
	 * token and the lease has not expired. {@code false} covers a completed row, a lease that was
	 * failed, released or taken over, an expired one and a missing row alike: none of them is worth
	 * a provider call.
	 */
	boolean isLeaseLive(GenerateContentTask task);

	/** Idempotent on content's side: a second completion under the same token changes nothing. */
	void complete(GenerateContentTask task, GeneratedText text);

	/** Dropped by content when the token is no longer the row's. */
	void fail(GenerateContentTask task, FailedClass failedClass, String detail);
}
