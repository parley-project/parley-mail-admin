package us.bringardner.parley.mail.admin.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.smtp.queue.QueueHistory;

/**
 * Reads the history of the SMTP queue that parley-smtp's SMTP servers write ({@link QueueHistory}):
 * every queue event as a JSON line in one file per day, {@code smtp-YYYY-MM-DD.jsonl}. The queue
 * forgets a message when it's done, so this is where the SMTP History page looks. The servers
 * delete the old files ({@code parley.mail.smtp.queue.historyDays}). The folder is a FileSource, on
 * the mail root's file system like the servers', so it works for a store on any file system.
 */
public final class HistoryStore {

	/** Longest range a query reads. */
	public static final int MAX_DAYS = 366;

	private final Supplier<FileSource> directory;
	private final ObjectMapper json;

	/** @param directory the history's folder as it is now (null: none is known) */
	public HistoryStore(Supplier<FileSource> directory, ObjectMapper json) {
		this.directory = directory;
		this.json = json;
	}

	/** The history's folder, or null if there is none. */
	public FileSource getDirectory() {
		return directory.get();
	}

	private static FileSource file(FileSource dir, LocalDate day) throws IOException {
		return dir.getChild(QueueHistory.PREFIX + day + QueueHistory.SUFFIX);
	}

	/**
	 * Events in the day files of two days (inclusive, the servers' calendar), newest first.
	 *
	 * @param text  only events whose id, addresses, status or result contain this (any case), or null
	 * @param types only these types, or null for all
	 * @param limit at most this many
	 */
	public List<Map<String, Object>> query(LocalDate from, LocalDate to, String text, Set<String> types, int limit) throws IOException {
		List<Map<String, Object>> ret = new ArrayList<>();
		FileSource dir = getDirectory();
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
			FileSource f = file(dir, d);
			if (f == null || !f.isFile()) {
				continue;
			}
			List<Map<String, Object>> day = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
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

	/**
	 * Events from {@code from} (inclusive) to {@code until} (exclusive), newest first. The day files
	 * are named by the calendar of the servers that write them ({@link QueueHistory}), which need
	 * not be the reader's: a day either side is read too, and each event is kept by its own time.
	 *
	 * @param text   only events whose id, addresses, status or result contain this (any case), or
	 *               null; with {@code latest}, only messages with such an event
	 * @param types  only these types, or null for all
	 * @param limit  at most this many rows
	 * @param latest one row per message: its newest event (of {@code types}, when given)
	 * @param filter only events it accepts (an administrator's own mail), or null for all
	 */
	public List<Map<String, Object>> query(Instant from, Instant until, String text, Set<String> types, int limit,
			boolean latest, Predicate<Map<String, Object>> filter) throws IOException {
		List<Map<String, Object>> ret = new ArrayList<>();
		FileSource dir = getDirectory();
		if (dir == null || !dir.isDirectory()) {
			return ret;
		}
		if (until.isBefore(from)) {
			Instant t = from;
			from = until;
			until = t;
		}
		ZoneId zone = ZoneId.systemDefault();
		LocalDate last = LocalDate.ofInstant(until, zone).plusDays(1);
		LocalDate first = LocalDate.ofInstant(from, zone).minusDays(1);
		if (first.plusDays(MAX_DAYS + 2).isBefore(last)) {
			first = last.minusDays(MAX_DAYS + 2);
		}
		long fromMs = from.toEpochMilli();
		long untilMs = until.toEpochMilli();
		String t = text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT);
		// with latest: the messages that have a matching line, and those already given a row
		Set<Object> matched = new HashSet<>();
		Set<Object> shown = new HashSet<>();
		List<Map<String, Object>> candidates = new ArrayList<>();
		for (LocalDate d = last; !d.isBefore(first) && (latest || ret.size() < limit); d = d.minusDays(1)) {
			FileSource f = file(dir, d);
			if (f == null || !f.isFile()) {
				continue;
			}
			List<Map<String, Object>> day = new ArrayList<>();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(f.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isEmpty()) {
						continue;
					}
					boolean hit = t == null || line.toLowerCase(Locale.ROOT).contains(t);
					if (!hit && !latest) {
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
					long time = m.get("time") instanceof Number ? ((Number) m.get("time")).longValue() : -1;
					if (time < fromMs || time >= untilMs) {
						continue;
					}
					if (filter != null && !filter.test(m)) {
						continue;
					}
					if (hit) {
						matched.add(m.get("id"));
					}
					if (types != null && !types.contains(String.valueOf(m.get("type")))) {
						continue;
					}
					day.add(m);
				}
			}
			Collections.reverse(day);
			if (latest) {
				candidates.addAll(day);
				continue;
			}
			for (Map<String, Object> m : day) {
				if (ret.size() >= limit) {
					break;
				}
				ret.add(m);
			}
		}
		if (latest) {
			// newest first, so a message's first row is its latest event (a day's lines are in the
			// order written; the sort is stable, and the files may overlap the edges of a day)
			candidates.sort((a, b) -> Long.compare(time(b), time(a)));
			for (Map<String, Object> m : candidates) {
				if (ret.size() >= limit) {
					break;
				}
				Object id = m.get("id");
				if (matched.contains(id) && shown.add(id)) {
					ret.add(m);
				}
			}
		}
		return ret;
	}

	private static long time(Map<String, Object> m) {
		Object t = m.get("time");
		return t instanceof Number ? ((Number) t).longValue() : 0;
	}
}
