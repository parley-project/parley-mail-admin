package us.bringardner.parley.mail.admin.server;

import us.bringardner.parley.smtp.server.SmtpServer;

import java.util.Collections;

import javax.management.JMException;

import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.smtp.SMTP;
import us.bringardner.parley.smtp.queue.MailQueueBean;

/**
 * The SMTP servers a launcher can run ({@code parley.servers=smtp,submission,submissions}): the
 * relay (MX, port 25), submission (587) and submission with implicit TLS (465). Each is made from
 * its own configuration, read from {@code parley-mail.properties}, then {@code parley-smtp.properties}
 * (or the file {@code parley.mail.smtp.configFile} names). Each runs when parley.servers lists it, on
 * its own port ({@code <prefix>.port}: 25, 587, 465); submissions is skipped with a warning while it
 * has no TLS key store.
 * <p>
 * Each server opens the queue its settings name ({@code <prefix>.queue}, default
 * {@code .smtp-queue} under the maildrop root); servers given the same queue share it through the
 * store's locks, as servers in separate processes would. While a server runs its queue is the JMX
 * bean {@code us.bringardner.parley:type=MailQueue,name=ID} (the server's id: smtp, submission, ...;
 * {@link us.bringardner.parley.smtp.queue.MailQueueMXBean}).
 *
 * @since 2.0.0
 */
public final class SmtpProviders {

	private SmtpProviders() {
	}

	/** A server whose store and queue are JMX beans while it runs */
	static ServerMain.Service service(SmtpServer server, String id, String name, ServerContext context) {
		return MailServices.service(server, id, name, context, c -> {
			try {
				return java.util.Collections.singletonList(c.register("MailQueue", id, new MailQueueBean(server.getQueue(), server.getQueueHistory())));
			} catch (JMException e) {
				System.err.println("Can't register the queue's JMX bean: " + e);
				return java.util.Collections.emptyList();
			}
		});
	}

	/** The relay (MX, port 25): {@code smtp} */
	public static final class Smtp extends MailServerProvider {
		public Smtp() {
			super("smtp", "SMTP relay (MX)", SMTP.SMTP_PORT, false);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			SmtpServer s = MailServices.make(context, () -> new SmtpServer(port, SmtpServer.SMTP_NAME, false, false));
			return service(s, id(), "SMTP server", context);
		}
	}

	/** Submission (RFC 6409, port 587, STARTTLS): {@code submission} */
	public static final class Submission extends MailServerProvider {
		public Submission() {
			super("submission", "SMTP submission", SMTP.SUBMISSION_PORT, false);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			SmtpServer s = MailServices.make(context, () -> new SmtpServer(port, SmtpServer.SMTP_NAME, false, true));
			return service(s, id(), "Submission server", context);
		}
	}

	/** Submission with implicit TLS (RFC 8314, port 465): {@code submissions}; it needs a TLS key store */
	public static final class Submissions extends MailServerProvider {
		public Submissions() {
			super("submissions", "SMTP submission with implicit TLS", SMTP.SUBMISSIONS_PORT, true);
		}

		@Override
		public ServerMain.Service create(ServerContext context) throws Exception {
			int port = standardPort();
			SmtpServer s = MailServices.make(context, () -> new SmtpServer(port, SmtpServer.SMTP_NAME, true, true));
			return service(s, id(), "Submission server (implicit TLS)", context);
		}
	}
}
