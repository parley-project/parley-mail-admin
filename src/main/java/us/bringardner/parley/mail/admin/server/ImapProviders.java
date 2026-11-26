package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.imap.server.ImapServer;


import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.imap.IMAP;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The IMAP servers a launcher can run ({@code parley.servers=imap,imaps}): each is made from its
 * own configuration, read from {@code parley-mail.properties}, then {@code parley-imap.properties}
 * (or the file {@code parley.mail.imap.configFile} names). Each runs when parley.servers lists it, on
 * its own port (parley.mail.imap.port, 143; parley.mail.imaps.port, 993); imaps is skipped with a
 * warning while it has no TLS key store.
 *
 * @since 2.0.0
 */
public final class ImapProviders {

	private ImapProviders() {
	}

	/** IMAP (port 143, STARTTLS): {@code imap} */
	public static final class Imap extends MailServerProvider {
		public Imap() {
			super("imap", "IMAP", IMAP.IMAP_PORT, false);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			return MailServices.service(MailServices.make(context, () -> new ImapServer(port, ImapServer.IMAP_NAME, false)), id(),
					"IMAP server", context);
		}
	}

	/** IMAP with implicit TLS (port 993): {@code imaps}; it needs a TLS key store */
	public static final class Imaps extends MailServerProvider {
		public Imaps() {
			super("imaps", "IMAP with implicit TLS", IMAP.IMAPS_PORT, true);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			return MailServices.service(MailServices.make(context, () -> new ImapServer(port, ImapServer.IMAP_NAME, true)), id(),
					"IMAP server (implicit TLS)", context);
		}
	}
}
