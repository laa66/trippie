package com.laa66.content.infrastructure.messaging;

/**
 * Every broker name of the generation pipeline, and the only place they are spelled.
 *
 * <p>The queue's arguments are immutable once declared: changing the dead-letter wiring later means
 * deleting the queue, and declaring it differently against a broker that already has it is a
 * channel error, not an update.
 */
public final class GenerationTopology {

	public static final String EXCHANGE = "trippie.content";

	public static final String ROUTING_KEY = "content.generate";

	public static final String QUEUE = "content.generate";

	public static final String DEAD_LETTER_EXCHANGE = "trippie.content.dlx";

	/** Dead-lettering keeps the original routing key, so the DLQ binds on the same one. */
	public static final String DEAD_LETTER_QUEUE = "content.generate.dlq";

	private GenerationTopology() {
	}
}
