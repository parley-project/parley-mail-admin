package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.net.server.ServerMain;

/**
 * A server {@link ServerLauncher} can run: found at run time (Java's ServiceLoader, through
 * parley-core's plugins: {@code META-INF/services/us.bringardner.parley.net.server.ServerProvider}),
 * so a launcher runs any set of servers, alone or several in one JVM, without depending on their
 * modules. Each listener is one provider: SMTP's MX and submission servers are two, POP3 and POP3
 * with implicit TLS are two.
 * <p>
 * A provider only makes its server: it constructs it, tells it whether it is secure, and gives it
 * what the application owns (the user registry, see {@link ServerContext#users()}). The server finds
 * and reads its own settings when it starts and refuses to start, listing every problem, when they
 * are missing or unusable; the launcher shows that as the server's state. Whether a server runs is
 * what {@code parley.servers} lists, which the application keeps.
 *
 * @since 2.0.0
 */
public interface ServerProvider {

	/** The id that names the server in {@code parley.servers}, in lower case, e.g. {@code pop3} */
	String id();

	/** For people, e.g. "POP3 (port 110)" */
	String description();

	/** Make the server; it is started by the launcher */
	ServerMain.Service create(ServerContext context) throws Exception;
}
