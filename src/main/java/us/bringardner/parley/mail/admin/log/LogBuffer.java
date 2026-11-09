package us.bringardner.parley.mail.admin.log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import us.bringardner.parley.files.FileSource;

/**
 * The log entries of the mail suite: the last few thousand in memory for the Logs page, and all of
 * them in {@code logs/parley-mail.log} under the admin home (rotated by size, 5 old files kept).
 * Entries come from the Parley loggers ({@link AdminLogger}): all Parley logging goes through
 * parley-core's ILogger.
 */
public final class LogBuffer {

	/** Levels, least to most important. */
	public enum Level {
		TRACE, DEBUG, INFO, WARN, ERROR;

		public static Level parse(String s, Level defaultLevel) {
			if (s == null) {
				return defaultLevel;
			}
			try {
				return valueOf(s.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return defaultLevel;
			}
		}
	}

	/** Where an entry came from, by its logger's name. */
	public enum Source {
		SMTP, IMAP, POP3, FTP, MAIL, NET, ADMIN, OTHER;

		/** The logger name prefixes of a source (for setting its level). */
		public String[] packages() {
			switch (this) {
			case SMTP:
				return new String[] { "us.bringardner.parley.smtp" };
			case IMAP:
				return new String[] { "us.bringardner.parley.imap" };
			case POP3:
				return new String[] { "us.bringardner.parley.pop3" };
			case FTP:
				return new String[] { "us.bringardner.parley.ftp" };
			case MAIL:
				return new String[] { "us.bringardner.parley.mail.store", "us.bringardner.parley.mail.server" };
			case NET:
				return new String[] { "us.bringardner.parley.net", "us.bringardner.parley.core", "us.bringardner.parley.files",
						"us.bringardner.parley.dns", "us.bringardner.parley.io" };
			case ADMIN:
				return new String[] { "us.bringardner.parley.mail.admin" };
			default:
				return new String[0];
			}
		}

		public static Source of(String loggerName) {
			if (loggerName == null) {
				return OTHER;
			}
			if (loggerName.startsWith("us.bringardner.parley.mail.admin")) {
				return ADMIN;
			}
			for (Source s : values()) {
				if (s != ADMIN) {
					for (String p : s.packages()) {
						if (loggerName.startsWith(p)) {
							return s;
						}
					}
				}
			}
			return loggerName.startsWith("us.bringardner.parley.mail") ? MAIL : OTHER;
		}
	}

	/** One entry. Public fields, for JSON. */
	public static final class Entry {
		public final long seq;
		public final long time;
		public final Level level;
		public final Source source;
		public final String logger;
		public final String thread;
		public final String message;
		/** The stack trace, or null. */
		public final String error;

		Entry(long seq, long time, Level level, String logger, String thread, String message, String error) {
			this.seq = seq;
			this.time = time;
			this.level = level;
			this.source = Source.of(logger);
			this.logger = logger;
			this.thread = thread;
			this.message = message;
			this.error = error;
		}
	}

	private static volatile LogBuffer instance;

	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	static final int OLD_FILES = 5;

	private final AtomicLong seq = new AtomicLong();
	private final Deque<Entry> entries = new ArrayDeque<>();
	private volatile int capacity;
	private FileSource file;
	private long fileSize;
	private long maxFileSize;
	private OutputStream out;

	public LogBuffer(int capacity) {
		this.capacity = Math.max(100, capacity);
	}

	/** The buffer the loggers write to (set by the application while it runs). */
	public static LogBuffer get() {
		return instance;
	}

	public static void install(LogBuffer buffer) {
		instance = buffer;
	}

	public void setCapacity(int capacity) {
		synchronized (entries) {
			this.capacity = Math.max(100, capacity);
			while (entries.size() > this.capacity) {
				entries.removeFirst();
			}
		}
	}

