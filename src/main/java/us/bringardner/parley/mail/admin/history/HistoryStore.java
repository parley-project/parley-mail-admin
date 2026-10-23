package us.bringardner.parley.mail.admin.history;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.bringardner.parley.smtp.queue.QueueEvent;
import us.bringardner.parley.smtp.queue.QueueListener;

/**
 * The history of the SMTP queue: every {@link QueueEvent} as a JSON line in one file per day,
 * {@code history/smtp-YYYY-MM-DD.jsonl} under the admin home. The queue itself forgets a message
 * when it's done, so this is where the SMTP History page looks. Files older than the retention
 * are deleted.
 */
public final class HistoryStore implements QueueListener {

	private static final System.Logger LOG = System.getLogger(HistoryStore.class.getName());
	private static final String PREFIX = "smtp-";
	private static final String SUFFIX = ".jsonl";
	/** Longest range a query reads. */
	public static final int MAX_DAYS = 366;

	private final File dir;
	private final ObjectMapper json;
	private final ZoneId zone = ZoneId.systemDefault();
	private LocalDate openDay;
	private OutputStream out;

	public HistoryStore(File dir, ObjectMapper json) {
		this.dir = dir;
		this.json = json;
	}

	public File getDirectory() {
		return dir;
	}

	/** One event as a map (also the JSON written). */
	static Map<String, Object> toMap(QueueEvent e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("time", e.getTime());
		m.put("type", e.getType().name());
		m.put("id", e.getId());
		m.put("from", e.getFrom());
		if (e.getRecipient() != null) {
			m.put("recipient", e.getRecipient());
			m.put("status", e.getStatus());
			m.put("result", e.getResult());
			if (e.getRemoteMta() != null) {
				m.put("remoteMta", e.getRemoteMta());
			}
			m.put("attempts", e.getAttempts());
			if (e.getNextAttempt() > 0) {
				m.put("nextAttempt", e.getNextAttempt());
			}
		} else {
			m.put("recipients", e.getRecipients());
		}
		m.put("size", e.getSize());
		if (e.getSubmitter() != null) {
			m.put("submitter", e.getSubmitter());
		}
		m.put("inbound", e.isInbound());
		return m;
	}

	@Override
	public void queueEvent(QueueEvent event) {
		try {
			append(toMap(event));
		} catch (IOException | RuntimeException e) {
			LOG.log(System.Logger.Level.WARNING, "Can't record " + event + " in the SMTP history", e);
		}
	}

	synchronized void append(Map<String, Object> record) throws IOException {
		LocalDate day = Instant.ofEpochMilli(((Number) record.get("time")).longValue()).atZone(zone).toLocalDate();
		if (out == null || !day.equals(openDay)) {
			close();
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("Can't create " + dir);
			}
			out = new FileOutputStream(file(day), true);
			openDay = day;
		}
		byte[] line = (json.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
		out.write(line);
		out.flush();
	}

	private File file(LocalDate day) {
		return new File(dir, PREFIX + day + SUFFIX);
	}

	public synchronized void close() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException e) {
				// nothing to do
			}
			out = null;
		}
	}

	/**
	 * Events between two days (inclusive), newest first.
	 *
	 * @param text  only events whose id, addresses, status or result contain this (any case), or null
	 * @param types only these types, or null for all
	 * @param limit at most this many
	 */
	public List<Map<String, Object>> query(LocalDate from, LocalDate to, String text, Set<String> types, int limit) throws IOException {
		if (to.isBefore(from)) {
			LocalDate t = from;
			from = to;
			to = t;
		}
		if (from.plusDays(MAX_DAYS).isBefore(to)) {
			from = to.minusDays(MAX_DAYS);
		}
		String t = text == null || text.trim().isEmpty() ? null : text.trim().toLowerCase(Locale.ROOT);
		List<Map<String, Object>> ret = new ArrayList<>();
		synchronized (this) {
			if (out != null) {
				out.flush();
			}
		}
		for (LocalDate d = to; !d.isBefore(from) && ret.size() < limit; d = d.minusDays(1)) {
			File f = file(d);
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
						continue; // a line cut short by a crash
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

	/** Delete the files of days before today minus keepDays. */
	public int purge(int keepDays) {
		if (keepDays <= 0) {
			return 0;
		}
		String oldest = PREFIX + LocalDate.now(zone).minusDays(keepDays) + SUFFIX;
		int n = 0;
		File[] files = dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
		if (files != null) {
			for (File f : files) {
				if (f.getName().compareTo(oldest) < 0 && f.delete()) {
					n++;
				}
			}
		}
		return n;
	}
}
