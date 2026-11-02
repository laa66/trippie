package com.laa66.content.domain.port.out;

import com.laa66.content.domain.model.EventPublicationException;
import com.laa66.content.domain.model.GenerateContentTask;

/**
 * Outbound port to the message broker.
 *
 * <p>The contract every adapter keeps:
 * <ul>
 * <li><b>Published means confirmed and routed.</b> A normal return means the broker confirmed the
 * task and a durable queue took it. A broker that answers without a queue behind the routing key has
 * not published anything.</li>
 * <li><b>Anything else is an exception, never a silent loss.</b> There is no fire-and-forget mode:
 * a task lost after its lease was taken leaves a POI spinning until the lease expires, with nobody
 * working on it.</li>
 * <li><b>Bounded time.</b> The call returns or throws within a few seconds whatever the broker does:
 * refused, unknown host, stalled connect, frozen, in a resource alarm. Callers may run it on a
 * request thread, but never inside a database transaction, which would hold its connection for
 * that long.</li>
 * </ul>
 *
 * <p>An exception does not prove the task is absent: a publish that timed out may still land, and
 * its consumer may then generate under a lease that was given up or taken over. The fence on the
 * write-backs protects the row from that task; it does not refund the provider call.
 */
public interface EventPublisher {

	/**
	 * @throws EventPublicationException when the broker did not confirm the task as queued, within
	 *     the bounded time
	 */
	void publish(GenerateContentTask task);
}
