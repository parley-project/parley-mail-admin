package us.bringardner.parley.mail.admin.users;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import us.bringardner.parley.mail.store.MailStoreAdminMXBean;
import us.bringardner.parley.mail.store.MailboxAcl;

/**
 * The mailbox ACLs (IMAP ACL, RFC 4314) of the mail users, as an administrator sees and changes
 * them: through a running mail server's {@link MailStoreAdminMXBean}, so every change takes that
 * server's locks on the store, as an IMAP client's SETACL does.
 * <p>
 * An identifier is {@value MailboxAcl#ANYONE}, a group with its domain ({@code $sales@example.com})
 * or a user of the users file (written as the file names it); a negative entry starts with
 * {@code -}. Rights are RFC 4314's {@value MailboxAcl#ALL}.
 *
 * <PRE>
 * Copyright 1998-2026 <A href="http://bringardner.us/tony">Tony Bringardner</A>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 * @since 2.0.0
 */
public final class MailboxAcls {

	/** Why nothing can be shown or changed when no mail server runs */
	public static final String NO_SERVER = "No mail server (IMAP, POP3 or SMTP) is running, and mailbox ACLs are changed through one; start one on the Services page";

	private final UserStore users;
	private final Supplier<MailStoreAdminMXBean> bean;

	public MailboxAcls(UserStore users, Supplier<MailStoreAdminMXBean> bean) {
		this.users = users;
		this.bean = bean;
	}

	private MailStoreAdminMXBean require() {
		MailStoreAdminMXBean b = bean.get();
		if (b == null) {
			throw new IllegalStateException(NO_SERVER);
		}
		return b;
	}

	private UserRecord user(String name) {
		UserRecord u = name == null ? null : users.get(name);
		if (u == null) {
			throw new IllegalArgumentException("No user " + name);
		}
		return u;
	}

	/**
	 * A user's mailboxes and their ACLs.
	 *
	 * @return {@code available} (false when no mail server runs, with {@code reason}),
	 *         {@code owner}, {@code maildrop} and {@code mailboxes}: each with {@code name} and
	 *         {@code entries} ({@code identifier}, {@code rights}, {@code negative}); a mailbox with
	 *         no entry for its owner gives the owner every right ({@code ownerDefault})
	 */
	public Map<String, Object> describe(String owner) throws IOException {
		UserRecord u = user(owner);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("owner", u.getName());
		m.put("maildrop", u.getMaildrop());
		m.put("rights", MailboxAcl.ALL);
		m.put("ownerAlways", MailboxAcl.OWNER_ALWAYS);
		MailStoreAdminMXBean b = bean.get();
		if (b == null) {
			m.put("available", false);
			m.put("reason", NO_SERVER);
			m.put("mailboxes", new ArrayList<>());
			return m;
		}
		m.put("available", true);
		List<Object> boxes = new ArrayList<>();
		for (String name : b.listMailboxes(u.getName(), u.getMaildrop())) {
			Map<String, String> acl;
			try {
				acl = b.getAcl(u.getName(), u.getMaildrop(), name);
			} catch (IOException e) {
				continue; // deleted meanwhile
			}
			Map<String, Object> box = new LinkedHashMap<>();
			box.put("name", name);
			List<Object> entries = new ArrayList<>();
			boolean ownerEntry = false;
			for (Map.Entry<String, String> e : new java.util.TreeMap<>(acl).entrySet()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				boolean negative = e.getKey().charAt(0) == MailboxAcl.NEGATIVE;
				String id = negative ? e.getKey().substring(1) : e.getKey();
				entry.put("identifier", id);
				entry.put("rights", e.getValue());
				entry.put("negative", negative);
				entries.add(entry);
				if (!negative && id.equals(u.getName())) {
					ownerEntry = true;
				}
			}
			box.put("entries", entries);
			box.put("ownerDefault", !ownerEntry);
			boxes.add(box);
		}
		m.put("mailboxes", boxes);
		return m;
	}

	/**
	 * Set an identifier's rights on one of the owner's mailboxes (SETACL with the whole set).
	 * Empty rights remove a user's or group's entry; the owner's own empty entry is kept (the owner
	 * then has only {@value MailboxAcl#OWNER_ALWAYS}).
	 *
	 * @return the identifier's rights now
	 */
	public String set(String owner, String mailbox, String identifier, boolean negative, String rights) throws IOException {
		UserRecord u = user(owner);
		String id = identifier(identifier);
		String r = MailboxAcl.canonical(rights == null ? "" : rights.trim());
		if (mailbox == null || mailbox.isEmpty()) {
			throw new IllegalArgumentException("No mailbox given");
		}
		return require().setRights(u.getName(), u.getMaildrop(), mailbox, negative ? MailboxAcl.NEGATIVE + id : id, r);
	}

	/** Remove an identifier's entry (DELETEACL). @return false if it had none */
	public boolean delete(String owner, String mailbox, String identifier, boolean negative) throws IOException {
		UserRecord u = user(owner);
		String id = identifier(identifier);
		return require().deleteRights(u.getName(), u.getMaildrop(), mailbox, negative ? MailboxAcl.NEGATIVE + id : id);
	}

	/**
	 * After a user was deleted: take the user out of every other user's mailbox ACLs and out of
	 * the share index.
	 *
	 * @return the entries removed, or -1 when no mail server runs (nothing was done)
	 */
	public int forget(String deleted) throws IOException {
		MailStoreAdminMXBean b = bean.get();
		if (b == null) {
			return -1;
		}
		Map<String, String> drops = new LinkedHashMap<>();
		for (UserRecord u : users.list()) {
			drops.put(u.getName(), u.getMaildrop());
		}
		return b.forgetUser(deleted, drops);
	}

	/**
	 * An identifier as the ACL stores it: {@value MailboxAcl#ANYONE}, {@code $group@domain} or a
	 * user of the users file, named as the file names the user.
	 */
	String identifier(String identifier) {
		String id = identifier == null ? "" : identifier.trim();
		if (id.isEmpty()) {
			throw new IllegalArgumentException("No user or group given");
		}
		if (id.charAt(0) == MailboxAcl.NEGATIVE) {
			throw new IllegalArgumentException("A negative entry is chosen with its own switch, not a -");
		}
		if (id.equalsIgnoreCase(MailboxAcl.ANYONE)) {
			return MailboxAcl.ANYONE;
		}
		if (id.charAt(0) == MailboxAcl.GROUP) {
			String g = UserStore.checkGroups(List.of(id)).get(0);
			return MailboxAcl.GROUP + g;
		}
		UserRecord u = users.get(id);
		if (u == null) {
			for (UserRecord r : users.list()) {
				if (r.getName().toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT))) {
					u = r;
					break;
				}
			}
		}
		if (u == null) {
			throw new IllegalArgumentException("No user " + id + " (a group is written $name@domain, every user anyone)");
		}
		return u.getName();
	}
}
