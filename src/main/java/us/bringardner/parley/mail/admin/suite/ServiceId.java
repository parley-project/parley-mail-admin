package us.bringardner.parley.mail.admin.suite;

import java.util.Locale;

/**
 * The listeners of the mail suite. Each reads only its own settings, with its property prefix (no
 * fallback to another prefix), and the settings every mail server reads.
 */
public enum ServiceId {

	SMTP(Protocol.SMTP, "SMTP", "Mail from other servers (MX)", "parley.mail.smtp", 25, 25, false),
	SUBMISSION(Protocol.SMTP, "Submission", "Mail from users' clients; STARTTLS, then AUTH", "parley.mail.submission", 587, 587, false),
	SUBMISSIONS(Protocol.SMTP, "Submission (TLS)", "Mail from users' clients over implicit TLS", "parley.mail.submissions", 465, 0, true),
	IMAP(Protocol.IMAP, "IMAP", "Mailbox access; STARTTLS before LOGIN", "parley.mail.imap", 143, 143, false),
	IMAPS(Protocol.IMAP, "IMAP (TLS)", "Mailbox access over implicit TLS", "parley.mail.imaps", 993, 0, true),
	POP3(Protocol.POP3, "POP3", "Download mail; STLS before USER", "parley.mail.pop3", 110, 110, false),
	POP3S(Protocol.POP3, "POP3 (TLS)", "Download mail over implicit TLS", "parley.mail.pop3s", 995, 0, true);

	public enum Protocol {
		SMTP("parley.mail.smtp", "parley-smtp.properties"), IMAP("parley.mail.imap", "parley-imap.properties"),
		POP3("parley.mail.pop3", "parley-pop3.properties");

		/** The prefix of the protocol's first server (the relay, imap, pop3), whose file is the protocol's. */
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
	/** The standard port. */
	public final int standardPort;
	/**
	 * The port when its setting isn't set: the standard port, or 0 (off) for the implicit TLS
	 * services, which need a key store (as parley-smtp's, parley-imap's and parley-pop3's server
	 * providers have it).
	 */
	public final int defaultPort;
	/** Implicit TLS. */
	public final boolean secure;

	ServiceId(Protocol protocol, String label, String description, String prefix, int standardPort, int defaultPort, boolean secure) {
		this.protocol = protocol;
		this.label = label;
		this.description = description;
		this.prefix = prefix;
		this.standardPort = standardPort;
		this.defaultPort = defaultPort;
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
		if (key.startsWith("parley.mail.admin.") || key.equals("parley.mail.users")) {
			return false; // the admin application's own, or applied without a restart
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
