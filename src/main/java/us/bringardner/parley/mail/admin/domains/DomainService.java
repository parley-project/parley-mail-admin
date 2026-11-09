package us.bringardner.parley.mail.admin.domains;

import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.admin.FileRewrite;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.users.AliasStore;
import us.bringardner.parley.mail.admin.users.UserRecord;
import us.bringardner.parley.mail.admin.users.UserService;
import us.bringardner.parley.smtp.dkim.DkimKeys;
import us.bringardner.parley.smtp.spam.NullSpamFilter;
import us.bringardner.parley.smtp.spam.Spam;
import us.bringardner.parley.smtp.spam.SpamProvider;
import us.bringardner.parley.smtp.spam.SpamProviders;

/**
 * The local domains ({@code parley.mail.smtp.domains}): adding and removing them, each one's users
 * and aliases, its DKIM signing key ({@code parley.mail.smtp.dkim.keys}) and the DNS records it
 * needs (MX, SPF, DKIM, DMARC).
 */
public final class DomainService {

	private static final Pattern DOMAIN = Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?)*");
	private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9\\-_]{0,62})");

	/** One entry of parley.mail.smtp.dkim.keys. */
	public static final class DkimEntry {
		public final String domain;
		public final String selector;
		public final String keyFile;

		DkimEntry(String domain, String selector, String keyFile) {
			this.domain = domain;
			this.selector = selector;
			this.keyFile = keyFile;
		}

		String format() {
			return domain + ":" + selector + ":" + keyFile;
		}
	}

	private final SettingsService settings;
	private final UserService users;
	private final AliasStore aliases;
	private final FileSource dkimDir;

	/**
	 * @param dkimDir where new DKIM keys go: a folder on the local disk, since parley-smtp's DKIM
	 *            signer reads the key files with java.io.File
	 */
	public DomainService(SettingsService settings, UserService users, AliasStore aliases, FileSource dkimDir) {
		this.settings = settings;
		this.users = users;
		this.aliases = aliases;
		this.dkimDir = dkimDir;
	}

	/** The local domains (A-labels, lower case), in the order of the setting. */
	public List<String> list() {
		Set<String> ret = new LinkedHashSet<>();
		String v = settings.getEffective(SettingsCatalog.QUEUE_DOMAINS);
		if (v == null) {
			return new ArrayList<>(); // none: not taken from the host name
		}
		for (String d : v.split(",")) {
			if (!d.trim().isEmpty()) {
				ret.add(normalize(d));
			}
		}
		return new ArrayList<>(ret);
	}

	public boolean isLocal(String domain) {
		return list().contains(normalize(domain));
	}

	/** The mail servers' host name (the queue's), or null: it isn't taken from the machine's name */
	private String hostname() {
		String h = settings.getEffective(SettingsCatalog.QUEUE_HOSTNAME);
		return h == null || h.trim().isEmpty() ? null : h.trim();
	}

	/** A domain as an A-label in lower case. */
	public static String normalize(String domain) {
		String d = domain.trim();
		if (d.endsWith(".")) {
			d = d.substring(0, d.length() - 1);
		}
		try {
			return IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return d.toLowerCase(Locale.ROOT);
		}
	}

	public void add(String domain) throws IOException {
		String d = normalize(domain == null ? "" : domain);
		if (!DOMAIN.matcher(d).matches() || !d.contains(".") && !d.equals("localhost")) {
			throw new IllegalArgumentException("'" + domain + "' is not a domain name");
		}
		List<String> list = list();
		if (list.contains(d)) {
			throw new IllegalArgumentException(d + " is already a local domain");
		}
		list.add(d);
		settings.set(SettingsCatalog.QUEUE_DOMAINS, String.join(",", list));
	}

	/**
	 * Remove a local domain. Its users and aliases stay (they get no mail until the domain is added
	 * again); its DKIM key entry is removed (the key file is kept).
	 */
	public void remove(String domain) throws IOException {
		String d = normalize(domain);
		List<String> list = list();
		if (!list.remove(d)) {
			throw new IllegalArgumentException(d + " is not a local domain");
		}
		if (list.isEmpty()) {
			throw new IllegalArgumentException("The last local domain can't be removed");
		}
		Map<String, String> changes = new LinkedHashMap<>();
		changes.put(SettingsCatalog.QUEUE_DOMAINS, String.join(",", list));
		List<DkimEntry> keys = dkimEntries();
		if (keys.removeIf(k -> k.domain.equals(d))) {
			changes.put(SettingsCatalog.QUEUE_DKIM_KEYS, formatKeys(keys));
		}
		Map<String, String> spam = spamOverrides();
		if (spam.remove(d) != null) {
			changes.put(SettingsCatalog.QUEUE_SPAM_DOMAINS, formatSpam(spam));
		}
		settings.update(changes);
	}

	/** The value of the spam override of a domain that has none: it follows the global choice. */
	public static final String SPAM_DEFAULT = "default";

	/** The spam overrides (lower case domain to "off" or a filter id), in the order of the setting. */
	private Map<String, String> spamOverrides() {
		Map<String, String> ret = new LinkedHashMap<>();
		String v = settings.getEffective(SettingsCatalog.QUEUE_SPAM_DOMAINS);
		if (v != null && !v.trim().isEmpty()) {
			ret.putAll(Spam.parseDomains(v));
		}
		return ret;
	}

	private static String formatSpam(Map<String, String> overrides) {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, String> e : overrides.entrySet()) {
			parts.add(e.getKey() + ":" + e.getValue());
		}
		return parts.isEmpty() ? null : String.join(",", parts);
	}

	/** The spam override of a domain: {@value #SPAM_DEFAULT} (follow the global choice), "off" or a filter id. */
	public String spamOverride(String domain) {
		String v = spamOverrides().get(normalize(domain));
		return v == null ? SPAM_DEFAULT : v;
	}

	/** The filter id that checks a domain's mail ("off" if none): its override, else the global choice. */
	public String spamEffective(String domain) {
		String o = spamOverride(domain);
		if (!o.equals(SPAM_DEFAULT)) {
			return o;
		}
		String enabled = settings.getEffective(SettingsCatalog.SPAM_ENABLED);
		String provider = settings.getEffective(SettingsCatalog.SPAM_PROVIDER);
		if (enabled == null || !Boolean.parseBoolean(enabled.trim()) || provider == null || provider.trim().isEmpty()
				|| provider.trim().equalsIgnoreCase(NullSpamFilter.ID)) {
			return Spam.OFF;
		}
		return provider.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Set the spam filter of a domain.
	 *
	 * @param filter {@value #SPAM_DEFAULT} (follow the global choice), "off" (or "none"), or the id of a filter
	 */
	public void setSpam(String domain, String filter) throws IOException {
		String d = normalize(domain);
		if (!isLocal(d)) {
			throw new IllegalArgumentException(d + " is not a local domain");
		}
		String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
		if (f.equals(NullSpamFilter.ID)) {
			f = Spam.OFF;
		}
		if (!f.equals(SPAM_DEFAULT) && !f.equals(Spam.OFF) && SpamProviders.get(f) == null) {
			throw new IllegalArgumentException("'" + filter + "' is not a spam provider (" + SPAM_DEFAULT + ", " + Spam.OFF + " or " + SpamProviders.ids() + ")");
		}
		Map<String, String> overrides = spamOverrides();
		if (f.equals(SPAM_DEFAULT)) {
			overrides.remove(d);
		} else {
			overrides.put(d, f);
		}
		settings.set(SettingsCatalog.QUEUE_SPAM_DOMAINS, formatSpam(overrides));
	}

	public List<DkimEntry> dkimEntries() {
		List<DkimEntry> ret = new ArrayList<>();
		String v = settings.getEffective(SettingsCatalog.QUEUE_DKIM_KEYS);
		if (v == null) {
			return ret;
		}
		for (String e : v.split(",")) {
			String[] p = e.trim().split(":", 3);
			if (p.length == 3) {
				ret.add(new DkimEntry(normalize(p[0]), p[1].trim(), p[2].trim()));
			}
		}
		return ret;
	}

	private static String formatKeys(List<DkimEntry> keys) {
		List<String> parts = new ArrayList<>();
		for (DkimEntry k : keys) {
			parts.add(k.format());
		}
		return parts.isEmpty() ? null : String.join(",", parts);
	}

	public DkimEntry dkimFor(String domain) {
		String d = normalize(domain);
		for (DkimEntry k : dkimEntries()) {
			if (k.domain.equals(d)) {
				return k;
			}
		}
		return null;
	}

	/**
	 * Make a DKIM key of a domain's own and put it in the queue's dkim.keys (replacing the domain's
	 * key there); mail from the domain is signed with it instead of the shared key. The key file goes
	 * in the admin home's dkim directory, with the TXT record to publish next to it.
	 *
	 * @param type "rsa" (2048 bits) or "ed25519"
	 */
	public DkimEntry generateDkim(String domain, String selector, String type) throws IOException {
		String d = normalize(domain);
		if (!isLocal(d)) {
			throw new IllegalArgumentException(d + " is not a local domain");
		}
		if (selector == null || !SELECTOR.matcher(selector.trim()).matches()) {
			throw new IllegalArgumentException("A selector is letters, digits, - and _ (e.g. mail2026)");
		}
		String sel = selector.trim();
		String t = type == null || type.isEmpty() ? "rsa" : type.toLowerCase(Locale.ROOT);
		if (!t.equals("rsa") && !t.equals("ed25519")) {
			throw new IllegalArgumentException("The key type is rsa or ed25519");
		}
		KeyPair pair;
		try {
			pair = DkimKeys.generate(t);
		} catch (GeneralSecurityException e) {
			throw new IOException("Can't make a " + t + " key: " + e.getMessage(), e);
		}
		FileSource key = dkimDir.getChild(d + "-" + sel + ".pem");
		// the folder's and the process's usual permissions (the SMTP servers, maybe another user in the
		// same group, read the key); a key file made again keeps its permissions and group
		FileRewrite.write(key, DkimKeys.toPem(pair.getPrivate()).getBytes(StandardCharsets.US_ASCII));
		FileRewrite.write(dnsFile(key), DkimKeys.dnsRecord(pair.getPublic()).getBytes(StandardCharsets.US_ASCII));
		List<DkimEntry> keys = dkimEntries();
		keys.removeIf(k -> k.domain.equals(d));
		DkimEntry e = new DkimEntry(d, sel, key.getAbsolutePath().replace('\\', '/'));
		keys.add(e);
		settings.set(SettingsCatalog.QUEUE_DKIM_KEYS, formatKeys(keys));
		return e;
	}

	private static FileSource dnsFile(FileSource key) throws IOException {
		return key.getParentFile().getChild(key.getName() + ".txt");
	}

	/**
	 * A key file dkim.keys names: on the local disk, where parley-smtp's DKIM signer reads it
	 * (java.io.File)
	 */
	private static FileSource keyFile(String path) throws IOException {
		return FileSourceFactory.fileProxyFactory.createFileSource(path);
	}

	private static boolean keyFileExists(String path) {
		try {
			return keyFile(path).isFile();
		} catch (IOException e) {
			return false;
		}
	}

	private static String read(FileSource f, java.nio.charset.Charset charset) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), charset);
		}
	}

	/**
	 * The domain whose key signs mail from every local domain without one of its own (the queue's
	 * dkim.shared, parley-smtp), or null.
	 */
	public String sharedDomain() {
		String v = settings.getEffective(SettingsCatalog.QUEUE_DKIM_SHARED);
		return v == null || v.trim().isEmpty() ? null : normalize(v);
	}

	/** The shared key's entry, or null when no key is shared or the shared domain has none. */
	public DkimEntry sharedKey() {
		String s = sharedDomain();
		return s == null ? null : dkimFor(s);
	}

	/**
	 * Keep the shared key the primary domain's: dkim.shared names the primary domain while it has a
	 * key, and nothing otherwise. A domain without a key of its own signs with it, and with Parley DNS
	 * gets its record from the shared zone, so it needs no zone file.
	 *
	 * @return true if the setting changed
	 */
	public boolean shareKeyOf(String primary) throws IOException {
		String want = primary == null || primary.trim().isEmpty() || dkimFor(normalize(primary)) == null ? null : normalize(primary);
		if (java.util.Objects.equals(want, sharedDomain())) {
			return false;
		}
		settings.set(SettingsCatalog.QUEUE_DKIM_SHARED, want);
		return true;
	}

	/** The DKIM TXT record of a domain's key, or null if it can't be worked out. */
	public String dkimRecord(DkimEntry e) {
		try {
			FileSource key = keyFile(e.keyFile);
			FileSource txt = dnsFile(key);
			if (txt.isFile()) {
				return read(txt, StandardCharsets.US_ASCII).trim();
			}
			if (key.isFile()) {
				// (DkimKeys.privateKey(File) reads the PEM as US-ASCII: its text, read here)
				PrivateKey pk = DkimKeys.privateKey(read(key, StandardCharsets.US_ASCII));
				if (pk instanceof RSAPrivateCrtKey) {
					RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) pk;
					PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
					return DkimKeys.dnsRecord(pub);
				}
			}
		} catch (IOException | GeneralSecurityException | RuntimeException ex) {
			return null;
		}
		return null;
	}

	/** What the Domains page shows for a domain. */
	public Map<String, Object> describe(String domain) {
		String d = normalize(domain);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("domain", d);
		String unicode = IDN.toUnicode(d, IDN.ALLOW_UNASSIGNED);
		if (!unicode.equals(d)) {
			m.put("unicode", unicode);
		}
		int userCount = 0;
		for (UserRecord u : users.list()) {
			if (d.equals(u.getDomain())) {
				userCount++;
			}
		}
		m.put("users", userCount);
		int aliasCount = 0;
		for (String a : aliases.list().keySet()) {
			int at = a.lastIndexOf('@');
			if (at > 0 && normalize(a.substring(at + 1)).equals(d)) {
				aliasCount++;
			}
		}
		m.put("aliases", aliasCount);
		DkimEntry k = dkimFor(d);
		String shared = sharedDomain();
		DkimEntry signing = k != null ? k : sharedKey();
		if (signing != null) {
			Map<String, Object> dk = new LinkedHashMap<>();
			dk.put("selector", signing.selector);
			dk.put("keyFile", signing.keyFile);
			dk.put("keyFileExists", keyFileExists(signing.keyFile));
			// own: the domain has a key of its own; shared: it's the key the others sign with
			dk.put("own", k != null);
			dk.put("shared", signing.domain.equals(shared));
			dk.put("domain", signing.domain);
			m.put("dkim", dk);
		}
		Map<String, Object> spam = new LinkedHashMap<>();
		spam.put("override", spamOverride(d));
		spam.put("effective", spamEffective(d));
		List<Map<String, String>> filters = new ArrayList<>();
		for (SpamProvider f : SpamProviders.all()) {
			if (!f.getId().equals(NullSpamFilter.ID)) {
				Map<String, String> fm = new LinkedHashMap<>();
				fm.put("id", f.getId());
				fm.put("label", f.getTitle());
				filters.add(fm);
			}
		}
		spam.put("filters", filters);
		m.put("spam", spam);
		m.put("dns", dnsRecords(d, k));
		return m;
	}

	/**
	 * The records to publish for a domain: with no key of its own, the shared key's record at its
	 * selector in this domain (with Parley DNS, from the shared zone).
	 */
	public List<Map<String, String>> dnsRecords(String d, DkimEntry k) {
		if (k == null) {
			k = sharedKey();
		}
		String host = hostname();
		List<Map<String, String>> ret = new ArrayList<>();
		ret.add(record("MX", d + ".", host == null ? "10 (set the host name on Settings > SMTP)" : "10 " + host + "."));
		ret.add(record("TXT", d + ".", "v=spf1 mx -all"));
		if (k != null) {
			String txt = dkimRecord(k);
			if (txt != null) {
				ret.add(record("TXT", k.selector + "._domainkey." + d + ".", txt));
			}
		}
		ret.add(record("TXT", "_dmarc." + d + ".", "v=DMARC1; p=none; rua=mailto:postmaster@" + d));
		return ret;
	}

	private static Map<String, String> record(String type, String name, String value) {
		Map<String, String> r = new LinkedHashMap<>();
		r.put("type", type);
		r.put("name", name);
		r.put("value", value);
		return r;
	}
}
