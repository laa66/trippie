package com.laa66.worker.infrastructure.messaging;

/**
 * The broker names of the generation pipeline, copied from content's {@code GenerationTopology}
 * (which publishes into them) and held equal to it by {@code ContentContractTest}.
 *
 * <p>The worker declares the same topology with the same arguments on its own connection, because
 * nothing in compose orders it after content and a listener on a queue that does not exist yet
 * never starts. Declaring is idempotent only while the arguments are identical: a queue declared
 * without its dead-letter exchange cannot get one later, and dead-lettering is the whole of this
 * module's DLQ.
 */
public final class GenerationTopology {

	public static final String EXCHANGE = "trippie.content";

	public static final String ROUTING_KEY = "content.generate";

	public static final String QUEUE = "content.generate";

	public static final String DEAD_LETTER_EXCHANGE = "trippie.content.dlx";

	public static final String DEAD_LETTER_QUEUE = "content.generate.dlq";

	private GenerationTopology() {
	}
}
