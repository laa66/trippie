package com.laa66.content.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.laa66.content.ContentApplication;
import com.laa66.content.domain.model.ContentMode;
import com.laa66.content.domain.model.GenerateContentTask;
import com.laa66.content.domain.model.PoiContentKey;
import com.laa66.content.domain.port.out.EventPublisher;
import com.laa66.content.support.PublishBudget;

/**
 * Real boots against the ways a broker can be missing, on real sockets and without Docker. Each one
 * must come up, and a publish must then fail with the port's exception inside its budget.
 */
class UnreachableBrokerBootTest {

	/** A normal boot plus one connect or handshake timeout; the client's default connect timeout is 60 s. */
	private static final Duration BOOT_BUDGET = Duration.ofSeconds(20);

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-10-31T12:00:00.123456Z"));

	@Test
	void anUnknownHostBoots() {
		assertBootsAndPublishFails("rabbitmq.invalid", 5672);
	}

	@Test
	void aClosedPortBoots() {
		assertBootsAndPublishFails("127.0.0.1", 1);
	}

	@Test
	void aPeerThatAcceptsAndClosesBoots() throws IOException {
		try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			Thread.ofVirtual().start(() -> {
				while (!server.isClosed()) {
					try {
						server.accept().close();
					}
					catch (IOException ex) {
						return;
					}
				}
			});
			assertBootsAndPublishFails("127.0.0.1", server.getLocalPort());
		}
	}

	@Test
	void aPeerThatAcceptsAndStaysSilentBoots() throws IOException {
		List<Socket> held = new ArrayList<>();
		try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			Thread.ofVirtual().start(() -> {
				while (!server.isClosed()) {
					try {
						held.add(server.accept());
					}
					catch (IOException ex) {
						return;
					}
				}
			});
			assertBootsAndPublishFails("127.0.0.1", server.getLocalPort());
		}
	}

	/**
	 * A listener whose accept queue is full drops further SYNs, so a connect stalls as it does against
	 * a firewalled host. This is the case {@code connection-timeout} exists for: the client's default
	 * is 60 s, which the boot budget does not survive.
	 */
	@Test
	void aPeerThatNeverCompletesTheConnectBootsOnTheConnectionTimeout() throws IOException {
		List<Socket> queued = new ArrayList<>();
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			InetSocketAddress address = (InetSocketAddress) server.getLocalSocketAddress();
			fillAcceptQueue(address, queued);
			assertThatThrownBy(() -> connect(address, queued)).as("the connect has to really stall")
					.isInstanceOf(SocketTimeoutException.class);

			assertBootsAndPublishFails("127.0.0.1", server.getLocalPort());
		}
		finally {
			for (Socket socket : queued) {
				socket.close();
			}
		}
	}

	private static void fillAcceptQueue(InetSocketAddress address, List<Socket> queued) {
		for (int attempt = 0; attempt < 8; attempt++) {
			try {
				connect(address, queued);
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	private static void connect(InetSocketAddress address, List<Socket> queued) throws IOException {
		Socket socket = new Socket();
		queued.add(socket);
		socket.connect(address, 300);
	}

	private static void assertBootsAndPublishFails(String host, int port) {
		try (ConfigurableApplicationContext context = assertTimeoutPreemptively(BOOT_BUDGET, () -> boot(host, port))) {
			assertThat(context.isRunning()).isTrue();
			PublishBudget.assertFailsWithinBudget(context.getBean(EventPublisher.class), TASK);
		}
	}

	private static ConfigurableApplicationContext boot(String host, int port) {
		return new SpringApplicationBuilder(ContentApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.rabbitmq.host=" + host, "--spring.rabbitmq.port=" + port);
	}
}
