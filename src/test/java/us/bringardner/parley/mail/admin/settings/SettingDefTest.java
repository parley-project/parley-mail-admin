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
}
