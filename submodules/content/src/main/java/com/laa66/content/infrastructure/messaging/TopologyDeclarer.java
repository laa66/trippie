package com.laa66.content.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;

/**
 * Declares the topology during boot. {@code RabbitAdmin} declares only when a connection opens, and
 * with no listener in this module nothing opens one until the first publish.
 *
 * <p>A broker that cannot be reached does not stop the boot, however the failure looks: reads do not
 * need it, compose restarts nothing, and the admin declares on the next connection, before that
 * connection's first publish. Only what cannot heal by waiting stops it: a queue already declared
 * with different arguments, and credentials the broker refuses.
 *
 * <p>"The next connection" has to be a new one. A declaration can fail after the connection opened,
 * on a slow broker whose answer misses {@code channel-rpc-timeout}, and the factory keeps that
 * connection: every publish would then reuse it and meet a missing exchange until it happened to
 * drop. So a swallowed failure also resets the connection.
 */
public class TopologyDeclarer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TopologyDeclarer.class);

	private final AmqpAdmin admin;

	private final ConnectionFactory connectionFactory;

	public TopologyDeclarer(AmqpAdmin admin, ConnectionFactory connectionFactory) {
		this.admin = admin;
		this.connectionFactory = connectionFactory;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			admin.initialize();
		}
		catch (AmqpException ex) {
			if (ex instanceof AmqpAuthenticationException || isRejectedDeclaration(ex)) {
				throw ex;
			}
			log.warn("Generation topology not declared at startup, the next connection declares it: {}", ex.toString());
			connectionFactory.resetConnection();
		}
	}

	private static boolean isRejectedDeclaration(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ShutdownSignalException signal
					&& signal.getReason() instanceof AMQP.Channel.Close close
					&& close.getReplyCode() == AMQP.PRECONDITION_FAILED) {
				return true;
			}
		}
		return false;
	}
}
