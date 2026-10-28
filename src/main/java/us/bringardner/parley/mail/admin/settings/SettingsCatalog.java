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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import us.bringardner.parley.mail.admin.suite.ServiceId;

/**
 * The settings the Settings page offers, in groups. The names are Parley 2.0.0's; the descriptions
 * and defaults follow the parley-smtp, parley-imap, parley-pop3, parley-mail and parley-core
 * READMEs. Settings that aren't listed here can still be set (the page's "Other settings").
 * <p>
 * Each server reads only its own settings, under its prefix ({@code parley.mail.submission.requireTls}
 * is the submission server's, {@code parley.mail.smtp.requireTls} the relay's), so a group of one
 * protocol's servers lists its settings once per server ({@link Group#servers}). The mail store and the
 * SMTP queue carry their own settings, in their own files: {@value #STORE_PREFIX}NAME is NAME in the
 * store's {@code .parley-store.properties}, {@value #QUEUE_PREFIX}NAME is NAME in the queue's
 * {@code queue.properties} ({@link SettingsService}).
 */
public final class SettingsCatalog {

	/** A group of settings: one tab of the Settings page. */
	public static final class Group {
		public final String id;
		public final String label;
		public final String description;
		public final List<SettingDef> settings = new ArrayList<>();
		/** The servers whose own settings the group has (each setting once per server), or empty */
		public final List<ServiceId> servers;

		Group(String id, String label, String description, ServiceId... servers) {
			this.id = id;
			this.label = label;
			this.description = description;
			this.servers = Collections.unmodifiableList(Arrays.asList(servers));
		}
	}

	/** The settings of the mail store's own file (.parley-store.properties in the mail root) */
	public static final String STORE_PREFIX = "store.";
	/** The settings of the SMTP queue's own file (queue.properties in the queue's folder) */
	public static final String QUEUE_PREFIX = "queue.";

	public static final String ROOT = "parley.mail.root";
	public static final String FILE_SOURCE = "parley.mail.fileSource";
	public static final String USERS = "parley.mail.users";
	public static final String TLS_KEY_STORE = "parley.mail.tls.keyStore";
	public static final String TLS_KEY_STORE_PASSWORD = "parley.mail.tls.keyStorePassword";
	public static final String TLS_KEY_STORE_TYPE = "parley.mail.tls.keyStoreType";
	/** The relay's queue (a folder under the mail root, default {@value #DEFAULT_QUEUE}): the one the Queue tab and page show */
	public static final String SMTP_QUEUE = "parley.mail.smtp.queue";
	public static final String DEFAULT_QUEUE = ".smtp-queue";
	public static final String STORE_SHARING = STORE_PREFIX + "sharing";
	/**
	 * The mail servers' name: the queue's hostname, and set with it, each SMTP server's
	 * ({@link #SMTP_HOSTNAMES}): one field on the SMTP tab.
	 */
	public static final String QUEUE_HOSTNAME = QUEUE_PREFIX + "hostname";
	/** Each SMTP server's hostname, which {@link #QUEUE_HOSTNAME} sets too */
	public static final List<String> SMTP_HOSTNAMES = Collections.unmodifiableList(Arrays.asList(
			ServiceId.SMTP.prefix + ".hostname", ServiceId.SUBMISSION.prefix + ".hostname", ServiceId.SUBMISSIONS.prefix + ".hostname"));
	public static final String QUEUE_DOMAINS = QUEUE_PREFIX + "domains";
	public static final String QUEUE_ALIASES = QUEUE_PREFIX + "aliases";
	public static final String QUEUE_DKIM_KEYS = QUEUE_PREFIX + "dkim.keys";
	/** Days of history the queue keeps (parley-smtp's QueueHistory). */
	public static final String QUEUE_HISTORY_DAYS = QUEUE_PREFIX + "historyDays";
	public static final String QUEUE_HISTORY = QUEUE_PREFIX + "history";
	public static final String QUEUE_HISTORY_DIRECTORY = QUEUE_PREFIX + "historyDirectory";
	public static final String ADMIN_AUTO_START = "parley.mail.admin.autoStart";
	/** The primary domain: its administrators manage every domain, other administrators only their own. */
	public static final String ADMIN_PRIMARY_DOMAIN = "parley.mail.admin.primaryDomain";
	/** One or more JMX service URLs of launcher processes; empty: the servers run in this application. */
	public static final String ADMIN_JMX_URL = "parley.mail.admin.jmxUrl";
	public static final String ADMIN_JMX_USER = "parley.mail.admin.jmxUser";
	public static final String ADMIN_JMX_PASSWORD = "parley.mail.admin.jmxPassword";
	/** A file whose first line is the JMX password (the parley-servers package makes it): used when the password isn't set */
	public static final String ADMIN_JMX_PASSWORD_FILE = "parley.mail.admin.jmxPasswordFile";
	public static final String ADMIN_LOG_FILES = "parley.mail.admin.logFiles";
	/** The TSIG key file (name algorithm secret per line) the DNS page signs its updates with */
	public static final String ADMIN_DNS_KEY_FILE = "parley.mail.admin.dnsKeyFile";
	/** The address the DNS page sends updates to, when it isn't the host of the DNS server's JMX address */
	public static final String ADMIN_DNS_HOST = "parley.mail.admin.dnsHost";
	public static final String ACL_RELOAD_SECONDS = "parley.net.acl.reloadSeconds";
	public static final String ADMIN_LOG_BUFFER = "parley.mail.admin.logBufferSize";
	public static final String ADMIN_LOG_FILE_SIZE = "parley.mail.admin.logFileSize";

