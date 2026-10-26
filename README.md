# parley-mail-admin

A web application that manages the **Parley** mail suite from a browser: `parley-smtp`,
`parley-imap` and `parley-pop3`, all serving one mail store with one user list. The servers are
parley-net launcher servers, managed with JMX, so they can run either

1. in this application's process (the servlet container's): the default; or
2. as services of their own (`ServerMain`, parley-net's generic launcher, run by systemd, launchd or
   a Windows service), alone or several per process, with this application managing them over their
   JMX addresses. The servers don't need it: it's a convenience.

Either way they read the same settings files, in the settings folder (below), which the pages edit.
It gives you pages to:

| Page | What it does |
|---|---|
| Dashboard | Where the servers run. Each service (SMTP, Submission, Submission over TLS, IMAP, IMAP over TLS, POP3, POP3 over TLS): state, port, TLS, uptime, open connections (with who is logged in); start, stop, restart; start or stop all. Recent warnings and errors. |
| Domains | The local domains (the suite serves several): add and remove them, see each one's users and aliases, make its DKIM key, and get the DNS records to publish (MX, SPF, DKIM, DMARC). |
| Users | Add, edit and delete users, per domain or for every domain: password, permissions (READ, WRITE, ADMIN), maildrop, and how much mail they keep. The servers read the users file again themselves: those in this application at once, services within `parley.net.acl.reloadSeconds` (15). |
| Aliases | Aliases per domain or for every domain. |
| Queue | The messages waiting in the SMTP queue, each recipient's status and last result; read a message, retry it now, delete it, or retry them all. |
| History | Every message the SMTP queue took and every delivery attempt, by date, address, id or result, and the whole story of one message (the history files the SMTP servers write). |
| Logs | This application's log as it's written (with the servers' entries when they run in it), filtered by level, source (SMTP, IMAP, POP3, store, network, admin) and text; set each source's log level; and the log files: this application's, the servers' (`logFile` in a settings file) and any others named in the settings, read from their end or downloaded. |
| Settings | Every setting, in the files the servers read: the ones every server shares (mail root, TLS key store, the users file) and each protocol's and each listener's own, with what they do and their defaults. Saving says which services need a restart and offers to do it. |

The pages look like parley-files-viewer: swing-widgets' gradient look, with the same eight themes
(Gold, the default, Dark, Light, Copper, Sage, Ocean, Slate and Plum), picked at the right of the
top bar (with who is logged in and Log out) and remembered by the browser. Their colors are the `.theme` files' values, at the top of
`src/main/webapp/css/app.css`.

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
aliases files, the history and log files, permissions kept on rewrite), `WebAppTest`, which runs
the whole application in an embedded Tomcat with the mail servers on free ports and uses it as the
pages do: it creates the first administrator, adds a domain, a DKIM key, a user and an alias, sends
mail through SMTP, finds it in the history and in POP3, works the queue, the logs and the settings;
and `RemoteModeTest`, which runs SMTP and POP3 in a launcher behind a JMX RMI connector and manages
them over its JMX address.

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
   (a change to a page shows on the next reload). `-Dport=`, `-DcontextPath=`,
   `-Dparley.mail.admin.home=` and `-Dparley.mail.admin.settingsDirectory=` change the defaults.
5. **Tests:** right-click `src/test/java` > Run As > JUnit Test.

The first start makes the admin home (below), and `parley-mail.properties` in the settings folder
unless it's there, with settings for trying the suite out: the services on high ports (SMTP 2525,
Submission 2587, IMAP 2143, POP3 2110; the implicit TLS services off until there's a key store), the
domain `localhost`, and the mail in `<home>/mail`. The first page then asks you to create the first
administrator.

## Deploying

Copy `parley-mail-admin.war` into Tomcat's `webapps` (10.1 or later). Nothing here assumes which
user or group Tomcat (or a server run as a service) runs as: files the pages rewrite keep their
permissions and group, and new ones (DKIM keys too) get the process's usual permissions, so give
the folders the group and permissions you want the servers to share.

### The servers in this application (the default)

With `parley.mail.admin.jmxUrl` empty, a parley-net `ServerLauncher` in the web application runs
every service (ids `smtp`, `submission`, `submissions`, `imap`, `imaps`, `pop3`, `pop3s`) from the
settings folder's files; stopping or undeploying the web application stops them. Their JMX beans
(`us.bringardner.parley:type=Server,name=<id>`, `type=Launcher,name=ParleyMailAdmin`,
`type=MailQueue,name=smtp`) are on the JVM's platform MBean server, so jconsole sees them too.
`parley.mail.admin.autoStart` (true) starts the services whose port isn't 0 when it starts.

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
- **The settings folder** is the `parley.mail.admin.settingsDirectory` system property or context
  parameter, else parley-net's `ServerMain.confDir()` (`/etc/parley`, Windows `%ProgramData%\Parley`,
  or the folder of the file `PARLEY_CONF` names) when it exists, else the admin home.

