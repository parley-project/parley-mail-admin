package us.bringardner.parley.mail.admin.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.mail.admin.log.LogBuffer.Source;

/** A line belongs to the server that was doing the work, whichever class logs it. */
public class LogSourceTest {

	@Test
	public void theWorkOfAServerIsTheServers() {
		// the framework's classes, with the work's ID in the message
		assertEquals(Source.FTP, Source.of("us.bringardner.parley.net.nio.NioConnection", "TCPConn22", "[FtpServer-19] TLS handshake done"));
		assertEquals(Source.POP3, Source.of("us.bringardner.parley.mail.store.Mailbox", "Pop3RequestProcessor", "[Pop3Server-4] Opened"));
		assertEquals(Source.DNS, Source.of("us.bringardner.parley.net.nio.NioConnection", "TCPConn1", "[DnsTcp-10] Disconnected"));
		// the application's class, on the server's work
		assertEquals(Source.FTP, Source.of("us.bringardner.parley.mail.admin.server.SuiteFtp$SuiteFtpServer", "FtpRequestProcessor", "[FtpServer-19] Connected from 1.2.3.4:5"));
		// the server's own thread, no session yet
		assertEquals(Source.IMAP, Source.of("us.bringardner.parley.core.SecureBaseObject", "ImapServer", "key store type=PKCS12"));
	}

	@Test
	public void theApplicationsOwnWorkIsAdmin() {
		assertEquals(Source.ADMIN, Source.of("us.bringardner.parley.mail.admin.web.ApiServlet", "https-openssl-nio-443-exec-7", "Saved"));
		assertEquals(Source.ADMIN, Source.of("us.bringardner.parley.mail.admin.suite.MailSuite", "Parley-control", "Can't set the level"));
	}

	@Test
	public void aServersOwnClassesAreItsWithoutASession() {
		assertEquals(Source.SMTP, Source.of("us.bringardner.parley.smtp.queue.MailQueue", "queue-1", "Delivered"));
		assertEquals(Source.DNS, Source.of("us.bringardner.parley.dns.server.DnsServer", "DnsMaintenance", "Zone saved"));
	}

	@Test
	public void whatNoServerIsDoingIsOther() {
		assertEquals(Source.OTHER, Source.of("us.bringardner.parley.files.watch.PollingWatchService", "watch-1", "Watching failed"));
		assertEquals(Source.OTHER, Source.of(null, null, null));
	}
}