	private static final ServiceId[] SMTP_SERVERS = { ServiceId.SMTP, ServiceId.SUBMISSION, ServiceId.SUBMISSIONS };

	private final Map<String, Group> groups = new LinkedHashMap<>();
	private final Map<String, SettingDef> byKey = new LinkedHashMap<>();

	public SettingsCatalog() {
		Group g = group("general", "General", "The mail store's place, the users and the settings every server shares.");
		add(g, ROOT, "Mail root", PATH, null,
				"Where the mail lives: one directory per user (or per domain/user), the SMTP queue (.smtp-queue) and DMARC report data. Shared by SMTP, IMAP and POP3; required. The store's own settings are on the Store tab.");
		add(g, FILE_SOURCE, "Mail root file system", TEXT, "the default (local files)",
				"The parley-files FileSource factory for the mail root, e.g. a database or SFTP factory id. Empty for local files.");
		add(g, USERS, "Users file", PATH, null,
				"The user list every server uses (FileBasedAcl format). The servers read it again when it changes (Users page or not), within the time below.");
		add(g, ACL_RELOAD_SECONDS, "Users file check (seconds)", NUMBER, "15",
				"How often the servers look for changes to the users file; 0: never (a restart reads it).");
		add(g, "parley.net.acl.maxConcurrentHashes", "Password checks at once", NUMBER, "the number of processors",
				"How many password hashes are checked at the same time (each takes about 0.1 s of a processor), so a flood of logins can't use every processor.");
		add(g, "parley.net.acl.hashWaitMs", "Password check wait (ms)", NUMBER, "30000",
				"How long a login waits for its turn to check a password before it fails.");

		g = group("store", "Store", "The mail store's own settings, in .parley-store.properties in the mail root: every server that uses the store reads them, so they can't disagree. A store without the file isn't used.");
		add(g, STORE_SHARING, "Sharing", CHOICE, null,
				"Required. off: one server at a time (a second is refused, in this process or another). host: several servers on this machine. cluster: several on any machines. The servers of one process count as several: the parley-mail service needs host.",
				"off", "host", "cluster");
		add(g, STORE_PREFIX + "lockDomain", "Lock domain", TEXT, "mail", "The name of the store's lock domain (parley-net locks); the same for every server.");
		add(g, STORE_PREFIX + "lockProvider", "Lock provider", TEXT, "auto",
				"How the store's locks are kept between servers: auto, region, lease or a class name (parley-net's README).");
		add(g, STORE_PREFIX + "allowUnsafe", "Allow unsafe locks", BOOLEAN, "false", "Allow a lock provider whose locks wouldn't reach every server.");

		g = group("tls", "TLS", "The key store for STARTTLS, STLS and implicit TLS. A service with a key store of its own (Listeners) uses that instead.");
		add(g, TLS_KEY_STORE, "Key store", PATH, null, "The key store file (e.g. made from a Let's Encrypt certificate). Without one, STARTTLS isn't offered and the implicit TLS services can't start.");
		add(g, TLS_KEY_STORE_PASSWORD, "Key store password", SECRET, null, "The key store's password.");
		add(g, TLS_KEY_STORE_TYPE, "Key store type", CHOICE, "PKCS12", "The key store's format.", "PKCS12", "JKS");
		add(g, "parley.core.forceTlsVersion", "Force TLS version", TEXT, null, "Allow only this TLS version (e.g. TLSv1.3). Empty: the JVM's defaults.");

		g = group("smtp", "SMTP", "Each SMTP server's own settings: choose the server. The host name is one for all of them.", SMTP_SERVERS);
		add(g, QUEUE_HOSTNAME, "Host name", TEXT, null,
				"Required. The name in the greeting, EHLO, Received fields and notifications: every SMTP server's (" + String.join(", ", SMTP_HOSTNAMES)
						+ ") and the queue's. It must match the PTR record of the server's public address and the MX records.");
		perServer(g, "relayNetworks", "Relay networks", LIST, null,
				"Client networks that may send to other domains without logging in, e.g. 127.0.0.1/32,10.0.0.0/8.");
		perServer(g, "requireTls", "Require TLS", BOOLEAN, "false", "Offer AUTH (and on the submission ports, accept MAIL) only after STARTTLS.");
		perServer(g, "maxMessageSize", "Largest message (bytes)", NUMBER, "52428800", "The SIZE limit.");
		perServer(g, "maxRecipients", "Recipients per message", NUMBER, "100", "More are refused (452).");
		perServer(g, "timeout", "Idle timeout (ms)", NUMBER, "300000", "Sessions idle this long are closed. RFC 5321 requires at least 5 minutes.");
		perServer(g, "maxHops", "Most Received fields", NUMBER, "100", "A message with more counts as a loop and is refused.");

		g = group("queue", "Queue", "The SMTP queue's own settings, in queue.properties in its folder: every SMTP server that uses the queue reads them when it starts, so they can't disagree about how mail is delivered. A queue without the file isn't used.");
		add(g, QUEUE_DOMAINS, "Local domains", LIST, null, "Required. Domains whose mail is delivered here, comma separated. Managed on the Domains page.");
		add(g, QUEUE_ALIASES, "Aliases file", PATH, null, "The aliases (name: member, member ...). Managed on the Aliases page.");
		add(g, QUEUE_PREFIX + "postmaster", "Postmaster", TEXT, "postmaster", "The user who receives postmaster mail.");
		add(g, QUEUE_PREFIX + "resolver", "MX resolver", CHOICE, "jdk",
				"jdk: the JDK's DNS. parley-dns: parley-dns with the DNS servers below (or /etc/resolv.conf). parley-dns-iterative: parley-dns from the root servers.",
				"jdk", "parley-dns", "parley-dns-iterative");
		add(g, QUEUE_PREFIX + "dnsServers", "DNS servers", LIST, "from /etc/resolv.conf", "DNS servers for parley-dns, comma separated. The checks of incoming mail use them too.");
		add(g, QUEUE_PREFIX + "preferIpv6", "Prefer IPv6", BOOLEAN, "false", "With parley-dns: try mail hosts' IPv6 addresses first.");
		add(g, QUEUE_PREFIX + "tls", "STARTTLS to other servers", CHOICE, "opportunistic", "none, opportunistic or required.", "none", "opportunistic", "required");
		add(g, QUEUE_PREFIX + "remotePort", "Other servers' port", PORT, "25", "The port of other mail servers.");
		add(g, QUEUE_PREFIX + "relayHost", "Smart host", TEXT, null,
				"host:port of a server (your provider's) that sends all outgoing mail. Many networks block outgoing port 25.");
		add(g, QUEUE_PREFIX + "relayUser", "Smart host user", TEXT, null, "The user for the smart host.");
		add(g, QUEUE_PREFIX + "relayPassword", "Smart host password", TEXT, null,
				"Where the password is kept, never the password: @keychain:parley/smarthost (parley-credentials-keychain), @file:/etc/parley/smarthost.secret or @env:NAME.");
		add(g, QUEUE_PREFIX + "relayAuth", "Smart host SASL", LIST, "PLAIN", "SASL mechanisms for the smart host, best first, e.g. SCRAM-SHA-256,PLAIN.");
		add(g, QUEUE_PREFIX + "relayTls", "Smart host TLS", CHOICE, "required", "TLS to the smart host.", "none", "opportunistic", "required");
		add(g, QUEUE_PREFIX + "workers", "Delivery threads", NUMBER, "4", "Messages delivered at the same time.");
		add(g, QUEUE_PREFIX + "retry", "Retry schedule (minutes)", LIST, "1,5,15,30,60,120", "Minutes between attempts after a temporary failure; the last repeats.");
		add(g, QUEUE_PREFIX + "delayWarningHours", "Delay warning (hours)", NUMBER, "4", "The sender is told the message is delayed after this long.");
		add(g, QUEUE_PREFIX + "maxAgeHours", "Give up after (hours)", NUMBER, "120", "The message is returned to the sender after this long.");
		add(g, QUEUE_HISTORY, "Keep SMTP history", BOOLEAN, "true", "The queue writes the history the SMTP History page shows.");
		add(g, QUEUE_HISTORY_DIRECTORY, "SMTP history folder", PATH, "<mail root>/.smtp-history", "Where the queue writes it.");
		add(g, QUEUE_HISTORY_DAYS, "Keep SMTP history (days)", NUMBER, "30", "Older history files are deleted; 0 keeps every day.");
		add(g, QUEUE_DKIM_KEYS, "DKIM keys", LIST, null, "Signing keys, domain:selector:keyfile, comma separated. Made on the Domains page.");
		add(g, QUEUE_PREFIX + "dkim.headers", "DKIM signed fields", LIST, "From, To, Cc, Subject, Date, Message-ID, MIME and List-* fields", "Fields to sign, comma separated (From is required).");
		add(g, QUEUE_PREFIX + "arc.seal", "ARC seal", BOOLEAN, "true", "Seal received mail that is forwarded (when a DKIM key fits the seal domain).");
		add(g, QUEUE_PREFIX + "arc.domain", "ARC domain", TEXT, null, "The d= of our ARC seals; none: no seal.");
		add(g, QUEUE_PREFIX + "arc.trustedSealers", "Trusted ARC sealers", LIST, null, "Domains whose passing ARC chains override a DMARC failure.");
		add(g, QUEUE_PREFIX + "dmarc.aggregateReports", "DMARC aggregate reports", BOOLEAN, "false", "Send rua= reports to the domains that ask for them.");
		add(g, QUEUE_PREFIX + "dmarc.failureReports", "DMARC failure reports", BOOLEAN, "false", "Send ruf= reports.");
		add(g, QUEUE_PREFIX + "dmarc.reportEmail", "Report sender", TEXT, null, "The reports' From address; required with reports.");
		add(g, QUEUE_PREFIX + "dmarc.reportOrgName", "Reporting organization", TEXT, null, "The org_name of the reports; required with reports.");
		add(g, QUEUE_PREFIX + "dmarc.reportIntervalHours", "Report interval (hours)", NUMBER, "24", "How often aggregate reports go out.");
		add(g, QUEUE_PREFIX + "dmarc.maxFailureReportsPerHour", "Failure reports per hour", NUMBER, "10", "Per domain.");

		g = group("smtp-auth", "Authentication", "Each SMTP server's checks of incoming mail: DKIM, SPF, DMARC and ARC (signing and sealing outgoing mail is the queue's).", SMTP_SERVERS);
		perServer(g, "dkim.verify", "Verify DKIM", BOOLEAN, "true", "Verify the signatures of incoming mail.");
		perServer(g, "spf.check", "Check SPF", BOOLEAN, "true", "Check SPF for incoming mail.");
		perServer(g, "spf.rejectFail", "Refuse SPF fail", BOOLEAN, "false", "Refuse MAIL FROM when SPF says fail (550 5.7.23).");
		perServer(g, "dmarc.check", "Check DMARC", BOOLEAN, "true", "Check DMARC for incoming mail.");
		perServer(g, "dmarc.enforce", "Enforce DMARC", BOOLEAN, "false", "Refuse p=reject mail, deliver p=quarantine mail to Junk.");
		perServer(g, "dmarc.publicSuffixList", "Public suffix list", PATH, "the included copy", "A newer public_suffix_list.dat.");
		perServer(g, "arc.verify", "Verify ARC", BOOLEAN, "true", "Validate the ARC chains of incoming mail.");

		g = group("imap", "IMAP", "Each IMAP server's own settings: choose the server.", ServiceId.IMAP, ServiceId.IMAPS);
		perServer(g, "requireTls", "Require TLS", BOOLEAN, "false", "Refuse LOGIN and AUTHENTICATE until STARTTLS.");
		perServer(g, "autologout", "Idle timeout (ms)", NUMBER, "1800000", "Idle sessions are closed. RFC 9051 requires at least 30 minutes.");
		perServer(g, "appendLimit", "Largest APPEND (bytes)", NUMBER, "104857600", "Larger messages get NO [TOOBIG].");
		perServer(g, "defaultMailboxes", "Default mailboxes", BOOLEAN, "true", "Create Sent, Drafts, Trash, Junk and Archive for new users.");

		g = group("pop3", "POP3", "Each POP3 server's own settings: choose the server.", ServiceId.POP3, ServiceId.POP3S);
		perServer(g, "requireTls", "Require TLS", BOOLEAN, "false", "Refuse USER, PASS, APOP and AUTH until STLS.");
		perServer(g, "autologout", "Idle timeout (ms)", NUMBER, "600000", "Idle sessions are closed. RFC 1939 requires at least 10 minutes.");
		perServer(g, "utf8Downgrade", "UTF-8 for old clients", CHOICE, "surrogate",
				"What a client that didn't send UTF8 gets for a message with UTF-8 headers: the RFC 6858 surrogate, or reject.", "surrogate", "reject");

		g = group("listeners", "Listeners", "Each service's own settings: its port (0 turns it off) and limits. Empty uses the default.");
		for (ServiceId s : ServiceId.values()) {
			String p = s.prefix + ".";
			String l = s.label + ": ";
			add(g, s.portProperty(), l + "port", PORT, s.defaultPort == 0 ? "0 (off)" : Integer.toString(s.defaultPort),
					s.description + ". 0 turns it off." + (s.defaultPort == 0 ? " The standard port is " + s.standardPort + "; it needs a key store." : ""));
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
		add(g, ADMIN_JMX_URL, "Servers' JMX address", LIST, "empty: the servers run in this application",
				"Where the servers run: empty runs them in this application (parley-net's launcher). One or more JMX service URLs, comma separated "
						+ "(service:jmx:rmi:///jndi/rmi://localhost:9010/jmxrmi), manage launcher processes run as services instead. They must read the settings files of this page.");
		add(g, ADMIN_JMX_USER, "JMX user", TEXT, null, "The user for the JMX addresses, if they need one.");
		add(g, ADMIN_JMX_PASSWORD, "JMX password", SECRET, null, "Its password.");
		add(g, ADMIN_JMX_PASSWORD_FILE, "JMX password file", PATH, null,
				"A file whose first line is the password (the parley-servers package makes /etc/parley/jmx/admin.password); used when the password above isn't set.");
		add(g, ADMIN_DNS_KEY_FILE, "DNS key file", PATH, null,
				"A TSIG key file (one 'name algorithm secret' per line) with a key the DNS server lets send updates (its parley.dns.updateKeys): "
						+ "the DNS page changes records with it. The parley-servers package makes /etc/parley/dns/admin.key.");
		add(g, ADMIN_DNS_HOST, "DNS server address", TEXT, "the host of its JMX address",
				"Where the DNS page sends record changes, when the DNS server doesn't listen on the host of its JMX address.");
		add(g, ADMIN_PRIMARY_DOMAIN, "Primary domain", TEXT, "the first administrator's domain",
				"Administrators of this domain manage every domain; an administrator of another domain manages only that domain's users, aliases and DKIM keys, and the queue and history of its mail. "
						+ "Set when the first administrator is made (or, if empty, from the first administrator in the users file).");
		add(g, ADMIN_AUTO_START, "Start services with the application", BOOLEAN, "true",
				"When the servers run in this application: start every service whose port isn't 0 when the web application starts.");
		add(g, ADMIN_LOG_BUFFER, "Log entries kept in memory", NUMBER, "5000", "The Logs page shows these.");
		add(g, ADMIN_LOG_FILE_SIZE, "Log file size (bytes)", NUMBER, "10485760", "The log file (logs/parley-mail.log in the admin home) is rotated at this size; 5 old files are kept.");
		add(g, ADMIN_LOG_FILES, "More log files", LIST, null,
				"Log files the Logs page shows besides its own and the servers' (the logFile setting of the settings files), comma separated.");
	}

	private Group group(String id, String label, String description, ServiceId... servers) {
		Group g = new Group(id, label, description, servers);
		groups.put(id, g);
		return g;
	}

	private void add(Group g, String key, String label, SettingDef.Type type, String defaultValue, String description, String... options) {
		SettingDef d = new SettingDef(key, g.id, label, type, defaultValue, description, options);
		g.settings.add(d);
		byKey.put(key, d);
	}

	/** A setting each of the group's servers has, under its own prefix */
	private void perServer(Group g, String name, String label, SettingDef.Type type, String defaultValue, String description, String... options) {
		for (ServiceId s : g.servers) {
			String key = s.prefix + "." + name;
			SettingDef d = new SettingDef(key, g.id, s.id(), label, type, defaultValue, description, options);
			g.settings.add(d);
			byKey.put(key, d);
		}
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

	/** True for the store's and the queue's own settings ({@value #STORE_PREFIX}NAME, {@value #QUEUE_PREFIX}NAME). */
	public static boolean isAssetKey(String key) {
		return key.startsWith(STORE_PREFIX) || key.startsWith(QUEUE_PREFIX);
	}
}
