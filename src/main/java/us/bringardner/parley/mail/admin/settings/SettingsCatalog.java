package us.bringardner.parley.mail.admin.settings;

import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.BOOLEAN;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.CHOICE;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.LIST;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.NUMBER;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.PATH;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.PORT;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.SECRET;
import static us.bringardner.parley.mail.admin.settings.SettingDef.Type.TEXT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.mail.admin.suite.ServiceId;

/**
 * The settings the Settings page offers, in groups. The names are Parley 2.0.0's
 * ({@code parley.mail.*}); the descriptions and defaults follow the parley-smtp, parley-imap,
 * parley-pop3, parley-mail and parley-core READMEs. Settings that aren't listed here can still
 * be set (the page's "Other settings").
 */
public final class SettingsCatalog {

	/** A group of settings: one tab of the Settings page. */
	public static final class Group {
		public final String id;
		public final String label;
		public final String description;
		public final List<SettingDef> settings = new ArrayList<>();

		Group(String id, String label, String description) {
			this.id = id;
			this.label = label;
			this.description = description;
		}
	}

	public static final String ROOT = "parley.mail.root";
	public static final String FILE_SOURCE = "parley.mail.fileSource";
	public static final String USERS = "parley.mail.users";
	public static final String TLS_KEY_STORE = "parley.mail.tls.keyStore";
	public static final String TLS_KEY_STORE_PASSWORD = "parley.mail.tls.keyStorePassword";
	public static final String TLS_KEY_STORE_TYPE = "parley.mail.tls.keyStoreType";
	public static final String SMTP_HOSTNAME = "parley.mail.smtp.hostname";
	public static final String SMTP_DOMAINS = "parley.mail.smtp.domains";
	public static final String SMTP_ALIASES = "parley.mail.smtp.aliases";
	public static final String SMTP_DKIM_KEYS = "parley.mail.smtp.dkim.keys";
	public static final String ADMIN_AUTO_START = "parley.mail.admin.autoStart";
	public static final String ADMIN_HISTORY_DAYS = "parley.mail.admin.historyDays";
	public static final String ADMIN_LOG_BUFFER = "parley.mail.admin.logBufferSize";
	public static final String ADMIN_LOG_FILE_SIZE = "parley.mail.admin.logFileSize";

	private final Map<String, Group> groups = new LinkedHashMap<>();
	private final Map<String, SettingDef> byKey = new LinkedHashMap<>();

