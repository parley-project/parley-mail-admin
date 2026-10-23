# parley-mail-admin

A web application that runs the **Parley** mail suite and manages it from a browser. It runs
`parley-smtp`, `parley-imap` and `parley-pop3` in its own process (the servlet container's), all
serving one mail store with one user list, and gives you pages to:

| Page | What it does |
|---|---|
| Dashboard | Each service (SMTP, Submission, Submission over TLS, IMAP, IMAP over TLS, POP3, POP3 over TLS): state, port, TLS, uptime, open connections (with who is logged in); start, stop, restart; start or stop all. Recent warnings and errors. |
| Domains | The local domains (the suite serves several): add and remove them, see each one's users and aliases, make its DKIM key, and get the DNS records to publish (MX, SPF, DKIM, DMARC). |
| Users | Add, edit and delete users, per domain or for every domain: password, permissions (READ, WRITE, ADMIN), maildrop, and how much mail they keep. Takes effect at once. |
| Aliases | Aliases per domain or for every domain. |
| Queue | The messages waiting in the SMTP queue, each recipient's status and last result; read a message, retry it now, delete it, or retry them all. |
| History | Every message the SMTP queue took and every delivery attempt, by date, address, id or result, and the whole story of one message. |
| Logs | The suite's log as it's written, filtered by level, source (SMTP, IMAP, POP3, store, network, admin) and text; download the log file; set each source's log level. |
| Settings | Every setting: the ones every server shares (mail root, TLS key store, the users file) and each protocol's and each listener's own, with what they do and their defaults. Saving says which services need a restart and offers to do it. |

It's a Maven war project (`packaging war`, Jakarta Servlet 6.0), so it imports into Eclipse as a
Dynamic Web Project and runs on Tomcat 10.1 or later.

## Build

Needs Java 11 or later at run time; the Parley modules it uses (`parley-smtp`, `parley-imap`,
`parley-pop3`, `parley-brand` and what they bring in) must be in the local Maven repository, as for
the other Parley projects (parley-build builds them).

```
mvn verify
```

makes `target/parley-mail-admin.war` and runs the tests: `StoresTest` (the settings, users and
aliases files) and `WebAppTest`, which runs the whole application in an embedded Tomcat with the
mail servers on free ports and uses it as the pages do: it creates the first administrator, adds a
domain, a DKIM key, a user and an alias, sends mail through SMTP, finds it in the history and in
POP3, works the queue, the logs and the settings.

## Eclipse

1. **Import:** File > Import > Maven > Existing Maven Projects, and pick this folder. m2e (with its
   WTP support, part of "Eclipse IDE for Enterprise Java and Web Developers") makes it a Dynamic Web
   Project with the Dynamic Web Module 6.0 facet. If the Parley projects it uses are open in the
   workspace, it uses them directly, so changes and breakpoints in them work too.
2. **A server:** Window > Preferences > Server > Runtime Environments > Add > Apache Tomcat v10.1 (or
   v11.0), and point it at a Tomcat installation.
3. **Run or debug on Tomcat:** right-click the project > Run As (or Debug As) > Run on Server. The
   pages are at http://localhost:8080/parley-mail-admin/.
4. **Or without a server:** open `src/test/java/.../DevServer.java` and Run As (or Debug As) > Java
   Application. It starts an embedded Tomcat on port 8080 serving the project's pages directly
   (a change to a page shows on the next reload). `-Dport=`, `-DcontextPath=` and
   `-Dparley.mail.admin.home=` change the defaults.
5. **Tests:** right-click `src/test/java` > Run As > JUnit Test.

The first start makes the admin home (below) with settings for trying the suite out: the services on
high ports (SMTP 2525, Submission 2587, IMAP 2143, POP3 2110; the implicit TLS services off until
there's a key store), the domain `localhost`, and the mail in `<home>/mail`. The first page then
asks you to create the first administrator.

## Deploying

Copy `parley-mail-admin.war` into Tomcat's `webapps` (10.1 or later). The suite runs while the web
application runs: stopping or undeploying it stops the mail servers.

- **One process per mail store.** IMAP sessions share mailboxes in the JVM and POP3 holds its
  maildrop locks there, so don't also run another Parley mail server on the same mail root (unless
  `parley.mail.sharedStore` says the store is shared; see parley-mail).
- **Low ports:** on a real server set the ports to 25, 587, 465, 143, 993, 110 and 995 (Settings >
  Listeners). The JVM must be allowed to bind them (on Linux, for example, `setcap
  cap_net_bind_service=+ep` on the java binary, or systemd's `AmbientCapabilities`).
- **HTTPS for the pages:** passwords and the session cookie go to the pages, so serve them over
  HTTPS (a Tomcat HTTPS connector, or a reverse proxy) and set `<secure>true</secure>` in the
  session cookie configuration of `web.xml`. Better still, don't expose them to the internet.
- **The admin home** is the `parley.mail.admin.home` system property (in `CATALINA_OPTS`), the
  context parameter of the same name in `web.xml`, the `PARLEY_MAIL_ADMIN_HOME` environment
  variable, or `~/.parley/mail-admin`.

## The admin home

| File | What |
|---|---|
| `parley-mail.properties` | The settings. Every line becomes a system property, which is where the Parley servers read their settings; edited by the Settings page (comments and order are kept). |
| `users.txt` | The users (`parley.mail.users`), in parley-net's FileBasedAcl format; read again when it changes. |
| `aliases.txt` | The aliases (`parley.mail.smtp.aliases`). |
| `history/smtp-YYYY-MM-DD.jsonl` | The SMTP history, one JSON event per line, kept `parley.mail.admin.historyDays` (90) days. |
| `logs/parley-mail.log` | The log (rotated at `parley.mail.admin.logFileSize`, 5 old files kept). |
| `dkim/` | DKIM keys made on the Domains page, each with its DNS record. |
| `mail/` | The mail store (`parley.mail.root`) of a new home. |

## Several domains

All local domains are in `parley.mail.smtp.domains` (the Domains page). Users and aliases can belong
to one domain or to all of them:

- `tony@example.com` gets mail for tony@example.com only; its maildrop is `example.com/tony` unless
  you give another. It logs in to IMAP and POP3 as `tony@example.com`.
- `tony` (no domain) gets mail for tony@ every local domain, as with one domain.
- An alias `sales@example.com: tony, jose@example.org` is for example.com only; a member without @
  takes the alias's domain (here tony@example.com, then tony).

This uses parley-smtp's virtual domains (`LocalDelivery.find(MailAddress)`): aliases are looked up
before users, and the full address before the local part.

## How it fits together

- `AdminApp` makes everything and is usable without a servlet container; `web.AdminContextListener`
  makes it when the web application starts and closes it when it stops.
- `suite.MailSuite` runs the servers. A Parley server reads its settings when it's made, so each
  start makes a new one from the current settings; a running service whose settings changed says
  "restart". The SMTP services share one queue (made when the first one starts), so a change to an
  SMTP setting restarts all running SMTP services together.
- `settings.SettingsService` keeps the properties file and the system properties in step;
  `settings.SettingsCatalog` describes the settings the Settings page offers (any other `parley.*`
  setting can go on its Other tab).
- `users.UserStore` and `users.AliasStore` edit the files; `users.SuiteAcl` is the servers' user
  list (a `FileBasedAcl` made again when the file changes). Passwords are stored as PBKDF2 hashes
  with SCRAM-SHA-256 and SCRAM-SHA-1 credentials, so every login mechanism works.
- `log.AdminLogger` (set as `parley.core.logger`) and `log.JulCapture` (java.util.logging and
  System.Logger) give every entry to `log.LogBuffer` for the Logs page and the log file.
- `history.HistoryStore` is a parley-smtp `QueueListener` that writes the queue's events.
- `web.ApiServlet` is the JSON API under `/api` that the pages (`src/main/webapp`, plain
  JavaScript) use; `web.AdminFilter` requires a logged-in user with the ADMIN permission and a
  CSRF token for every change, and sets a strict Content-Security-Policy.

## Logging in

Administrators are mail users with the ADMIN permission (Users page). While no user has it, the
first page creates one. After 10 login attempts from one address in 15 minutes without success,
that address must wait (behind a reverse proxy every client has the proxy's address, so they share
that limit). A user who loses ADMIN, or is deleted, is logged out at their next request.

## Known limits

- Starting and stopping services is done one at a time: while a service stops (the SMTP queue
  waits up to 30 seconds for a delivery under way) or starts (up to 15 seconds), the other pages'
  requests wait for it.
- Restarting the SMTP services while a slow remote delivery is still under way can leave the old
  queue finishing that delivery while the new one starts; with `parley.mail.sharedStore` off the
  two don't lock each other out, so that message could be tried by both.

## License

Apache License 2.0.
