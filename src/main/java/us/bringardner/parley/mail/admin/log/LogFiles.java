package us.bringardner.parley.mail.admin.log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The log files the Logs page shows: this application's own, the servers' (the {@code parley.log.file}
 * setting of parley-core's logger in the settings files, with its rotated copies {@code .1},
 * {@code .2} ...) and any others the settings name. Only these files can be read through the page.
 */
public final class LogFiles {

	/** Most of a file one read returns (its end). */
	public static final int MAX_READ = 1024 * 1024;
	/** Rotated copies looked for. */
	static final int MAX_ROTATED = 20;

	private final Supplier<List<File>> sources;

	/** @param sources the log files as the settings name them now (rotated copies are found here) */
	public LogFiles(Supplier<List<File>> sources) {
		this.sources = sources;
	}

	/** The files there are, each named file followed by its rotated copies. */
	public List<File> list() {
		Set<File> ret = new LinkedHashSet<>();
		for (File f : sources.get()) {
			File a = f.getAbsoluteFile();
			if (a.isFile()) {
				ret.add(a);
			}
			for (int i = 1; i <= MAX_ROTATED; i++) {
				File r = new File(a.getPath() + "." + i);
				if (!r.isFile()) {
					break;
				}
				ret.add(r);
			}
		}
		return new ArrayList<>(ret);
	}

	/** The files, for the page: path, name, size and when they last changed. */
	public List<Map<String, Object>> describe() {
		List<Map<String, Object>> ret = new ArrayList<>();
		for (File f : list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("path", f.getPath());
			m.put("name", f.getName());
			m.put("size", f.length());
			m.put("modified", f.lastModified());
			ret.add(m);
		}
		return ret;
	}

	/** One of {@link #list()}'s files, or null if the path isn't one of them. */
	public File find(String path) {
		if (path == null) {
			return null;
		}
		File want = new File(path).getAbsoluteFile();
		for (File f : list()) {
			if (f.equals(want)) {
				return f;
			}
		}
		return null;
	}

	/**
	 * The end of a file: at most maxBytes (up to {@link #MAX_READ}), from the start of a line,
	 * read as UTF-8.
	 */
	public static String tail(File f, int maxBytes) throws IOException {
		int max = Math.max(1, Math.min(maxBytes, MAX_READ));
		try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
			long len = r.length();
			long start = Math.max(0, len - max);
			byte[] b = new byte[(int) (len - start)];
			r.seek(start);
			r.readFully(b);
			int from = 0;
			if (start > 0) {
				while (from < b.length && b[from] != '\n') {
					from++;
				}
				from = Math.min(b.length, from + 1);
			}
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
					.onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(b, from, b.length - from)).toString();
		}
	}
}
