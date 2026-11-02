package us.bringardner.parley.mail.admin.settings;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class SettingDefTest {

	private static SettingDef port() {
		return new SettingDef("parley.mail.pop3.port", "listeners", "POP3: port", SettingDef.Type.PORT, "110", "Its port.");
	}

	@Test
	public void portZeroIsRefused() {
		String message = port().validate("0");
		assertNotNull(message, "0 no longer turns a service off");
		assertTrue(message.contains("Turn off"), message);
	}

	@Test
	public void portRange() {
		SettingDef p = port();
		assertNull(p.validate("1"));
		assertNull(p.validate("110"));
		assertNull(p.validate("65535"));
		assertNull(p.validate(""), "empty uses the default");
		assertNotNull(p.validate("65536"));
		assertNotNull(p.validate("-1"));
		assertNotNull(p.validate("1.5"));
		assertNotNull(p.validate("abc"));
	}

	@Test
	public void numbersMayStillBeZero() {
		SettingDef n = new SettingDef("parley.mail.pop3.maxConnections", "listeners", "POP3: most connections", SettingDef.Type.NUMBER, "0", "No limit.");
		assertNull(n.validate("0"));
	}

	@Test
	public void durationsCarryTheirUnit() {
		SettingDef d = new SettingDef("queue.maxAge", "queue", "Give up after", SettingDef.Type.DURATION, "5d", "Returned after this long.");
		assertNull(d.validate("5d"));
		assertNull(d.validate("90m"));
		assertNull(d.validate("250ms"));
		assertNull(d.validate("120"), "a number without a unit is ms");
		assertNull(d.validate(""), "empty uses the default");
		assertNotNull(d.validate("5 days"));
		assertNotNull(d.validate("1.5h"));
		assertNotNull(d.validate("1m,5m"), "one duration, not a list");
	}

	@Test
	public void aListOfDurations() {
		SettingDef d = new SettingDef("queue.retry", "queue", "Retry schedule", SettingDef.Type.DURATIONS, "1m,5m", "The waits.");
		assertNull(d.validate("1m,5m,15m,30m,1h,2h"));
		assertNull(d.validate("30s, 5m"));
		assertNotNull(d.validate("1m,soon"));
	}

	@Test
	public void theCatalogHasTwoPointZerosNames() {
		SettingsCatalog c = new SettingsCatalog();
		for (String k : new String[] { "queue.smartHost", "queue.smartHost.port", "queue.smartHost.user", "queue.smartHost.password",
				"queue.smartHost.auth", "queue.smartHost.tls", "queue.maxAge", "queue.delayWarning", "queue.dmarc.reportInterval",
				"parley.net.acl.reloadInterval", "parley.net.acl.hashWait", "parley.mail.smtp.spam.timeout" }) {
			assertNotNull(c.get(k), k);
		}
		for (String k : new String[] { "queue.relayHost", "queue.relayUser", "queue.relayPassword", "queue.relayAuth", "queue.relayTls",
				"queue.maxAgeHours", "queue.delayWarningHours", "queue.dmarc.reportIntervalHours", "parley.net.acl.reloadSeconds",
				"parley.net.acl.hashWaitMs", "parley.mail.smtp.spam.timeoutMillis" }) {
			assertNull(c.get(k), k);
		}
		org.junit.jupiter.api.Assertions.assertEquals(SettingDef.Type.DURATIONS, c.get("queue.retry").getType());
		org.junit.jupiter.api.Assertions.assertEquals(SettingDef.Type.DURATION, c.get("parley.mail.imap.autologout").getType());
	}
}
