package us.bringardner.parley.mail.admin.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.mail.store.MaildropRoot;
import us.bringardner.parley.smtp.queue.MailQueue;
import us.bringardner.parley.smtp.server.SmtpServer;

/**
 * Make a mail store's own settings and its queue's, in the store, on whatever file system it is
 * on: what the packaging's parley-servers-setup does with files on the local disk, for a store that isn't
 * ({@code parley.mail.fileSource} set, or {@code parley.mail.root} a {@code filesource:} URL). The
 * store is found from the mail servers' settings files exactly as a server finds it
 * ({@link MaildropRoot}). Nothing that is there is changed: each file is made once, from its
 * template, and a required setting is filled in only where it is empty.
 *
 * <pre>
 * java -cp 'lib/*' us.bringardner.parley.mail.admin.server.StoreSetup
 *     --settings /etc/parley/parley-mail.properties   (one or more; a later file wins)
 *     --store-template FILE    the store's .parley-store.properties when it has none
 *     --queue-template FILE    the queue's queue.properties when it has none
 *     [--queue DIR]            the queue's folder under the root (default .smtp-queue)
 *     [--fill KEY=VALUE]...    a required queue setting, where queue.properties has it empty
 *     [--require KEY]...       a required queue setting to report when it is empty
 * </pre>
 *
 * It prints what it made and set, and {@code missing KEY} for each required setting still empty;
 * exits with 1, saying why, when the store can't be opened or written.
 */
public final class StoreSetup {

	/** The servers' setting for the root */
	public static final String ROOT = "parley.mail.root";
	/** The servers' setting for the root's file system */
	public static final String FILE_SOURCE = "parley.mail.fileSource";
	/** The queue's folder under the root when none is given */
	public static final String DEFAULT_QUEUE = SmtpServer.QUEUE_DIRECTORY;
	/** The queue's settings file in its folder */
	public static final String QUEUE_SETTINGS_FILE = MailQueue.SETTINGS_FILE;

	private final PrintStream out;

	public StoreSetup(PrintStream out) {
		this.out = out;
	}

	public static void main(String[] args) {
		try {
			new StoreSetup(System.out).run(args);
		} catch (IllegalArgumentException e) {
			System.err.println("StoreSetup: " + e.getMessage());
			System.exit(2);
		} catch (IOException e) {
			System.err.println("StoreSetup: " + e.getMessage());
			System.exit(1);
		}
		System.exit(0);
	}

	/** Do it; see the class comment for ARGS */
	public void run(String[] args) throws IOException {
		List<Path> settingsFiles = new ArrayList<>();
		Path storeTemplate = null;
		Path queueTemplate = null;
		String queue = DEFAULT_QUEUE;
		Map<String, String> fill = new LinkedHashMap<>();
		List<String> require = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if (a.equals("--settings")) {
				settingsFiles.add(Path.of(value(args, ++i, a)));
			} else if (a.equals("--store-template")) {
				storeTemplate = Path.of(value(args, ++i, a));
			} else if (a.equals("--queue-template")) {
				queueTemplate = Path.of(value(args, ++i, a));
			} else if (a.equals("--queue")) {
				queue = value(args, ++i, a);
			} else if (a.equals("--fill")) {
				String kv = value(args, ++i, a);
				int eq = kv.indexOf('=');
				if (eq <= 0) {
					throw new IllegalArgumentException("--fill takes KEY=VALUE");
				}
				fill.put(kv.substring(0, eq), kv.substring(eq + 1));
			} else if (a.equals("--require")) {
				require.add(value(args, ++i, a));
			} else {
				throw new IllegalArgumentException("unknown option " + a);
			}
		}
		if (settingsFiles.isEmpty()) {
			throw new IllegalArgumentException("--settings is required");
		}
		Properties settings = new Properties();
		for (Path p : settingsFiles) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				settings.load(r);
			}
		}
		String root = settings.getProperty(ROOT);
		if (root == null || root.trim().isEmpty()) {
			throw new IOException("The mail root isn't set (" + ROOT + ")");
		}
		String source = settings.getProperty(FILE_SOURCE);
		String where = MaildropRoot.describe(root, source);
		FileSource store;
		try {
			store = MaildropRoot.open(root, source, MaildropRoot.connectionSettings(settings, FILE_SOURCE + "."), FILE_SOURCE);
			if (!store.exists() && !store.mkdirs()) {
				throw new IOException("Can't make it");
			}
		} catch (IOException e) {
			throw new IOException("Can't open the mail store " + where + ": " + e.getMessage(), e);
		}

		if (storeTemplate != null) {
			makeOnce(store.getChild(MailLocks.STORE_SETTINGS_FILE), storeTemplate, where + " " + MailLocks.STORE_SETTINGS_FILE);
		}
		FileSource queueDir = MaildropRoot.child(store, queue);
		String queueWhere = where + " " + queue + "/" + QUEUE_SETTINGS_FILE;
		FileSource queueFile = queueDir.getChild(QUEUE_SETTINGS_FILE);
		if (queueTemplate != null) {
			if (!queueDir.exists() && !queueDir.mkdirs()) {
				throw new IOException("Can't make the queue's folder " + where + " " + queue);
			}
			makeOnce(queueFile, queueTemplate, queueWhere);
		}
		if (queueFile.isFile()) {
			String text = read(queueFile);
			String before = text;
			for (Map.Entry<String, String> e : fill.entrySet()) {
				if (e.getValue() != null && !e.getValue().isEmpty() && !hasValue(text, e.getKey())) {
					text = fill(text, e.getKey(), e.getValue());
					out.println("set " + e.getKey() + "=" + e.getValue() + " in " + queueWhere);
				}
			}
			if (!text.equals(before)) {
				write(queueFile, text);
			}
			for (String k : require) {
				if (!hasValue(text, k)) {
					out.println("missing " + k + "= in " + queueWhere);
				}
			}
		}
	}

	private static String value(String[] args, int i, String option) {
		if (i >= args.length) {
			throw new IllegalArgumentException(option + " needs a value");
		}
		return args[i];
	}

	private void makeOnce(FileSource f, Path template, String where) throws IOException {
		if (f.exists()) {
			return;
		}
		write(f, Files.readString(template, StandardCharsets.UTF_8));
		out.println("made " + where);
	}

	/** True if TEXT has a line KEY=something */
	static boolean hasValue(String text, String key) {
		for (String line : text.split("\n", -1)) {
			String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
			if (l.startsWith(key + "=") && l.length() > key.length() + 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Set KEY=VALUE where TEXT has it empty ({@code KEY=}) or commented out ({@code #KEY=...}),
	 * the first such line; else at the end (parley-servers-setup's fill, for the local disk).
	 */
	static String fill(String text, String key, String value) {
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String l = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
			if (l.equals(key + "=") || l.startsWith("#" + key + "=")) {
				lines[i] = key + "=" + value;
				return String.join("\n", lines);
			}
		}
		return text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + key + "=" + value + "\n";
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			in.transferTo(b);
			return b.toString(StandardCharsets.UTF_8);
		}
	}

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream o = f.getOutputStream()) {
			o.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}
}
