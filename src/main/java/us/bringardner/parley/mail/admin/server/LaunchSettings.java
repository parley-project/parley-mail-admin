package us.bringardner.parley.mail.admin.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import us.bringardner.parley.files.FileSource;

/**
 * The settings of a {@link ServerLauncher}: settings files read in order (a later file wins),
 * the JVM's own system properties ({@code java -Dname=value}), which win over the files, and the
 * command line, which wins over both.
 * <p>
 * Each server gets a configuration of its own ({@link #configuration(String)}): the launcher's
 * files, then that server's files ({@link #addFile(String, FileSource, boolean)}), then the JVM's and the
 * command line's settings. Two servers can then have different values for the same name, and no
 * server sees another server's files. The system properties aren't changed, except for a server
 * whose provider still reads them ({@link #applyToSystem()}). {@link #apply()} reads the files
 * again (after a file changed).
 * <p>
 * The files are FileSources: the settings folder's are on the default file system, as
 * {@code ServerMain.confDir()} is.
 *
 * @since 2.0.0
 */
public final class LaunchSettings {

	private final Set<String> jvm;
	/** The JVM's own settings when the launcher was made (java -D, and Java's own) */
	private final Properties jvmValues = new Properties();
	private final Properties commandLine;
	/** The launcher's files, read for every server */
	private final List<FileSource> files = new ArrayList<>();
	/** Each server's own files, by its id */
	private final Map<String, List<FileSource>> serverFiles = new LinkedHashMap<>();
	private final Set<FileSource> required = new HashSet<>();
	/** Each file's settings, as {@link #apply()} last read them (a missing file has none) */
	private final Map<FileSource, Properties> read = new LinkedHashMap<>();
	/** The settings {@link #applyToSystem()} set, and their values */
	private final Map<String, String> fromFiles = new LinkedHashMap<>();

	/**
	 * @param commandLine the command line's settings ({@code -Dname=value} or {@code name value})
	 */
	public LaunchSettings(Properties commandLine) {
		this.commandLine = commandLine;
		this.jvm = new HashSet<>(System.getProperties().stringPropertyNames());
		this.jvm.removeAll(commandLine.stringPropertyNames());
		for (String name : jvm) {
			String v = System.getProperty(name);
			if (v != null) {
				jvmValues.setProperty(name, v);
			}
		}
	}

	/** Parse a command line: {@code -Dname=value} or {@code name value} pairs. */
	public static Properties parse(String[] args) {
		Properties p = new Properties();
		for (int idx = 0; idx < args.length; idx++) {
			if (args[idx].startsWith("-D")) {
				String[] tmp = args[idx].substring(2).split("=", 2);
				if (tmp.length == 2) {
					p.setProperty(tmp[0], tmp[1]);
				} else {
					System.out.println("Invalid arg = " + args[idx]);
				}
			} else if (idx + 1 < args.length) {
				p.setProperty(args[idx++], args[idx]);
			} else {
				System.out.println("Invalid arg = " + args[idx]);
			}
		}
		return p;
	}

	/**
	 * The value of a setting before the files are read: the command line's, else the JVM's, else null
	 */
	public String early(String name) {
		String v = commandLine.getProperty(name);
		return v != null ? v : jvmValues.getProperty(name);
	}

	/**
	 * Read this file too (after those added before, so it wins over them).
	 *
	 * @param mustExist true for a file someone named (an error if it isn't there); false for a
	 *                  default file, which is skipped when it isn't there
	 */
	public synchronized void addFile(FileSource f, boolean mustExist) {
		FileSource a = f.getAbsoluteFile();
		if (!files.contains(a)) {
			files.add(a);
		}
		if (mustExist) {
			required.add(a);
		}
	}

	/**
	 * Read this file for one server only (after the launcher's files and the server's files added
	 * before, so it wins over them).
	 *
	 * @param serverId  the server's id (its provider's)
	 * @param mustExist true for a file someone named; false for a default file, skipped when missing
	 */
	public synchronized void addFile(String serverId, FileSource f, boolean mustExist) {
		FileSource a = f.getAbsoluteFile();
		List<FileSource> list = serverFiles.computeIfAbsent(serverId, k -> new ArrayList<>());
		if (!list.contains(a)) {
			list.add(a);
		}
		if (mustExist) {
			required.add(a);
		}
	}

	/** Every file, the launcher's then each server's, in order, whether they exist or not */
	public synchronized List<FileSource> getFiles() {
		List<FileSource> ret = new ArrayList<>(files);
		for (List<FileSource> l : serverFiles.values()) {
			for (FileSource f : l) {
				if (!ret.contains(f)) {
					ret.add(f);
				}
			}
		}
		return Collections.unmodifiableList(ret);
	}

	/** The files one server reads, in order: the launcher's, then its own */
	public synchronized List<FileSource> getFiles(String serverId) {
		List<FileSource> ret = new ArrayList<>(files);
		List<FileSource> own = serverFiles.get(serverId);
		if (own != null) {
			for (FileSource f : own) {
				if (!ret.contains(f)) {
					ret.add(f);
				}
			}
		}
		return Collections.unmodifiableList(ret);
	}

