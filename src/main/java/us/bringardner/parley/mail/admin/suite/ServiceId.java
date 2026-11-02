package us.bringardner.parley.mail.admin.suite;

import java.util.Locale;

/**
 * The listeners of the mail suite. Each reads only its own settings, with its property prefix (no
 * fallback to another prefix), and the settings every mail server reads.
 */
public enum ServiceId {

	SMTP(Protocol.SMTP, "SMTP", "Mail from other servers (MX)", "parley.mail.smtp", 25, false),
	SUBMISSION(Protocol.SMTP, "Submission", "Mail from users' clients; STARTTLS, then AUTH", "parley.mail.submission", 587, false),
	SUBMISSIONS(Protocol.SMTP, "Submission (TLS)", "Mail from users' clients over implicit TLS", "parley.mail.submissions", 465, true),
	IMAP(Protocol.IMAP, "IMAP", "Mailbox access; STARTTLS before LOGIN", "parley.mail.imap", 143, false),
	IMAPS(Protocol.IMAP, "IMAP (TLS)", "Mailbox access over implicit TLS", "parley.mail.imaps", 993, true),
	POP3(Protocol.POP3, "POP3", "Download mail; STLS before USER", "parley.mail.pop3", 110, false),
	POP3S(Protocol.POP3, "POP3 (TLS)", "Download mail over implicit TLS", "parley.mail.pop3s", 995, true),
	FTP(Protocol.FTP, "FTP", "Files in the mail store; AUTH TLS before login", "parley.mail.ftp", 21, false),
	FTPS(Protocol.FTP, "FTP (TLS)", "Files in the mail store over implicit TLS", "parley.mail.ftps", 990, true);

	/** The setting that lists the servers that run (parley-net's launcher reads it), in parley-mail.properties */
	public static final String SERVERS = "parley.servers";

	public enum Protocol {
		SMTP("parley.mail.smtp", "parley-smtp.properties"), IMAP("parley.mail.imap", "parley-imap.properties"),
		POP3("parley.mail.pop3", "parley-pop3.properties"), FTP("parley.mail.ftp", "parley-mail-ftp.properties");

		/** The prefix of the protocol's first server (the relay, imap, pop3, ftp), whose file is the protocol's. */
		public final String prefix;
		/** The settings file of the protocol's servers (read after parley-mail.properties). */
		public final String settingsFile;

		Protocol(String prefix, String settingsFile) {
			this.prefix = prefix;
			this.settingsFile = settingsFile;
		}
	}

	public final Protocol protocol;
	public final String label;
	public final String description;
	/** The prefix of this listener's own settings. */
	public final String prefix;
	/**
	 * The standard port: the port when its setting isn't set. Whether the service runs is what
	 * {@value #SERVERS} lists; a service with implicit TLS also needs a key store (as parley-mail's
	 * server providers have it).
	 */
	public final int standardPort;
	/** Implicit TLS. */
	public final boolean secure;

	ServiceId(Protocol protocol, String label, String description, String prefix, int standardPort, boolean secure) {
		this.protocol = protocol;
		this.label = label;
		this.description = description;
		this.prefix = prefix;
		this.standardPort = standardPort;
		this.secure = secure;
	}

	/** The id used in URLs and JSON, and parley-net's server id: smtp, submission, ... */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public String portProperty() {
		return prefix + ".port";
	}

	public static ServiceId parse(String id) {
		for (ServiceId s : values()) {
			if (s.id().equalsIgnoreCase(id)) {
				return s;
			}
		}
		return null;
	}

	/**
	 * True if a change to this setting needs this service restarted: its own prefix (each server
	 * reads only its own: the submission server doesn't read the relay's {@code parley.mail.smtp.*}),
	 * the store's settings (every server), the queue's (the SMTP servers), or a setting every server
	 * reads (the mail root, TLS, parley-net and parley-core settings).
	 */
	public boolean isAffectedBy(String key) {
		if (key.startsWith("parley.mail.admin.") || key.equals("parley.mail.users") || key.equals(SERVERS) || key.startsWith(SERVERS + ".")) {
			return false; // the admin application's own, applied without a restart, or the launcher's list
		}
		if (key.startsWith("store.")) {
			return true;
		}
		if (key.startsWith("queue.")) {
			return protocol == Protocol.SMTP;
		}
		if (key.startsWith(prefix + ".")) {
			return true;
		}
		for (ServiceId s : values()) {
			if (key.startsWith(s.prefix + ".")) {
				return false; // another service's
			}
		}
		return key.startsWith("parley.");
	}
}
