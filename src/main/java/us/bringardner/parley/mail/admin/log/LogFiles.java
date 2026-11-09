package us.bringardner.parley.mail.admin.log;

import java.io.IOException;
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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessStream;

/**
 * The log files the Logs page shows: this application's own, the servers' (the {@code parley.log.file}
 * setting of parley-core's logger in the settings files, with its rotated copies {@code .1},
 * {@code .2} ...) and any others the settings name. Only these files can be read through the page.
 * Each is a FileSource on the file system its writer uses.
 */
public final class LogFiles {

	/** Most of a file one read returns (its end). */
	public static final int MAX_READ = 1024 * 1024;
	/** Rotated copies looked for. */
	static final int MAX_ROTATED = 20;

	private final Supplier<List<FileSource>> sources;

	/** @param sources the log files as the settings name them now (rotated copies are found here) */
	public LogFiles(Supplier<List<FileSource>> sources) {
		this.sources = sources;
	}

	/** The files there are, each named file followed by its rotated copies (none of a file that can't be asked). */
	public List<FileSource> list() {
		Set<FileSource> ret = new LinkedHashSet<>();
		for (FileSource a : sources.get()) {
			try {
				if (a.isFile()) {
					ret.add(a);
				}
				FileSource dir = a.getParentFile();
				for (int i = 1; dir != null && i <= MAX_ROTATED; i++) {
					FileSource r = dir.getChild(a.getName() + "." + i);
					if (!r.isFile()) {
						break;
					}
					ret.add(r);
				}
			} catch (IOException e) {
				// it can't be reached now: not listed
			}
		}
		return new ArrayList<>(ret);
	}

	/** The files, for the page: path, name, size and when they last changed. */
	public List<Map<String, Object>> describe() {
		List<Map<String, Object>> ret = new ArrayList<>();
		for (FileSource f : list()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("path", f.getPath());
			m.put("name", f.getName());
			try {
				m.put("size", f.length());
				m.put("modified", f.lastModified());
			} catch (IOException e) {
				m.put("size", 0L);
				m.put("modified", 0L);
			}
			ret.add(m);
		}
		return ret;
	}

	/** One of {@link #list()}'s files, or null if the path isn't one of them. */
	public FileSource find(String path) {
		if (path == null) {
			return null;
		}
		for (FileSource f : list()) {
			if (f.getPath().equals(path)) {
				return f;
			}
		}
		return null;
	}

	/**
	 * The end of a file: at most maxBytes (up to {@link #MAX_READ}), from the start of a line,
	 * read as UTF-8.
	 */
	public static String tail(FileSource f, int maxBytes) throws IOException {
		int max = Math.max(1, Math.min(maxBytes, MAX_READ));
		try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
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
