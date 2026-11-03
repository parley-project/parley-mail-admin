package us.bringardner.parley.mail.admin.dns;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import us.bringardner.parley.dns.server.SharedZoneText;
import us.bringardner.parley.dns.server.SharedZoneText.Record;

/**
 * The zone files the admin writes: the shared zone's ({@code parley.dns.sharedZone}) and a new
 * domain's that inherits it. The DNS server only reads zone files; after a change here it is told
 * to read them again ({@code reload}), so the change is served at once.
 * <p>
 * A change to the shared zone writes its file whole from its records (comments are not kept), raises
 * its serial past the file's unless the change set a higher one, sets the TTL of the record's whole
 * set (RFC 2181 5.2), keeps the file it replaces as {@code <file>.previous}, and is refused when the
 * file changed while it was being made (edited by hand meanwhile), so nothing is lost.
 */
final class ZoneFiles {

	/** A domain name in lower case: labels of letters, digits and '-' (not at either end) */
	static final java.util.regex.Pattern DOMAIN = java.util.regex.Pattern.compile(
			"(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

	/** The lines before the records in a shared zone file the admin writes */
	static final String HEADER = "; The shared zone (parley.dns.sharedZone): the records every listed domain serves under its own\n"
			+ "; name, and zones with $INHERIT inherit. Written by parley-mail-admin's DNS page when its records\n"
			+ "; are changed (comments are not kept: edit it there or by hand, not both); the file before each\n"
			+ "; change is kept as <file>.previous.\n";

	/** One change at a time, in this application */
	private static final Object LOCK = new Object();

	private ZoneFiles() {
	}

	/** The shared zone's records as its file has them, the SOA first */
	static List<Record> sharedRecords(File file) throws IOException {
		return SharedZoneText.records(read(file), file.getAbsoluteFile().getParentFile());
	}

	static void addShared(File file, String name, String type, long ttl, List<String> values, Runnable reload) throws Exception {
		String n = sharedName(name);
		String t = SharedZoneText.editableType(type);
		if (t.equals("SOA")) {
			throw new IllegalArgumentException("The shared zone has one SOA: change it instead");
		}
		List<String> add = new ArrayList<String>();
		for (String v : values == null ? List.<String>of() : values) {
			if (v != null && !v.trim().isEmpty()) {
				add.add(v.trim());
			}
		}
		if (add.isEmpty()) {
			throw new IllegalArgumentException("No value to add");
		}
		editShared(file, recs -> {
			for (String v : add) {
				boolean have = false;
				for (Record r : recs) {
					have |= r.same(n, t, v);
				}
				if (!have) {
					recs.add(after(recs, n), new Record(n, ttl, t, v));
				}
			}
			setTtl(recs, n, t, ttl);
		}, reload);
	}

	static void changeShared(File file, String name, String type, String data, String newName, String newType, long ttl, String newData,
			Runnable reload) throws Exception {
		String n = sharedName(name);
		String nn = sharedName(newName);
		String nt = SharedZoneText.editableType(newType);
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		if (newData == null || newData.trim().isEmpty()) {
			throw new IllegalArgumentException("The record needs data");
		}
		if (t.equals("SOA") != nt.equals("SOA") || (nt.equals("SOA") && !nn.equals("@"))) {
			throw new IllegalArgumentException("The SOA stays the SOA, at @");
		}
		String nd = newData.trim();
		editShared(file, recs -> {
			int i = find(recs, n, t, data);
			recs.remove(i);
			boolean have = false;
			for (Record r : recs) {
				have |= r.same(nn, nt, nd);
			}
			if (!have) {
				recs.add(nt.equals("SOA") ? 0 : n.equalsIgnoreCase(nn) ? i : after(recs, nn), new Record(nn, ttl, nt, nd));
			}
			setTtl(recs, nn, nt, ttl);
		}, reload);
	}

	static void deleteShared(File file, String name, String type, String data, Runnable reload) throws Exception {
		String n = sharedName(name);
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		if (t.equals("SOA")) {
			throw new IllegalArgumentException("The shared zone's SOA can't be deleted");
		}
		editShared(file, recs -> recs.remove(find(recs, n, t, data)), reload);
	}

	/**
	 * Write a new domain's zone file, inheriting the shared zone: a $INHERIT line and an SOA like the
	 * shared zone's, serial 1. Refused if the file is there, or an old journal of the zone (it would be
	 * replayed into the new zone).
	 * @return the file written
	 */
	static File createInheriting(File zoneDir, File sharedFile, String domain) throws IOException {
		String name = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
		while (name.endsWith(".")) {
			name = name.substring(0, name.length() - 1);
		}
		if (!DOMAIN.matcher(name).matches()) {
			throw new IllegalArgumentException("Not a domain name: " + domain);
		}
		File file = new File(zoneDir, name + ".txt");
		synchronized (LOCK) {
			if (file.exists()) {
				throw new IllegalArgumentException("The zone file " + file + " exists already");
			}
			File journal = new File(file.getPath() + ".jnl");
			if (journal.exists()) {
				throw new IllegalArgumentException("An old journal of the zone is there: " + journal + "; move it away first");
			}
			String text = SharedZoneText.inheritingZone(name, read(sharedFile), zoneDir);
			File tmp = File.createTempFile("." + name, ".tmp", zoneDir);
			try {
				Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
				try {
					// readable by the DNS server whatever this process's umask (as the packaging makes zone files)
					Files.setPosixFilePermissions(tmp.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));
				} catch (UnsupportedOperationException e) {
					// not a POSIX file system
				}
				// a link appears whole and fails if the file is there by now (a move would replace it)
				Files.createLink(file.toPath(), tmp.toPath());
			} catch (java.nio.file.FileAlreadyExistsException e) {
				throw new IllegalArgumentException("The zone file " + file + " exists already");
			} finally {
				tmp.delete();
			}
		}
		return file;
	}

