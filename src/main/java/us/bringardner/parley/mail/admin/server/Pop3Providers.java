package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.pop3.server.Pop3Server;

import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.pop3.POP3;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The POP3 servers a launcher can run ({@code parley.servers=pop3,pop3s}): each is made from its
 * own configuration, read from {@code parley-mail.properties}, then {@code parley-pop3.properties}
 * (or the file {@code parley.mail.pop3.configFile} names). Each runs when parley.servers lists it, on
 * its own port (parley.mail.pop3.port, 110; parley.mail.pop3s.port, 995); pop3s is skipped with a
 * warning while it has no TLS key store.
 *
 * @since 2.0.0
 */
public final class Pop3Providers {

	private Pop3Providers() {
	}

	/** Read by 1.x to run POP3 with implicit TLS only; 2.0.0 runs pop3s when parley.servers lists it */
	static final Map<String, String> OBSOLETE = Collections.singletonMap(Pop3Server.POP3_PREFIX + ".secure",
			"for POP3 with implicit TLS list pop3s in parley.servers (and leave pop3 out to run it alone)");

	/** POP3 (port 110, STLS): {@code pop3} */
	public static final class Pop3 extends MailServerProvider {
		public Pop3() {
			super("pop3", "POP3", "parley-pop3.properties", Pop3Server.CONFIG_PROP, Pop3Server.POP3_PREFIX, POP3.POP3_PORT, false);
		}

		@Override
		protected Map<String, String> obsoleteSettings() {
			return OBSOLETE;
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
			int port = port(configuration);
			return ServerMain.service(AbstractMailServer.makeIn(context, configuration, () -> new Pop3Server(port, Pop3Server.POP3_NAME, false)),
					"POP3 server");
		}
	}

	/** POP3 with implicit TLS (port 995): {@code pop3s}; it needs a TLS key store */
	public static final class Pop3s extends MailServerProvider {
		public Pop3s() {
			super("pop3s", "POP3 with implicit TLS", "parley-pop3.properties", Pop3Server.CONFIG_PROP, Pop3Server.POP3S_PREFIX, POP3.POP3S_PORT,
					true);
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties configuration) throws Exception {
			int port = port(configuration);
			return ServerMain.service(AbstractMailServer.makeIn(context, configuration, () -> new Pop3Server(port, Pop3Server.POP3_NAME, true)),
					"POP3 server (implicit TLS)");
		}
	}
}