	public SettingsCatalog() {
		Group g = group("general", "General", "The mail store, the users and the settings every server shares.");
		add(g, ROOT, "Mail root", PATH, "/pop3",
				"Where the mail lives: one directory per user (or per domain/user), the SMTP queue (.smtp-queue) and DMARC report data. Shared by SMTP, IMAP and POP3.");
		add(g, FILE_SOURCE, "Mail root file system", TEXT, "the default (local files)",
				"The parley-files FileSource factory for the mail root, e.g. a database or SFTP factory id. Empty for local files.");
		add(g, USERS, "Users file", PATH, null,
				"The user list every server uses (FileBasedAcl format). Changes made on the Users page, or to the file, take effect at once.");
		add(g, "parley.mail.sharedStore", "Shared store", CHOICE, "off",
				"off: this process alone serves the mail root (a second one is refused). host: several servers on this machine share it. cluster: servers on several machines share it.",
				"off", "host", "cluster");
		add(g, "parley.mail.lockDomain", "Lock domain", TEXT, "mail", "The name of the store's lock domain (parley-net locks) when the store is shared.");
		add(g, "parley.net.acl.maxConcurrentHashes", "Password checks at once", NUMBER, "the number of processors",
				"How many password hashes are checked at the same time (each takes about 0.1 s of a processor), so a flood of logins can't use every processor.");
		add(g, "parley.net.acl.hashWaitMs", "Password check wait (ms)", NUMBER, "30000",
				"How long a login waits for its turn to check a password before it fails.");

		g = group("tls", "TLS", "The key store for STARTTLS, STLS and implicit TLS. A service with a key store of its own (Listeners) uses that instead.");
		add(g, TLS_KEY_STORE, "Key store", PATH, null, "The key store file (e.g. made from a Let's Encrypt certificate). Without one, STARTTLS isn't offered and the implicit TLS services can't start.");
		add(g, TLS_KEY_STORE_PASSWORD, "Key store password", SECRET, null, "The key store's password.");
		add(g, TLS_KEY_STORE_TYPE, "Key store type", CHOICE, "PKCS12", "The key store's format.", "PKCS12", "JKS");
		add(g, "parley.core.forceTlsVersion", "Force TLS version", TEXT, null, "Allow only this TLS version (e.g. TLSv1.3). Empty: the JVM's defaults.");

		g = group("smtp", "SMTP", "Settings of every SMTP service (MX and submission): they share one queue.");
		add(g, SMTP_HOSTNAME, "Host name", TEXT, "the local host name",
				"The name in the greeting, EHLO, Received fields and notifications. It must match the PTR record of the server's public address and the MX records.");
		add(g, SMTP_DOMAINS, "Local domains", LIST, "the host name",
				"Domains whose mail is delivered here, comma separated. Managed on the Domains page.");
		add(g, "parley.mail.smtp.relayNetworks", "Relay networks", LIST, null,
				"Client networks that may send to other domains without logging in, e.g. 127.0.0.1/32,10.0.0.0/8.");
		add(g, "parley.mail.smtp.requireTls", "Require TLS", BOOLEAN, "false", "Offer AUTH (and on the submission ports, accept MAIL) only after STARTTLS.");
		add(g, "parley.mail.smtp.maxMessageSize", "Largest message (bytes)", NUMBER, "52428800", "The SIZE limit.");
		add(g, "parley.mail.smtp.maxRecipients", "Recipients per message", NUMBER, "100", "More are refused (452).");
		add(g, "parley.mail.smtp.timeout", "Idle timeout (ms)", NUMBER, "300000", "Sessions idle this long are closed. RFC 5321 requires at least 5 minutes.");
		add(g, SMTP_ALIASES, "Aliases file", PATH, null, "The aliases (name: member, member ...). Managed on the Aliases page.");
		add(g, "parley.mail.smtp.postmaster", "Postmaster", TEXT, "postmaster", "The user who receives postmaster mail.");

		g = group("smtp-delivery", "Delivery", "How the SMTP queue delivers mail to other domains.");
		add(g, "parley.mail.smtp.resolver", "MX resolver", CHOICE, "jdk",
				"jdk: the JDK's DNS. parley-dns: parley-dns with the DNS servers below (or /etc/resolv.conf). parley-dns-iterative: parley-dns from the root servers.",
				"jdk", "parley-dns", "parley-dns-iterative");
		add(g, "parley.mail.smtp.dnsServers", "DNS servers", LIST, "from /etc/resolv.conf", "DNS servers for parley-dns, comma separated.");
		add(g, "parley.mail.smtp.preferIpv6", "Prefer IPv6", BOOLEAN, "false", "With parley-dns: try mail hosts' IPv6 addresses first.");
		add(g, "parley.mail.smtp.tls", "STARTTLS to other servers", CHOICE, "opportunistic", "none, opportunistic or required.", "none", "opportunistic", "required");
		add(g, "parley.mail.smtp.relayHost", "Smart host", TEXT, null,
				"host:port of a server (your provider's) that sends all outgoing mail. Many networks block outgoing port 25.");
		add(g, "parley.mail.smtp.relayUser", "Smart host user", TEXT, null, "The user for the smart host.");
		add(g, "parley.mail.smtp.relayPassword", "Smart host password", SECRET, null, "The password for the smart host.");
		add(g, "parley.mail.smtp.relayAuth", "Smart host SASL", LIST, "PLAIN", "SASL mechanisms for the smart host, best first, e.g. SCRAM-SHA-256,PLAIN.");
		add(g, "parley.mail.smtp.relayTls", "Smart host TLS", CHOICE, "required", "TLS to the smart host.", "none", "opportunistic", "required");
		add(g, "parley.mail.smtp.queue.workers", "Delivery threads", NUMBER, "4", "Messages delivered at the same time.");
		add(g, "parley.mail.smtp.queue.retry", "Retry schedule (minutes)", LIST, "1,5,15,30,60,120", "Minutes between attempts after a temporary failure; the last repeats.");
		add(g, "parley.mail.smtp.queue.delayWarningHours", "Delay warning (hours)", NUMBER, "4", "The sender is told the message is delayed after this long.");
		add(g, "parley.mail.smtp.queue.maxAgeHours", "Give up after (hours)", NUMBER, "120", "The message is returned to the sender after this long.");

		g = group("smtp-auth", "Authentication", "DKIM, SPF, DMARC and ARC: signing outgoing mail and checking incoming mail.");
		add(g, SMTP_DKIM_KEYS, "DKIM keys", LIST, null, "domain:selector:keyfile, comma separated. Made on the Domains page.");
		add(g, "parley.mail.smtp.dkim.headers", "DKIM signed fields", LIST, "From, To, Cc, Subject, Date, Message-ID, MIME and List-* fields", "Fields to sign, comma separated (From is required).");
		add(g, "parley.mail.smtp.dkim.verify", "Verify DKIM", BOOLEAN, "true", "Verify the signatures of incoming mail.");
		add(g, "parley.mail.smtp.spf.check", "Check SPF", BOOLEAN, "true", "Check SPF for incoming mail.");
		add(g, "parley.mail.smtp.spf.rejectFail", "Refuse SPF fail", BOOLEAN, "false", "Refuse MAIL FROM when SPF says fail (550 5.7.23).");
		add(g, "parley.mail.smtp.dmarc.check", "Check DMARC", BOOLEAN, "true", "Check DMARC for incoming mail.");
		add(g, "parley.mail.smtp.dmarc.enforce", "Enforce DMARC", BOOLEAN, "false", "Refuse p=reject mail, deliver p=quarantine mail to Junk.");
		add(g, "parley.mail.smtp.dmarc.aggregateReports", "DMARC aggregate reports", BOOLEAN, "false", "Send rua= reports to the domains that ask for them.");
		add(g, "parley.mail.smtp.dmarc.failureReports", "DMARC failure reports", BOOLEAN, "false", "Send ruf= reports.");
		add(g, "parley.mail.smtp.dmarc.reportEmail", "Report sender", TEXT, "postmaster@<host name>", "The reports' From address.");
		add(g, "parley.mail.smtp.dmarc.reportOrgName", "Reporting organization", TEXT, "the host name", "The org_name of the reports.");
		add(g, "parley.mail.smtp.dmarc.reportIntervalHours", "Report interval (hours)", NUMBER, "24", "How often aggregate reports go out.");
		add(g, "parley.mail.smtp.dmarc.maxFailureReportsPerHour", "Failure reports per hour", NUMBER, "10", "Per domain.");
		add(g, "parley.mail.smtp.dmarc.publicSuffixList", "Public suffix list", PATH, "the included copy", "A newer public_suffix_list.dat.");
		add(g, "parley.mail.smtp.arc.verify", "Verify ARC", BOOLEAN, "true", "Validate the ARC chains of incoming mail.");
		add(g, "parley.mail.smtp.arc.seal", "ARC seal", BOOLEAN, "true", "Seal received mail that is forwarded (when a DKIM key fits the seal domain).");
		add(g, "parley.mail.smtp.arc.domain", "ARC domain", TEXT, "the host name", "The d= of our ARC seals.");
		add(g, "parley.mail.smtp.arc.trustedSealers", "Trusted ARC sealers", LIST, null, "Domains whose passing ARC chains override a DMARC failure.");

		g = group("imap", "IMAP", "Settings of both IMAP services.");
		add(g, "parley.mail.imap.requireTls", "Require TLS", BOOLEAN, "false", "Refuse LOGIN and AUTHENTICATE until STARTTLS.");
		add(g, "parley.mail.imap.autologout", "Idle timeout (ms)", NUMBER, "1800000", "Idle sessions are closed. RFC 9051 requires at least 30 minutes.");
		add(g, "parley.mail.imap.appendLimit", "Largest APPEND (bytes)", NUMBER, "104857600", "Larger messages get NO [TOOBIG].");
		add(g, "parley.mail.imap.defaultMailboxes", "Default mailboxes", BOOLEAN, "true", "Create Sent, Drafts, Trash, Junk and Archive for new users.");

		g = group("pop3", "POP3", "Settings of both POP3 services.");
		add(g, "parley.mail.pop3.requireTls", "Require TLS", BOOLEAN, "false", "Refuse USER, PASS, APOP and AUTH until STLS.");
		add(g, "parley.mail.pop3.autologout", "Idle timeout (ms)", NUMBER, "600000", "Idle sessions are closed. RFC 1939 requires at least 10 minutes.");
		add(g, "parley.mail.pop3.utf8Downgrade", "UTF-8 for old clients", CHOICE, "surrogate",
				"What a client that didn't send UTF8 gets for a message with UTF-8 headers: the RFC 6858 surrogate, or reject.", "surrogate", "reject");

		g = group("listeners", "Listeners", "Each service's own settings: its port (0 turns it off) and limits. Empty uses the default.");
		for (ServiceId s : ServiceId.values()) {
			String p = s.prefix + ".";
			String l = s.label + ": ";
			add(g, s.portProperty(), l + "port", PORT, Integer.toString(s.standardPort), s.description + ". 0 turns it off.");
			add(g, p + "bindAddress", l + "bind address", TEXT, "every address", "Listen on this address only.");
			add(g, p + "maxConnections", l + "most connections", NUMBER, "0 (no limit)", "Connections at once.");
			add(g, p + "maxConnectionsPerClient", l + "most connections per client", NUMBER, "0 (no limit)", "Connections at once from one address.");
			add(g, p + "allowedClients", l + "allowed clients", LIST, "everyone", "Addresses and networks allowed to connect, e.g. 127.0.0.1 10.0.0.0/8 ::1.");
			add(g, p + "maxLoginAttempts", l + "login attempts", NUMBER, "3", "Failed logins before the connection is closed.");
			add(g, p + "loginFailureDelay", l + "failed login delay (ms)", NUMBER, "1000", "Delay before answering a failed login.");
			add(g, p + "loginTimeLimit", l + "login time limit (ms)", NUMBER, "0 (no limit)", "Time a connection has to log in.");
			add(g, p + "virtualThreads", l + "virtual threads", CHOICE, "AUTO", "Run sessions on virtual threads (Java 21).", "AUTO", "ON", "OFF");
			add(g, p + "keyStoreName", l + "key store", PATH, "the TLS page's", "A key store for this service only.");
			add(g, p + "keyStorePassword", l + "key store password", SECRET, null, "Its password.");
		}

		g = group("admin", "Admin", "This web application's own settings.");
		add(g, ADMIN_AUTO_START, "Start services with the application", BOOLEAN, "true", "Start every service whose port isn't 0 when the web application starts.");
		add(g, ADMIN_HISTORY_DAYS, "Keep SMTP history (days)", NUMBER, "90", "Older history files are deleted.");
		add(g, ADMIN_LOG_BUFFER, "Log entries kept in memory", NUMBER, "5000", "The Logs page shows these.");
		add(g, ADMIN_LOG_FILE_SIZE, "Log file size (bytes)", NUMBER, "10485760", "The log file (logs/parley-mail.log in the admin home) is rotated at this size; 5 old files are kept.");
	}

	private Group group(String id, String label, String description) {
		Group g = new Group(id, label, description);
		groups.put(id, g);
		return g;
	}

	private void add(Group g, String key, String label, SettingDef.Type type, String defaultValue, String description, String... options) {
		SettingDef d = new SettingDef(key, g.id, label, type, defaultValue, description, options);
		g.settings.add(d);
		byKey.put(key, d);
	}

	public List<Group> getGroups() {
		return Collections.unmodifiableList(new ArrayList<>(groups.values()));
	}

	public SettingDef get(String key) {
		return byKey.get(key);
	}

	/** True for settings whose values are never sent to the browser. */
	public boolean isSecret(String key) {
		SettingDef d = byKey.get(key);
		if (d != null) {
			return d.getType() == SECRET;
		}
		String lower = key.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("password") || lower.contains("secret");
	}
}
