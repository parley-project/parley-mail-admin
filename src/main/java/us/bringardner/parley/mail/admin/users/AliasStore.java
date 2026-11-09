package us.bringardner.parley.mail.admin.users;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.admin.FileRewrite;

/**
 * The aliases file of parley-smtp ({@code parley.mail.smtp.aliases}): lines like
 * {@code sales@example.com: tony@example.com, jose@example.org}. Comments and order are kept. The
 * SMTP services read the file when they start, so a change needs them restarted. The file is on
 * the file system the SMTP servers read it on (the mail root's: {@code SettingsService.onQueueFileSystem}).
 */
public final class AliasStore {

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}(@[A-Za-z0-9][A-Za-z0-9.\\-]{0,252})?");
	private static final Pattern MEMBER = Pattern.compile("[^\\s,:#]{1,320}");

	private static final class Line {
		final String text;
		String name;
		List<String> members;

		Line(String text, String name, List<String> members) {
			this.text = text;
			this.name = name;
			this.members = members;
		}
	}

	private volatile FileSource file;
	private final List<Line> lines = new ArrayList<>();

	public AliasStore(FileSource file) {
		this.file = file;
	}

	public FileSource getFile() {
		return file;
	}

	public synchronized void setFile(FileSource file) throws IOException {
		this.file = file;
		load();
	}

	public synchronized void load() throws IOException {
		lines.clear();
		if (!file.exists()) {
			return;
		}
		String text;
		try (InputStream in = file.getInputStream()) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		for (String l : text.split("\r?\n", -1)) {
			String content = l;
			int hash = content.indexOf('#');
			if (hash >= 0) {
				content = content.substring(0, hash);
			}
			int colon = content.indexOf(':');
			if (colon <= 0) {
				lines.add(new Line(l, null, null));
				continue;
			}
			List<String> members = new ArrayList<>();
			for (String m : content.substring(colon + 1).split(",")) {
				if (!m.trim().isEmpty()) {
					members.add(m.trim());
				}
			}
			lines.add(new Line(l, content.substring(0, colon).trim(), members));
		}
		while (!lines.isEmpty() && lines.get(lines.size() - 1).name == null && lines.get(lines.size() - 1).text.isEmpty()) {
			lines.remove(lines.size() - 1);
		}
	}

	/** The aliases by name, in file order (a name on several lines has all their members, as in parley-smtp). */
	public synchronized Map<String, List<String>> list() {
		Map<String, List<String>> ret = new LinkedHashMap<>();
		for (Line l : lines) {
			if (l.name != null) {
				ret.computeIfAbsent(l.name, k -> new ArrayList<>()).addAll(l.members);
			}
		}
		return ret;
	}

	/** Set an alias's members (one line, replacing the alias's lines), adding it if it's new. */
	public synchronized void put(String name, List<String> members) throws IOException {
		if (name == null || !NAME.matcher(name.trim()).matches()) {
			throw new IllegalArgumentException("An alias name is letters, digits and . _ % + -, optionally followed by @domain");
		}
		name = normalize(name.trim());
		List<String> clean = new ArrayList<>();
		for (String m : members == null ? Collections.<String>emptyList() : members) {
			if (m == null || m.trim().isEmpty()) {
				continue;
			}
			if (!MEMBER.matcher(m.trim()).matches()) {
				throw new IllegalArgumentException("Invalid member '" + m + "'");
			}
			clean.add(m.trim());
		}
		if (clean.isEmpty()) {
			throw new IllegalArgumentException("An alias needs at least one member");
		}
		boolean replaced = false;
		for (Line l : lines) {
			if (l.name != null && l.name.equalsIgnoreCase(name)) {
				if (!replaced) {
					l.name = name;
					l.members = clean;
					replaced = true;
				} else {
					l.name = null; // the alias's other lines go
					l.members = null;
				}
			}
		}
		if (!replaced) {
			lines.add(new Line(null, name, clean));
		}
		save();
	}

	public synchronized boolean delete(String name) throws IOException {
		boolean found = false;
		for (Line l : lines) {
			if (l.name != null && l.name.equalsIgnoreCase(name)) {
				l.name = null;
				l.members = null;
				found = true;
			}
		}
		if (found) {
			save();
		}
		return found;
	}

	private static String normalize(String name) {
		int at = name.lastIndexOf('@');
		return at < 0 ? name.toLowerCase(Locale.ROOT) : name.substring(0, at).toLowerCase(Locale.ROOT) + "@" + name.substring(at + 1).toLowerCase(Locale.ROOT);
	}

	private void save() throws IOException {
		StringBuilder out = new StringBuilder();
		for (Line l : lines) {
			if (l.name != null) {
				out.append(l.name).append(": ").append(String.join(", ", l.members)).append('\n');
			} else if (l.text != null) {
				String t = l.text.trim();
				if (t.isEmpty() || t.startsWith("#")) {
					out.append(l.text).append('\n');
				}
			}
		}
		// through a temporary file, keeping the file's permissions and group
		FileRewrite.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
		load();
	}
}