### The servers as services

Run the servers with parley-net's launcher (see parley-net's "Running servers: as a service, alone
or together" and parley-mail's "Running the mail suite"), with remote JMX turned on, e.g.

```
java -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.host=localhost \
     -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false \
     -cp ... us.bringardner.parley.net.server.ServerMain parley.servers smtp,submission,imap,pop3
```

and set `parley.mail.admin.jmxUrl` (Settings > Admin) to its JMX service URL,
`service:jmx:rmi:///jndi/rmi://localhost:9010/jmxrmi`; several launcher processes (SMTP in one, IMAP
and POP3 in another, say) are several URLs, comma separated. `parley.mail.admin.jmxUser` and
`parley.mail.admin.jmxPassword` are the credentials when JMX asks for them. A server no launcher
runs shows as unavailable. The application then starts nothing itself, and stopping it leaves the
services running.

- The launchers must read the settings folder this application edits (the same machine, the same
  `/etc/parley` or `PARLEY_CONF`); the Dashboard warns when a launcher reads files elsewhere.
- A start or restart from the pages makes the launcher read the settings files again first.
- Servers in separate processes on one mail store need `parley.mail.sharedStore=host`.
- Protect JMX as you would the servers' settings: anyone who reaches it can stop them.

## The settings folder

| File | What |
|---|---|
| `parley-mail.properties` | What every mail server shares (mail root, users, TLS key store, ports ...), and this application's own `parley.mail.admin.*` settings, which the servers ignore. |
| `parley-smtp.properties`, `parley-imap.properties`, `parley-pop3.properties` | Each protocol's own, read after the shared file (they win for their servers). |

The Settings page changes a setting in the file that has it, and puts a new one in its protocol's
file (`parley.mail.smtp.*`, `parley.mail.submission.*` and `parley.mail.submissions.*` in
parley-smtp.properties, and so on), anything else in the shared file; comments and order are kept.
The files are read again when they change outside the pages. A setting the JVM was given with `-D`
wins over the files, and the page says so.

## The admin home

| File | What |
|---|---|
| `users.txt` | The users (`parley.mail.users`), in parley-net's FileBasedAcl format; the servers read it again when it changes. |
| `aliases.txt` | The aliases (`parley.mail.smtp.aliases`). |
| `logs/parley-mail.log` | This application's log (rotated at `parley.mail.admin.logFileSize`, 5 old files kept). |
| `dkim/` | DKIM keys made on the Domains page, each with its DNS record. |
| `mail/` | The mail store (`parley.mail.root`) of a new home. |

The SMTP history is the SMTP servers' own: `smtp-YYYY-MM-DD.jsonl` files in `<mail root>/.smtp-history`
(`parley.mail.smtp.queue.historyDirectory`), kept `parley.mail.smtp.queue.historyDays` (30) days.

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
- `suite.MailSuite` manages the servers through JMX: parley-net's `ParleyServerMBean` (state,
  start, stop, restart, connections and who they are) and `ParleyLauncherMBean` (read the settings again), and
  parley-smtp's `MailQueueMXBean` for the Queue page; in this application's own launcher or over
  `parley.mail.admin.jmxUrl`. A running service whose settings (or, for SMTP, aliases file) changed
  since it started says "restart". The SMTP services of a process share one queue (made when the
  first one starts), so a change to an SMTP setting restarts that process's running SMTP services
  together.
- `settings.SettingsService` edits the settings files; `settings.SettingsCatalog` describes the
  settings the Settings page offers (any other `parley.*` setting can go on its Other tab).
- `users.UserStore` and `users.AliasStore` edit the files; the servers' user list is parley-mail's
  shared `FileBasedAcl` of `parley.mail.users`, which reads the file again when it changes.
  Passwords are stored as PBKDF2 hashes with SCRAM-SHA-256 and SCRAM-SHA-1 credentials, so every
  login mechanism works.
- `FileRewrite` writes a file through a temporary file and a rename, keeping the original's
  permissions and group.
- `log.AdminLogger` (set as `parley.core.logger`) and `log.JulCapture` (java.util.logging and
  System.Logger) give every entry to `log.LogBuffer` for the Logs page and the log file.
- `history.HistoryStore` reads the history files the SMTP servers write (parley-smtp's
  `QueueHistory`), in the folder the queue's bean names; `log.LogFiles` the log files.
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
  waits up to 30 seconds for a delivery under way) or starts (up to 60 seconds), the other pages'
  requests wait for it. An unreachable JMX address is tried again at most every 10 seconds, each
  try waiting up to 10 seconds.
- A service started before this application saw it is taken to need a restart if one of its
  settings files changed after it started (the application can't know which settings it read).
- Restarting the SMTP services while a slow remote delivery is still under way can leave the old
  queue finishing that delivery while the new one starts; with `parley.mail.sharedStore` off the
  two don't lock each other out, so that message could be tried by both.

## License

Apache License 2.0.
