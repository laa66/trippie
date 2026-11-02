package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.ConnectException;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;

class TopologyDeclarerTest {

	private final AmqpAdmin admin = mock(AmqpAdmin.class);

	private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

	private final TopologyDeclarer declarer = new TopologyDeclarer(admin, connectionFactory);

	@Test
	void aDeclarationThatTimedOutOnAnOpenConnectionDropsItSoTheNextOneDeclaresAgain() {
		doThrow(new AmqpTimeoutException("no answer to queue.declare")).when(admin).initialize();

		declarer.run(null);

		verify(connectionFactory).resetConnection();
	}

	@Test
	void aSuccessfulDeclarationKeepsTheConnection() {
		declarer.run(null);

		verify(connectionFactory, never()).resetConnection();
	}

	@Test
	void aConnectFailureDoesNotStopTheBoot() {
		doThrow(new AmqpConnectException(new ConnectException("refused"))).when(admin).initialize();

		assertThatCode(() -> declarer.run(null)).doesNotThrowAnyException();
	}

	@Test
	void anIoFailureDoesNotStopTheBoot() {
		doThrow(new AmqpIOException(new IOException("reset"))).when(admin).initialize();

		assertThatCode(() -> declarer.run(null)).doesNotThrowAnyException();
	}

	@Test
	void aTimeoutDoesNotStopTheBoot() {
		doThrow(new AmqpTimeoutException("no answer")).when(admin).initialize();

		assertThatCode(() -> declarer.run(null)).doesNotThrowAnyException();
	}

	@Test
	void aChannelClosedForAnotherReasonDoesNotStopTheBoot() {
		doThrow(new AmqpIOException(new IOException(channelClosed(AMQP.INTERNAL_ERROR)))).when(admin).initialize();

		assertThatCode(() -> declarer.run(null)).doesNotThrowAnyException();
	}

	@Test
	void aRejectedDeclarationStopsTheBootWhereverItSitsInTheCauseChain() {
		doThrow(new AmqpIOException(new IOException(channelClosed(AMQP.PRECONDITION_FAILED)))).when(admin).initialize();

		assertThatThrownBy(() -> declarer.run(null)).isInstanceOf(AmqpIOException.class);
	}

	@Test
	void refusedCredentialsStopTheBoot() {
		doThrow(new AmqpAuthenticationException(new IOException("ACCESS_REFUSED"))).when(admin).initialize();

		assertThatThrownBy(() -> declarer.run(null)).isInstanceOf(AmqpAuthenticationException.class);
	}

	@Test
	void aBugIsNotABrokerOutage() {
		doThrow(new IllegalStateException("bug")).when(admin).initialize();

		assertThatThrownBy(() -> declarer.run(null)).isInstanceOf(IllegalStateException.class);
	}

	private static ShutdownSignalException channelClosed(int replyCode) {
		AMQP.Channel.Close close = new AMQP.Channel.Close.Builder().replyCode(replyCode).replyText("closed").build();
		return new ShutdownSignalException(false, false, close, null);
	}
}