	/**
	 * One server's configuration, from the files as {@link #apply()} last read them: the
	 * launcher's files, then the server's own, then the JVM's settings, then the command line's.
	 * A new copy each time: changing it changes nothing else.
	 */
	public synchronized Properties configuration(String serverId) {
		Properties ret = new Properties();
		for (FileSource f : getFiles(serverId)) {
			Properties p = read.get(f);
			if (p != null) {
				for (String name : p.stringPropertyNames()) {
					ret.setProperty(name, p.getProperty(name));
				}
			}
		}
		for (String name : jvmValues.stringPropertyNames()) {
			ret.setProperty(name, jvmValues.getProperty(name));
		}
		for (String name : commandLine.stringPropertyNames()) {
			ret.setProperty(name, commandLine.getProperty(name));
		}
		return ret;
	}

	/**
	 * Read the files (again). Nothing is changed if a named file can't be read.
	 *
	 * @throws IOException if a named file can't be read
	 */
	public synchronized void apply() throws IOException {
		Map<FileSource, Properties> now = new LinkedHashMap<>();
		for (FileSource f : getFiles()) {
			if (!f.isFile()) {
				if (required.contains(f)) {
					throw new IOException("Can't read the settings file " + f.getPath());
				}
				continue;
			}
			now.put(f, load(f));
		}
		read.clear();
		read.putAll(now);
	}

	/**
	 * For a server whose provider reads the system properties (not its configuration): read the
	 * files and set the system properties from every file (a later one wins), the JVM's settings
	 * and the command line, as before servers had configurations of their own. Settings a file no
	 * longer has are removed, unless the JVM or command line set them.
	 *
	 * @throws IOException if a named file can't be read (nothing is changed then)
	 */
	public synchronized void applyToSystem() throws IOException {
		apply();
		Map<String, String> merged = new LinkedHashMap<>();
		for (FileSource f : getFiles()) {
			Properties p = read.get(f);
			if (p != null) {
				for (String name : p.stringPropertyNames()) {
					merged.put(name, p.getProperty(name));
				}
			}
		}
		for (String name : fromFiles.keySet()) {
			if (!merged.containsKey(name) && !jvm.contains(name) && !commandLine.containsKey(name)) {
				System.clearProperty(name); // no longer in a file
			}
		}
		fromFiles.clear();
		for (Map.Entry<String, String> e : merged.entrySet()) {
			if (!jvm.contains(e.getKey()) && !commandLine.containsKey(e.getKey())) {
				System.setProperty(e.getKey(), e.getValue());
				fromFiles.put(e.getKey(), e.getValue());
			}
		}
		for (String name : commandLine.stringPropertyNames()) {
			System.setProperty(name, commandLine.getProperty(name));
		}
	}

	/**
	 * The names set, by where they were set: each file as {@link #apply()} last read it (the
	 * launcher's, then each server's), then the JVM's {@code -D} settings, then the command line's.
	 */
	public synchronized Map<String, Set<String>> sources() {
		Map<String, Set<String>> ret = new LinkedHashMap<>();
		for (FileSource f : getFiles()) {
			Properties p = read.get(f);
			if (p != null) {
				ret.put(f.getPath(), new java.util.TreeSet<>(p.stringPropertyNames()));
			}
		}
		ret.put("a -D setting", new java.util.TreeSet<>(jvmValues.stringPropertyNames()));
		ret.put("the command line", new java.util.TreeSet<>(commandLine.stringPropertyNames()));
		return ret;
	}

	/** Print the command line's settings, and which files were read (no secret values). */
	public synchronized void print(String serverName) {
		System.out.println("\nStarting " + serverName);
		for (String name : commandLine.stringPropertyNames()) {
			System.out.println("\t" + name + "=" + printable(name, commandLine.getProperty(name)));
		}
		for (FileSource f : getFiles()) {
			try {
				if (f.isFile()) {
					System.out.println("Loaded properties from " + f.getPath());
				}
			} catch (IOException e) {
				System.out.println("Can't reach " + f.getPath() + ": " + e.getMessage());
			}
		}
	}

	/** A settings file: UTF-8, or ISO-8859-1 (what Properties.load reads) when it isn't valid UTF-8. */
	static Properties load(FileSource f) throws java.io.IOException {
		byte[] bytes;
		try (InputStream in = f.getInputStream()) {
			bytes = in.readAllBytes();
		}
		String text;
		try {
			text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
					.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
					.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
		} catch (java.nio.charset.CharacterCodingException e) {
			text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
		}
		if (text.startsWith("\uFEFF")) {
			text = text.substring(1);
		}
		Properties ret = new Properties();
		ret.load(new java.io.StringReader(text));
		return ret;
	}

	/** A value for the console: hidden when the name says it's a password or secret. */
	static String printable(String name, String value) {
		String n = name.toLowerCase(java.util.Locale.ROOT);
		boolean secret = n.contains("password") || n.contains("secret") || n.contains("passphrase") || n.endsWith("token");
		return secret ? "********" : value;
	}
}