	private static int find(List<Record> recs, String name, String type, String data) {
		for (int i = 0; i < recs.size(); i++) {
			if (recs.get(i).same(name, type, data == null ? "" : data)) {
				return i;
			}
		}
		throw new IllegalStateException("The shared zone has no record " + name + " " + type + " " + data + " (changed meanwhile? read it again)");
	}

	/** Where a record of this name goes: after the last record of the name, or at the end. */
	private static int after(List<Record> recs, String name) {
		for (int i = recs.size() - 1; i > 0; i--) {
			if (recs.get(i).getName().equalsIgnoreCase(name)) {
				return i + 1;
			}
		}
		return recs.size();
	}

	private static void setTtl(List<Record> recs, String name, String type, long ttl) {
		for (int i = 0; i < recs.size(); i++) {
			Record r = recs.get(i);
			if (r.getName().equalsIgnoreCase(name) && r.getType().equals(type) && r.getTtl() != ttl) {
				recs.set(i, new Record(r.getName(), ttl, r.getType(), r.getData()));
			}
		}
	}

	/** A shared zone owner: @ for the domain, or a name below it, never absolute. */
	private static String sharedName(String name) {
		String n = name == null ? "" : name.trim();
		if (n.isEmpty() || n.equals("@")) {
			return "@";
		}
		if (n.endsWith(".")) {
			throw new IllegalArgumentException("Names in the shared zone are relative to each domain: " + n
					+ " ends with a dot (write www, not www.example.com.)");
		}
		return n.toLowerCase(Locale.ROOT);
	}

	private static String read(File f) throws IOException {
		if (!f.isFile()) {
			throw new IllegalStateException("The shared zone's file " + f + " isn't here (parley-mail-admin writes it where the DNS server"
					+ " reads it, so it must run on the same machine)");
		}
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	private static String stamp(File f) {
		return f.exists() ? f.lastModified() + "/" + f.length() : "-";
	}

	/**
	 * Change the shared zone's records and write its file: the serial raised past the file's (unless
	 * the change set a higher one), the text checked as the DNS server checks it, the file replaced
	 * whole (the one it replaces kept as file.previous), then the server told to read it again.
	 */
	private static void editShared(File file, Consumer<List<Record>> change, Runnable reload) throws Exception {
		synchronized (LOCK) {
			String stamp = stamp(file);
			String before = read(file);
			List<Record> recs;
			try {
				recs = new ArrayList<Record>(SharedZoneText.records(before, file.getAbsoluteFile().getParentFile()));
			} catch (IOException e) {
				throw new IllegalStateException(e.getMessage() + " (" + file + "; fix the file by hand)", e);
			}
			long old = serial(recs.get(0));
			change.accept(recs);
			Record soa = recs.get(0);
			if (serial(soa) <= old) {
				String[] f = soa.getData().trim().split("\\s+");
				f[2] = Long.toString(old + 1 > 0xFFFFFFFFL ? 1 : old + 1);
				recs.set(0, new Record(soa.getName(), soa.getTtl(), soa.getType(), String.join(" ", f)));
			}
			String t = SharedZoneText.text(recs, HEADER);
			try {
				SharedZoneText.check(t, file.getAbsoluteFile().getParentFile());
			} catch (IOException e) {
				throw new IllegalArgumentException(explain(e.getMessage(), recs), e);
			}
			write(file, t, stamp);
		}
		if (reload != null) {
			reload.run();
		}
	}

	private static long serial(Record soa) {
		String[] f = soa.getData().trim().split("\\s+");
		try {
			return f.length < 3 ? -1 : Long.parseLong(f[2]);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("The SOA's serial isn't a number: " + f[2]);
		}
	}

	/** A check's error, naming the record of the line it gives rather than the line of a file nobody has seen. */
	private static String explain(String message, List<Record> recs) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("at line (\\d+): (.*)", java.util.regex.Pattern.DOTALL)
				.matcher(String.valueOf(message));
		if (m.find()) {
			int i = Integer.parseInt(m.group(1)) - HEADER.split("\n").length - 1;
			if (i >= 0 && i < recs.size()) {
				Record r = recs.get(i);
				return "Not a valid record: " + r.getName() + " " + r.getType() + " " + r.getData() + " (" + m.group(2).trim() + ")";
			}
		}
		return message;
	}

	private static void write(File file, String t, String stamp) throws IOException {
		File f = file.getAbsoluteFile();
		File dir = f.getParentFile();
		File tmp = File.createTempFile("." + f.getName(), ".tmp", dir);
		try {
			Files.write(tmp.toPath(), t.getBytes(StandardCharsets.UTF_8));
			if (f.exists()) {
				try {
					Files.setPosixFilePermissions(tmp.toPath(), Files.getPosixFilePermissions(f.toPath()));
				} catch (UnsupportedOperationException e) {
					// not a POSIX file system
				}
			}
			if (!stamp(f).equals(stamp)) {
				throw new IllegalStateException("The shared zone's file " + f + " changed meanwhile (edited by hand?); nothing was"
						+ " written: read it again and redo the change");
			}
			if (f.exists()) {
				Files.copy(f.toPath(), new File(dir, f.getName() + ".previous").toPath(), StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.COPY_ATTRIBUTES);
			}
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			if (stamp(f).equals(stamp)) {
				// the DNS server sees a change by the file's time and length: make it see this one
				f.setLastModified(f.lastModified() + 1000);
			}
		} finally {
			tmp.delete();
		}
	}
}
