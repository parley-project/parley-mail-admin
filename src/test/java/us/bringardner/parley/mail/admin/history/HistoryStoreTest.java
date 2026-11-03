package us.bringardner.parley.mail.admin.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.mail.admin.log.AdminLogger;
import us.bringardner.parley.smtp.queue.QueueHistory;

/**
 * The SMTP history as the History page reads it: one row per message with its latest event, and
 * days in the viewer's time zone whatever the time zone the day files are named in.
 */
public class HistoryStoreTest {

	@TempDir
	Path tmp;
	private HistoryStore store;

	private static final Instant T = Instant.parse("2026-11-02T22:27:55Z");

	/**
	 * As AdminApp does before any Parley object logs: the Parley logger class is chosen once per
	 * JVM, and the web app tests sharing this JVM read the admin's log entries.
	 */
	@BeforeAll
	public static void adminLogger() {
		if (System.getProperty(BaseObject.PROPERTY_LOGGER) == null) {
			System.setProperty(BaseObject.PROPERTY_LOGGER, AdminLogger.class.getName());
		}
	}

	@BeforeEach
	public void setup() throws IOException {
		FileSource dir = FileSourceFactory.getDefaultFactory().createFileSource(tmp.toString());
		store = new HistoryStore(() -> dir, new ObjectMapper());
	}

	/** Appends an event to the day file the servers would write it to (this machine's calendar). */
	private void event(Instant time, String type, String id, String more) throws IOException {
		LocalDate day = LocalDate.ofInstant(time, ZoneId.systemDefault());
		File f = new File(tmp.toFile(), QueueHistory.PREFIX + day + QueueHistory.SUFFIX);
		try (FileWriter w = new FileWriter(f, true)) {
			w.write("{\"time\":" + time.toEpochMilli() + ",\"type\":\"" + type + "\",\"id\":\"" + id
					+ "\",\"from\":\"postmaster@b.test\"" + more + "}\n");
		}
	}

	private void deliveredMessage(String id, Instant t) throws IOException {
		event(t, "QUEUED", id, ",\"recipients\":[\"tony@far.test\"]");
		event(t.plusMillis(2617), "DELIVERED", id, ",\"recipient\":\"tony@far.test\",\"status\":\"2.0.0\"");
		event(t.plusMillis(2617), "COMPLETED", id, ",\"recipients\":[\"tony@far.test\"]");
	}

	private static List<String> rows(List<Map<String, Object>> r) {
		List<String> ret = new ArrayList<>();
		for (Map<String, Object> m : r) {
			ret.add(m.get("id") + " " + m.get("type"));
		}
		return ret;
	}

	private List<Map<String, Object>> day(String date, String zone, String text, String types, boolean latest) throws IOException {
		ZoneId z = ZoneId.of(zone);
		LocalDate d = LocalDate.parse(date);
		return store.query(d.atStartOfDay(z).toInstant(), d.plusDays(1).atStartOfDay(z).toInstant(), text,
				types == null ? null : new HashSet<>(Arrays.asList(types.split(","))), 1000, latest, null);
	}

	@Test
	public void oneRowPerMessageWithItsLatestEvent() throws IOException {
		deliveredMessage("A", T);
		event(T.plusSeconds(60), "QUEUED", "B", ",\"recipients\":[\"sam@far.test\"]");
		event(T.plusSeconds(61), "DEFERRED", "B", ",\"recipient\":\"sam@far.test\",\"status\":\"4.4.1\"");
		assertEquals("[B DEFERRED, A COMPLETED]", rows(day("2026-11-02", "America/New_York", null, null, true)).toString());
		// without latest, every event, newest first
		assertEquals("[B DEFERRED, B QUEUED, A COMPLETED, A DELIVERED, A QUEUED]",
				rows(day("2026-11-02", "America/New_York", null, null, false)).toString());
	}

	@Test
	public void aTypeFilterGivesTheLatestEventOfThoseTypes() throws IOException {
		deliveredMessage("A", T);
		event(T.plusSeconds(60), "QUEUED", "B", ",\"recipients\":[\"sam@far.test\"]");
		assertEquals("[A DELIVERED]", rows(day("2026-11-02", "America/New_York", null, "DELIVERED,FAILED", true)).toString());
	}

	@Test
	public void textMatchingAnyEventShowsTheMessagesLatestEvent() throws IOException {
		deliveredMessage("A", T);
		event(T.plusSeconds(60), "QUEUED", "B", ",\"recipients\":[\"sam@far.test\"]");
		// "2.0.0" is only in A's DELIVERED line
		assertEquals("[A COMPLETED]", rows(day("2026-11-02", "America/New_York", "2.0.0", null, true)).toString());
	}

	@Test
	public void daysAreTheViewersWhateverTheFilesCalendar() throws IOException {
		// 21:00 on Nov 2 in New York is Nov 3 in UTC (and on Kiritimati, UTC+14, it's Nov 3 midday)
		Instant evening = Instant.parse("2026-11-03T02:00:00Z");
		deliveredMessage("E", evening);
		assertEquals("[E COMPLETED]", rows(day("2026-11-02", "America/New_York", null, null, true)).toString());
		assertEquals("[]", rows(day("2026-11-03", "America/New_York", null, null, true)).toString());
		assertEquals("[E COMPLETED]", rows(day("2026-11-03", "UTC", null, null, true)).toString());
		assertEquals("[E COMPLETED]", rows(day("2026-11-03", "Pacific/Kiritimati", null, null, true)).toString());
		assertEquals("[E COMPLETED]", rows(day("2026-11-02", "Pacific/Pago_Pago", null, null, true)).toString());
	}

	@Test
	public void aMessageAcrossMidnightShowsItsLastEvent() throws IOException {
		Instant beforeMidnight = LocalDate.parse("2026-11-02").plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusSeconds(1);
		deliveredMessage("M", beforeMidnight); // queued in one day's file, delivered in the next
		ZoneId z = ZoneId.systemDefault();
		List<Map<String, Object>> r = store.query(LocalDate.parse("2026-11-02").atStartOfDay(z).toInstant(),
				LocalDate.parse("2026-11-04").atStartOfDay(z).toInstant(), null, null, 1000, true, null);
		assertEquals("[M COMPLETED]", rows(r).toString());
	}

	@Test
	public void theFilterKeepsOnlyAnAdministratorsMail() throws IOException {
		deliveredMessage("A", T);
		ZoneId z = ZoneId.of("UTC");
		List<Map<String, Object>> r = store.query(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-03T00:00:00Z"),
				null, null, 1000, true, m -> !"COMPLETED".equals(m.get("type")));
		assertEquals("[A DELIVERED]", rows(r).toString());
		assertEquals(0, store.query(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"), null, null, 10).size(), z.toString());
	}
}
