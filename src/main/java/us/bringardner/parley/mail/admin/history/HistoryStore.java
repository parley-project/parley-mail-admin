package us.bringardner.parley.mail.admin.history;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.bringardner.parley.smtp.queue.QueueHistory;

/**
 * Reads the history of the SMTP queue that parley-smtp's SMTP servers write ({@link QueueHistory}):
 * every queue event as a JSON line in one file per day, {@code smtp-YYYY-MM-DD.jsonl}. The queue
 * forgets a message when it's done, so this is where the SMTP History page looks. The servers
 * delete the old files ({@code parley.mail.smtp.queue.historyDays}).
 */
public final class HistoryStore {

	/** Longest range a query reads. */
	public static final int MAX_DAYS = 366;

	private final Supplier<File> directory;
	private final ObjectMapper json;

	/** @param directory the history's folder as it is now (null: none is known) */
	public HistoryStore(Supplier<File> directory, ObjectMapper json) {
		this.directory = directory;
		this.json = json;
	}

	/** The history's folder, or null if there is none. */
	public File getDirectory() {
		return directory.get();
	}

	private static File file(File dir, LocalDate day) {
		return new File(dir, QueueHistory.PREFIX + day + QueueHistory.SUFFIX);
	}

	/**
	 * Events between two days (inclusive), newest first.
	 *
	 * @param text  only events whose id, addresses, status or result contain this (any case), or null
	 * @param types only these types, or null for all
	 * @param limit at most this many
	 */
	public List<Map<String, Object>> query(LocalDate from, LocalDate to, String text, Set<String> types, int limit) throws IOException {
		List<Map<String, Object>> ret = new ArrayList<>();
		File dir = getDirectory();
		if (dir == null || !dir.isDirectory()) {
			return ret;
		}
		if (to.isBefore(from)) {
			LocalDate t = from;
			from = to;
			to = t;
		}
		if (from.plusDays(MAX_DAYS).isBefore(to)) {
			from = to.minusDays(MAX_DAYS);
		}
		String t = text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT);
		for (LocalDate d = to; !d.isBefore(from) && ret.size() < limit; d = d.minusDays(1)) {
			File f = file(dir, d);
			if (!f.isFile()) {
				continue;
			}
			List<Map<String, Object>> day = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isEmpty()) {
						continue;
					}
					if (t != null && !line.toLowerCase(Locale.ROOT).contains(t)) {
						continue;
					}
					Map<String, Object> m;
					try {
						@SuppressWarnings("unchecked")
						Map<String, Object> parsed = json.readValue(line, Map.class);
						m = parsed;
					} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
						continue; // a line being written, or cut short by a crash
					}
					if (types != null && !types.contains(String.valueOf(m.get("type")))) {
						continue;
					}
					day.add(m);
				}
			}
			Collections.reverse(day);
			for (Map<String, Object> m : day) {
				if (ret.size() >= limit) {
					break;
				}
				ret.add(m);
			}
		}
		return ret;
	}
}
