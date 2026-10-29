package us.bringardner.parley.mail.admin.dns;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import us.bringardner.parley.dns.ByteBuffer;
import us.bringardner.parley.dns.DNS;
import us.bringardner.parley.dns.DnsTransport;
import us.bringardner.parley.dns.Message;
import us.bringardner.parley.dns.RR;
import us.bringardner.parley.dns.Tsig;
import us.bringardner.parley.dns.Utility;
import us.bringardner.parley.dns.server.DnsRecordInfo;
import us.bringardner.parley.dns.server.DnsRecordType;
import us.bringardner.parley.dns.server.DnsServerMXBean;
import us.bringardner.parley.dns.server.DnsZoneInfo;
import us.bringardner.parley.dns.server.Zone;
import us.bringardner.parley.mail.admin.settings.SettingsCatalog;
import us.bringardner.parley.mail.admin.settings.SettingsService;
import us.bringardner.parley.mail.admin.suite.MailSuite;

/**
 * The DNS page's work: the Parley DNS server a launcher runs (its {@code type=Dns} bean), the
 * domains' zones, which inherit the primary domain's records and add or override some, and their
 * records. Records are read from the bean and changed with dynamic updates (RFC 2136) signed with
 * the TSIG key in {@value SettingsCatalog#ADMIN_DNS_KEY_FILE}, sent to the DNS server's TCP port,
 * so they are journaled and the secondaries told, as for any update.
 */
public final class DnsAdmin {

	/** The bean type a launcher registers for its DNS server */
	public static final String TYPE = "Dns";
	static final int TIMEOUT = 10000;

	private final MailSuite suite;
	private final SettingsService settings;

	public DnsAdmin(MailSuite suite, SettingsService settings) {
		this.suite = suite;
		this.settings = settings;
	}

	/** The DNS server's bean, or null when no launcher reached runs a DNS server */
	public MailSuite.Typed<DnsServerMXBean> server() {
		return suite.typed(TYPE, DnsServerMXBean.class);
	}

	private MailSuite.Typed<DnsServerMXBean> require() {
		MailSuite.Typed<DnsServerMXBean> t = server();
		if (t == null) {
			throw new IllegalStateException("No Parley DNS server is running where the servers are managed");
		}
		return t;
	}

	static String norm(String name) {
		String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		while (n.endsWith(".")) {
			n = n.substring(0, n.length() - 1);
		}
		return n;
	}

	/**
	 * The zones the Parley DNS server serves (lower case): those of its zone files, and the listed
	 * domains it serves from the shared zone (parley.dns.domainList, parley.dns.sharedZone). None
	 * when no DNS server is reached.
	 */
	public java.util.Set<String> servedZones() {
		java.util.Set<String> ret = new java.util.HashSet<>();
		try {
			MailSuite.Typed<DnsServerMXBean> t = server();
			if (t != null) {
				for (DnsZoneInfo z : t.bean.getZones()) {
					ret.add(norm(z.getName()));
				}
			}
		} catch (RuntimeException e) {
			// not reached: none
		}
		return ret;
	}

	/** The server's state and its zones, for the primary domain's administrators */
	public Map<String, Object> status() {
		Map<String, Object> m = new LinkedHashMap<>();
		MailSuite.Typed<DnsServerMXBean> t = server();
		m.put("available", t != null);
		if (t == null) {
			return m;
		}
		DnsServerMXBean b = t.bean;
		m.put("server", t.server);
		m.put("where", t.where);
		m.put("state", b.getState());
		m.put("started", b.getStarted());
		m.put("port", b.getTcpPort());
		m.put("recursion", b.isRecursionAvailable());
		m.put("zoneDirectory", b.getZoneDirectory());
		m.put("updateKeys", List.of(b.getUpdateKeyNames()));
		m.put("keyReady", keyProblem(b) == null);
		m.put("keyProblem", keyProblem(b));
		List<Map<String, Object>> zones = new ArrayList<>();
		for (DnsZoneInfo z : b.getZones()) {
			zones.add(zone(z));
		}
		m.put("zones", zones);
		m.put("errors", List.of(b.getErrors()));
		m.put("sharedZone", sharedZoneFile(b));
		return m;
	}