	/** Also write the entries to this file, starting a new one when it reaches maxSize bytes. */
	public synchronized void setFile(FileSource file, long maxSize) throws IOException {
		closeFile();
		this.file = file;
		this.maxFileSize = Math.max(64 * 1024, maxSize);
		openFile();
	}

	public synchronized FileSource getFile() {
		return file;
	}

	private void openFile() throws IOException {
		FileSource dir = file.getParentFile();
		if (dir != null && !dir.isDirectory()) {
			dir.mkdirs();
		}
		out = file.getOutputStream(true);
		fileSize = file.exists() ? file.length() : 0;
	}

	private synchronized void closeFile() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException e) {
				// nothing to do
			}
			out = null;
		}
	}

	public void close() {
		closeFile();
	}

	public void add(Level level, String logger, String message, Throwable error) {
		String trace = null;
		if (error != null) {
			StringWriter w = new StringWriter();
			error.printStackTrace(new PrintWriter(w));
			trace = w.toString();
		}
		Entry e = new Entry(seq.incrementAndGet(), System.currentTimeMillis(), level, logger, Thread.currentThread().getName(),
				message == null ? "" : message, trace);
		synchronized (entries) {
			entries.addLast(e);
			while (entries.size() > capacity) {
				entries.removeFirst();
			}
		}
		write(e);
	}

	private synchronized void write(Entry e) {
		if (out == null) {
			return;
		}
		StringBuilder b = new StringBuilder(128);
		b.append(FILE_TIME.format(Instant.ofEpochMilli(e.time))).append(' ').append(e.level).append(' ').append(e.source).append(" [")
				.append(e.thread).append("] ").append(e.logger).append(" - ").append(e.message).append('\n');
		if (e.error != null) {
			b.append(e.error);
		}
		byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
		try {
			if (fileSize + bytes.length > maxFileSize && fileSize > 0) {
				rotate();
			}
			out.write(bytes);
			out.flush();
			fileSize += bytes.length;
		} catch (IOException ex) {
			// the in-memory entries still work; don't log (that would come back here)
		}
	}

	private void rotate() throws IOException {
		closeFile();
		for (int i = OLD_FILES - 1; i >= 1; i--) {
			FileSource from = rotated(i);
			if (from.exists()) {
				FileSource to = rotated(i + 1);
				to.delete();
				from.renameTo(to);
			}
		}
		FileSource first = rotated(1);
		first.delete();
		file.renameTo(first);
		openFile();
	}

	/** The rotated copy number I: the file's name followed by .I, next to it */
	private FileSource rotated(int i) throws IOException {
		return file.getParentFile().getChild(file.getName() + "." + i);
	}

	/**
	 * Entries after a sequence number, oldest first.
	 *
	 * @param after    only entries with a larger seq (0 for all)
	 * @param minLevel the least important level to include
	 * @param source   only this source, or null
	 * @param text     only entries whose message or logger contains this (any case), or null
	 * @param limit    at most this many (the newest)
	 */
	public List<Entry> query(long after, Level minLevel, Source source, String text, int limit) {
		String t = text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT);
		List<Entry> ret = new ArrayList<>();
		synchronized (entries) {
			java.util.Iterator<Entry> it = entries.descendingIterator();
			while (it.hasNext() && ret.size() < limit) {
				Entry e = it.next();
				if (e.seq <= after) {
					break;
				}
				if (e.level.ordinal() < minLevel.ordinal() || (source != null && e.source != source)) {
					continue;
				}
				if (t != null && !e.message.toLowerCase(Locale.ROOT).contains(t) && !e.logger.toLowerCase(Locale.ROOT).contains(t)
						&& (e.error == null || !e.error.toLowerCase(Locale.ROOT).contains(t))) {
					continue;
				}
				ret.add(e);
			}
		}
		Collections.reverse(ret);
		return ret;
	}

	/** The newest sequence number. */
	public long lastSeq() {
		return seq.get();
	}

	/** Remove the entries in memory (the file is kept). */
	public void clear() {
		synchronized (entries) {
			entries.clear();
		}
	}
}
