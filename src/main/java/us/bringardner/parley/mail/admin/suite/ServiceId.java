package us.bringardner.parley.mail.admin.suite;

import java.util.Locale;

/**
 * The listeners of the mail suite. Each reads its own settings (port, limits, key store) with its
 * property prefix; the protocol's shared settings use the protocol's prefix
 * ({@code parley.mail.smtp}, {@code parley.mail.imap}, {@code parley.mail.pop3}).
 */
public enum ServiceId {

	SMTP(Protocol.SMTP, "SMTP", "Mail from other servers (MX)", "parley.mail.smtp", 25, false),
	SUBMISSION(Protocol.SMTP, "Submission", "Mail from users' clients; STARTTLS, then AUTH", "parley.mail.submission", 587, false),
	SUBMISSIONS(Protocol.SMTP, "Submission (TLS)", "Mail from users' clients over implicit TLS", "parley.mail.submissions", 465, true),
	IMAP(Protocol.IMAP, "IMAP", "Mailbox access; STARTTLS before LOGIN", "parley.mail.imap", 143, false),
	IMAPS(Protocol.IMAP, "IMAP (TLS)", "Mailbox access over implicit TLS", "parley.mail.imaps", 993, true),
	POP3(Protocol.POP3, "POP3", "Download mail; STLS before USER", "parley.mail.pop3", 110, false),
	POP3S(Protocol.POP3, "POP3 (TLS)", "Download mail over implicit TLS", "parley.mail.pop3s", 995, true);

	public enum Protocol {
		SMTP("parley.mail.smtp"), IMAP("parley.mail.imap"), POP3("parley.mail.pop3");

		/** The prefix of the protocol's shared settings. */
		public final String prefix;

		Protocol(String prefix) {
			this.prefix = prefix;
		}
	}

	public final Protocol protocol;
	public final String label;
	public final String description;
	/** The prefix of this listener's own settings. */
	public final String prefix;
	/** The standard port, the default when the port isn't set. */
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

	/** The id used in URLs and JSON: smtp, submission, ... */
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
	 * True if a change to this setting needs this service restarted: its own prefix, its
	 * protocol's prefix (for SMTP, which shares one queue, any SMTP setting), or a setting every
	 * server reads (the store, TLS, parley-net and parley-core settings).
	 */
	public boolean isAffectedBy(String key) {
		if (key.startsWith("parley.mail.admin.") || key.equals("parley.mail.users")) {
			return false; // the admin application's own, or applied without a restart
		}
		if (key.startsWith(prefix + ".") || key.startsWith(protocol.prefix + ".")) {
			return true;
		}
		for (ServiceId s : values()) {
			if (key.startsWith(s.prefix + ".") || key.startsWith(s.protocol.prefix + ".")) {
				return false; // another service's
			}
		}
		return key.startsWith("parley.");
	}
}