	private static String sharedZoneFile(DnsServerMXBean b) {
		try {
			return b.getSharedZoneFile();
		} catch (RuntimeException e) {
			// a DNS server without the attribute: no shared zone to edit
			return null;
		}
	}

	/**
	 * The record types the DNS server can add and change, in its order, each with an example of
	 * its data (for the record form). They come from the server, so a type it learns shows here.
	 */
	public static List<Map<String, Object>> types(DnsServerMXBean b) {
		List<Map<String, Object>> ret = new ArrayList<>();
		for (DnsRecordType t : b.getRecordTypes()) {
			if (t.isEditable()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("name", t.getName());
				m.put("example", t.getExample());
				ret.add(m);
			}
		}
		return ret;
	}

	/** The shared zone (parley.dns.sharedZone): its file, its records (relative names) and the types, for the primary's administrators */
	public Map<String, Object> sharedZone() throws Exception {
		DnsServerMXBean b = require().bean;
		String f = sharedZoneFile(b);
		if (f == null) {
			throw new IllegalStateException("The DNS server has no shared zone (parley.dns.sharedZone is not set)");
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("file", f);
		List<Map<String, Object>> records = new ArrayList<>();
		for (DnsRecordInfo r : b.getSharedRecords()) {
			records.add(record(r));
		}
		m.put("records", records);
		m.put("types", types(b));
		return m;
	}

	/** Add records to the shared zone (one per value); the TTL becomes the set's */
	public void addSharedRecords(String name, String type, long ttl, List<String> values) throws Exception {
		require().bean.addSharedRecords(name, type, ttl, values == null ? new String[0] : values.toArray(new String[0]));
	}

	/** Replace one record of the shared zone with another (any name, type, TTL and data) */
	public void changeSharedRecord(String name, String type, String data, String newName, String newType, long ttl, String newData) throws Exception {
		require().bean.changeSharedRecord(name, type, data, newName, newType, ttl, newData);
	}

	/** Delete one record of the shared zone */
	public void deleteSharedRecord(String name, String type, String data) throws Exception {
		require().bean.deleteSharedRecord(name, type, data);
	}

	private static Map<String, Object> record(DnsRecordInfo r) {
		Map<String, Object> x = new LinkedHashMap<>();
		x.put("name", r.getName());
		x.put("type", r.getType());
		x.put("ttl", r.getTtl());
		x.put("data", r.getData());
		x.put("inherited", r.isInherited());
		return x;
	}

	private static Map<String, Object> zone(DnsZoneInfo z) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", z.getName());
		m.put("inheritFrom", z.getInheritFrom());
		m.put("serial", z.getSerial());
		m.put("ownSerial", z.getOwnSerial());
		m.put("records", z.getRecords());
		m.put("ownRecords", z.getOwnRecords());
		m.put("signed", z.isSigned());
		return m;
	}

	public void reload() throws Exception {
		require().bean.reload();
	}

	/**
	 * A domain's zone: whether it is served, what it inherits from, its records (own or inherited),
	 * and the mail records the domain needs with whether the zone serves them.
	 * @param primary the primary domain (whose zone the others inherit)
	 * @param mailRecords the records the Domains page recommends (type, name, value)
	 */
	public Map<String, Object> zone(String domain, String primary, List<Map<String, String>> mailRecords) {
		String d = norm(domain);
		DnsServerMXBean b = require().bean;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("domain", d);
		m.put("primary", norm(primary));
		m.put("isPrimary", d.equals(norm(primary)));
		DnsZoneInfo info = null;
		for (DnsZoneInfo z : b.getZones()) {
			if (z.getName().equalsIgnoreCase(d)) {
				info = z;
			}
		}
		m.put("exists", info != null);
		m.put("primaryServed", primary != null && hasZone(b, norm(primary)));
		m.put("types", types(b));
		if (info == null) {
			return m;
		}
		m.putAll(zone(info));
		List<Map<String, Object>> records = new ArrayList<>();
		DnsRecordInfo[] list = b.getRecords(d);
		for (DnsRecordInfo r : list) {
			records.add(record(r));
		}
		m.put("records", records);
		List<Map<String, Object>> mail = new ArrayList<>();
		for (Map<String, String> want : mailRecords) {
			Map<String, Object> x = new LinkedHashMap<>(want);
			x.put("status", mailStatus(list, want));
			mail.add(x);
		}
		m.put("mail", mail);
		return m;
	}

