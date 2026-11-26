package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.pop3.server.Pop3Server;


import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.pop3.POP3;
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

	/** POP3 (port 110, STLS): {@code pop3} */
	public static final class Pop3 extends MailServerProvider {
		public Pop3() {
			super("pop3", "POP3", POP3.POP3_PORT, false);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			return MailServices.service(MailServices.make(context, () -> new Pop3Server(port, Pop3Server.POP3_NAME, false)), id(),
					"POP3 server", context);
		}
	}

	/** POP3 with implicit TLS (port 995): {@code pop3s}; it needs a TLS key store */
	public static final class Pop3s extends MailServerProvider {
		public Pop3s() {
			super("pop3s", "POP3 with implicit TLS", POP3.POP3S_PORT, true);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			return MailServices.service(MailServices.make(context, () -> new Pop3Server(port, Pop3Server.POP3_NAME, true)), id(),
					"POP3 server (implicit TLS)", context);
		}
	}
}