	private static boolean hasZone(DnsServerMXBean b, String name) {
		for (DnsZoneInfo z : b.getZones()) {
			if (z.getName().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** "served" (by the zone, own or inherited), "different" (the name has other data of that type) or "missing" */
	static String mailStatus(DnsRecordInfo[] list, Map<String, String> want) {
		String name = norm(want.get("name"));
		String type = want.get("type");
		String value = want.get("value");
		boolean other = false;
		for (DnsRecordInfo r : list) {
			if (norm(r.getName()).equals(name) && r.getType().equals(type)) {
				if (sameData(type, r.getData(), value)) {
					return "served";
				}
				if (!"TXT".equals(type) || isSameKind(r.getData(), value)) {
					other = true;
				}
			}
		}
		return other ? "different" : "missing";
	}

	/** Two TXT values of the same kind (v=spf1, v=DMARC1, v=DKIM1): one replaces the other */
	static boolean isSameKind(String data, String value) {
		String a = txtText(data).toLowerCase(Locale.ROOT);
		String b = txtText(value).toLowerCase(Locale.ROOT);
		for (String k : new String[] { "v=spf1", "v=dmarc1", "v=dkim1" }) {
			if (a.startsWith(k) && b.startsWith(k)) {
				return true;
			}
		}
		return false;
	}

	static boolean sameData(String type, String data, String value) {
		if ("TXT".equals(type)) {
			return txtText(data).equals(txtText(value));
		}
		return norm(data).equals(norm(value));
	}

	/** A TXT record's text without the quotes and string breaks: "v=DKIM1; " "p=..." is v=DKIM1; p=... */
	static String txtText(String data) {
		String d = data.trim();
		if (!d.startsWith("\"")) {
			return d;
		}
		StringBuilder b = new StringBuilder();
		boolean in = false;
		for (int i = 0; i < d.length(); i++) {
			char c = d.charAt(i);
			if (c == '"') {
				in = !in;
			} else if (c == '\\' && in && i + 1 < d.length()) {
				b.append(d.charAt(++i));
			} else if (in) {
				b.append(c);
			}
		}
		return b.toString();
	}

	/** A TXT value as zone file text: quoted, in strings of at most 255 bytes, unless it is quoted already */
	static String txtData(String value) {
		String v = value.trim();
		if (v.startsWith("\"")) {
			return v;
		}
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < v.length(); i += 250) {
			String part = v.substring(i, Math.min(v.length(), i + 250)).replace("\\", "\\\\").replace("\"", "\\\"");
			if (b.length() > 0) {
				b.append(' ');
			}
			b.append('"').append(part).append('"');
		}
		return b.toString();
	}

	/** Make the domain's zone file: it inherits every record of the shared zone (parley.dns.sharedZone) */
	public void createZone(String domain, String primary) throws Exception {
		String d = norm(domain);
		String p = norm(primary);
		if (d.equals(p)) {
			throw new IllegalStateException("The primary domain's zone is made by hand");
		}
		require().bean.createInheritingZone(d);
	}

	/**
	 * Replace the domain's own records of this type at this name with these values (an empty list
	 * removes them, so an inherited set of that type shows through again).
	 * @param name the owner: a name in the domain, relative (www, @) or absolute
	 */
	public void setRecords(String domain, String name, String type, long ttl, List<String> values) throws Exception {
		String d = norm(domain);
		String owner = owner(name, d);
		String t = checkType(require().bean, type);
		if (t.equals("SOA")) {
			throw new IllegalArgumentException("A zone has one SOA: change it instead");
		}
		Message m = update(d);
		m.getAuthority().add(empty(owner, typeOf(t), DNS_CLASS_ANY));
		Set<String> seen = new LinkedHashSet<>();
		for (String v : values) {
			if (v == null || v.trim().isEmpty() || !seen.add(v.trim())) {
				continue;
			}
			m.getAuthority().add(record(owner, ttl, t, v.trim()));
		}
		send(d, m);
	}

	/**
	 * Add records (one per value) of a type at a name to the domain's own records; those it has
	 * stay. The TTL becomes the TTL of the whole set (RFC 2181 5.2). At a name and type where the
	 * domain inherits records, its own set replaces the inherited one (an override).
	 */
	public void addRecords(String domain, String name, String type, long ttl, List<String> values) throws Exception {
		String d = norm(domain);
		String owner = owner(name, d);
		String t = checkType(require().bean, type);
		List<String> all = ownValues(d, owner, t, null);
		for (String v : values == null ? List.<String>of() : values) {
			if (v != null && !v.trim().isEmpty()) {
				all.add(v.trim());
			}
		}
		if (all.isEmpty()) {
			throw new IllegalArgumentException("No value to add");
		}
		setRecords(d, owner, t, ttl, all);
	}

	/**
	 * Replace one of the domain's own records with another: any name, type, TTL and data, in one
	 * update. The TTL becomes the TTL of every record of the new name and type. The SOA stays the
	 * zone's SOA; its serial is raised past the zone's own if the new one isn't higher.
	 */
	public void changeRecord(String domain, String name, String type, String data, String newName, String newType, long ttl, String newData) throws Exception {
		String d = norm(domain);
		DnsServerMXBean b = require().bean;
		String owner = owner(name, d);
		String t = checkType(b, type);
		String nOwner = owner(newName, d);
		String nt = checkType(b, newType);
		if (newData == null || newData.trim().isEmpty()) {
			throw new IllegalArgumentException("The record needs data");
		}
		if (t.equals("SOA") || nt.equals("SOA")) {
			if (!t.equals(nt) || !nOwner.equals(d)) {
				throw new IllegalArgumentException("The SOA stays the zone's SOA, at @");
			}
			changeSoa(d, b, ttl, newData.trim());
			return;
		}
		boolean own = false;
		for (DnsRecordInfo r : b.getRecords(d)) {
			own |= !r.isInherited() && norm(r.getName()).equals(owner) && r.getType().equals(t) && sameData(t, r.getData(), data);
		}
		if (!own) {
			throw new IllegalStateException(relative(owner, d) + " " + t + " " + data + " isn't one of " + d + "'s own records (changed meanwhile? read it again)");
		}
		Message m = update(d);
		RR old = record(owner, 0, t, data);
		old.setDnsClass(DNS_CLASS_NONE);
		old.setTTL(0);
		m.getAuthority().add(old);
		List<String> values = ownValues(d, nOwner, nt, owner.equals(nOwner) && t.equals(nt) ? data : null);
		values.add(newData.trim());
		m.getAuthority().add(empty(nOwner, typeOf(nt), DNS_CLASS_ANY));
		Set<String> seen = new LinkedHashSet<>();
		for (String v : values) {
			if (seen.add(v.trim())) {
				m.getAuthority().add(record(nOwner, ttl, nt, v.trim()));
			}
		}
		send(d, m);
	}

	/** The new SOA, its serial raised past the zone's own (the one served adds the shared zone's) when it isn't higher */
	private void changeSoa(String d, DnsServerMXBean b, long ttl, String data) throws Exception {
		long own = -1, served = -1;
		for (DnsZoneInfo z : b.getZones()) {
			if (z.getName().equalsIgnoreCase(d)) {
				own = z.getOwnSerial();
				served = z.getSerial();
			}
		}
		if (own < 0) {
			throw new IllegalStateException("The DNS server has no zone " + d);
		}
		String[] f = data.split("\\s+");
		if (f.length != 7) {
			throw new IllegalArgumentException("An SOA is: primary-server mailbox serial refresh retry expire minimum");
		}
		long typed;
		try {
			typed = Long.parseLong(f[2]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The SOA's serial isn't a number: " + f[2]);
		}
		long next = Math.max(typed - (served - own), own + 1);
		f[2] = Long.toString(next > 0xFFFFFFFFL ? 1 : next);
		Message m = update(d);
		m.getAuthority().add(record(d, ttl, "SOA", String.join(" ", f)));
		send(d, m);
	}

	/** The data of the domain's own records of a type at a name (without one of them, or none) */
	private List<String> ownValues(String d, String owner, String type, String without) {
		List<String> ret = new ArrayList<>();
		for (DnsRecordInfo r : require().bean.getRecords(d)) {
			if (!r.isInherited() && norm(r.getName()).equals(owner) && r.getType().equals(type)
					&& (without == null || !sameData(type, r.getData(), without))) {
				ret.add(r.getData());
			}
		}
		return ret;
	}

	/** Delete one of the domain's own records (by its data) */
	public void deleteRecord(String domain, String name, String type, String data) throws Exception {
		String d = norm(domain);
		String owner = owner(name, d);
		String t = checkType(require().bean, type);
		if (t.equals("SOA")) {
			throw new IllegalArgumentException("A zone's SOA can't be deleted");
		}
		RR rr = record(owner, 0, t, data);
		rr.setDnsClass(DNS_CLASS_NONE);
		rr.setTTL(0);
		Message m = update(d);
		m.getAuthority().add(rr);
		send(d, m);
	}

	/**
	 * Put the mail records the domain needs into its zone: each one not served yet replaces the
	 * domain's own records of its kind at its name (an SPF record replaces the SPF record, the other
	 * TXT records stay), keeping inherited values of other kinds by copying them.
	 * @return the records changed
	 */
	public List<Map<String, String>> publishMail(String domain, List<Map<String, String>> mailRecords) throws Exception {
		String d = norm(domain);
		DnsServerMXBean b = require().bean;
		DnsRecordInfo[] list = b.getRecords(d);
		List<Map<String, String>> changed = new ArrayList<>();
		for (Map<String, String> want : mailRecords) {
			if (want.get("value") == null || want.get("value").contains("(set ")) {
				continue; // not ready, e.g. no host name yet
			}
			if ("served".equals(mailStatus(list, want))) {
				continue;
			}
			String name = norm(want.get("name"));
			String type = want.get("type");
			List<String> values = new ArrayList<>();
			long ttl = 3600;
			for (DnsRecordInfo r : list) {
				if (norm(r.getName()).equals(name) && r.getType().equals(type)) {
					ttl = r.getTtl();
					if ("TXT".equals(type) && !isSameKind(r.getData(), want.get("value"))) {
						values.add(r.getData()); // another TXT record at the name: kept
					}
				}
			}
			values.add("TXT".equals(type) ? txtData(want.get("value")) : want.get("value"));
			setRecords(d, name, type, ttl, values);
			list = b.getRecords(d);
			changed.add(want);
		}
		return changed;
	}

	/** A type's number from its mnemonic (A, MX, TXT...) */
	static int typeOf(String type) {
		for (int i = 0; i < Utility.TYPENAMES.length; i++) {
			if (type.equalsIgnoreCase(Utility.TYPENAMES[i])) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unknown record type " + type);
	}

	static final int DNS_CLASS_ANY = 255;
	static final int DNS_CLASS_NONE = 254;

	/** The type, if the DNS server can change records of it (its own list, see {@link #types}) */
	private static String checkType(DnsServerMXBean b, String type) {
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		List<String> names = new ArrayList<>();
		for (Map<String, Object> m : types(b)) {
			names.add((String) m.get("name"));
		}
		if (!names.contains(t)) {
			throw new IllegalArgumentException("Records of type " + type + " can't be changed; one of " + names);
		}
		return t;
	}

	/** The owner name in the domain: @ or empty is the domain, a relative name is below it */
	static String owner(String name, String domain) {
		String n = name == null ? "" : name.trim();
		if (n.isEmpty() || n.equals("@")) {
			return domain;
		}
		String lower = norm(n);
		if (n.endsWith(".") || lower.equals(domain) || lower.endsWith("." + domain)) {
			if (!lower.equals(domain) && !lower.endsWith("." + domain)) {
				throw new IllegalArgumentException(n + " isn't in " + domain);
			}
			return lower;
		}
		return lower + "." + domain;
	}

	private static RR record(String owner, long ttl, String type, String data) {
		try {
			return Zone.parseRecord(owner + ".", ttl, DNS.IN, typeOf(type), data);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Not a valid " + type + " record: " + data + " (" + e.getMessage() + ")");
		}
	}

	private static RR empty(String name, int type, int cls) {
		RR rr = new RR(name, type, cls);
		rr.setRdata(new byte[0]);
		rr.setTTL(0);
		return rr;
	}

	private static Message update(String zone) {
		Message m = new Message();
		m.setQuestion(zone, DNS.SOA, DNS.IN);
		m.setID(new java.security.SecureRandom().nextInt(0x10000));
		m.getHeader().setOPCODE(DNS.UPDATE);
		return m;
	}

	/** Why records can't be changed (no key file, or none of its keys may update), or null */
	public String keyProblem(DnsServerMXBean b) {
		try {
			key(b);
			return null;
		} catch (IllegalStateException e) {
			return e.getMessage();
		}
	}

	private Tsig.Key key(DnsServerMXBean b) {
		String file = settings.getEffective(SettingsCatalog.ADMIN_DNS_KEY_FILE);
		if (file == null || file.trim().isEmpty()) {
			throw new IllegalStateException("Changing records needs a TSIG key: set " + SettingsCatalog.ADMIN_DNS_KEY_FILE
					+ " to a key file the DNS server reads (parley.dns.tsigKeyFile) with a key in its parley.dns.updateKeys");
		}
		Tsig.KeyRing ring;
		try {
			ring = Tsig.KeyRing.load(new File(file.trim()));
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("Can't read the TSIG key file " + file + ": " + e.getMessage());
		}
		for (String name : b.getUpdateKeyNames()) {
			Tsig.Key k = ring.get(name);
			if (k != null) {
				return k;
			}
		}
		throw new IllegalStateException("None of the keys in " + file + " may send updates to the DNS server (its parley.dns.updateKeys: "
				+ String.join(", ", b.getUpdateKeyNames()) + ")");
	}

	/** Sign the update, send it over TCP and check the answer */
	private void send(String zone, Message m) throws Exception {
		MailSuite.Typed<DnsServerMXBean> t = require();
		Tsig.Key key = key(t.bean);
		String host = settings.getEffective(SettingsCatalog.ADMIN_DNS_HOST);
		if (host == null || host.trim().isEmpty()) {
			host = t.host;
		}
		int port = t.bean.getTcpPort();
		Tsig.Session session = Tsig.Session.client(key);
		byte[] wire = session.signRequest(m.toByteArray());
		byte[] answer;
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host.trim(), port), TIMEOUT);
			s.setSoTimeout(TIMEOUT);
			OutputStream out = s.getOutputStream();
			out.write(DnsTransport.frame(wire));
			out.flush();
			answer = DnsTransport.readFramed(s, System.currentTimeMillis() + TIMEOUT);
		} catch (IOException e) {
			throw new IOException("Can't reach the DNS server at " + host + ":" + port + " (" + e.getMessage() + "); set "
					+ SettingsCatalog.ADMIN_DNS_HOST + " to an address it listens on", e);
		}
		session.verifyResponse(answer);
		Message r = new Message(new ByteBuffer(answer));
		int rc = r.getResponseCode();
		if (rc != DNS.NOERROR) {
			throw new IllegalStateException("The DNS server refused the change to " + zone + ": " + rcode(rc));
		}
	}

	static String rcode(int rc) {
		switch (rc) {
		case 1: return "FORMERR (a record it can't take)";
		case 2: return "SERVFAIL (see its log)";
		case 3: return "NXDOMAIN";
		case 5: return "REFUSED (the key may not update, or the type can't be updated)";
		case 6: return "YXDOMAIN";
		case 7: return "YXRRSET";
		case 8: return "NXRRSET";
		case 9: return "NOTAUTH (it doesn't serve that zone, or the key was refused)";
		case 10: return "NOTZONE (a name outside the zone)";
		default: return "response code " + rc;
		}
	}

	/** For the record list: the domain's records relative to it (@ for the domain itself) */
	public static String relative(String name, String domain) {
		String n = norm(name);
		String d = norm(domain);
		if (n.equals(d)) {
			return "@";
		}
		return n.endsWith("." + d) ? n.substring(0, n.length() - d.length() - 1) : n + ".";
	}

	static List<String> unmodifiable(List<String> l) {
		return Collections.unmodifiableList(l);
	}
}
