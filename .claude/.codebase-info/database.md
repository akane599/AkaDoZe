# Persistent State

*Last Updated: 2026-10-04*

There's no ORM and no Room. The app keeps four private stores, all through platform APIs.

| Store | File | Owner | Contents |
|-------|------|-------|----------|
| Default prefs | `com.akylas.enforcedoze_preferences.xml` | `access/Prefs.kt` (new keys), `res/xml/prefs.xml` | Settings, tunables, `executionMode`, `serviceEnabled`, `serviceUserEnabled` (schedule user intent, default true), timing / charging / feature toggles, `customDozePeriods`, `dozeAppBlockList` / `notificationBlockList`, legacy `dozeUsageDataAdvanced` stats |
| Restore ledger | `doze_ledger.xml` | `service/SharedPrefsLedgerStore.kt`, codec in `doze/RestoreLedger.kt` | Keys `restoreLedger`, `restoreLedgerCorruptLines`, `restoreLedgerAnnouncedCorruptLines` |
| Notice state | `notices.xml` | `ui/NoticeSink.java` | `externalBasicRejectedShown`, `externalPrivilegedRejectedShown`, `debtNotified` (StringSet of detail+target) |
| Journal | `doze_journal.db` (SQLite v1) | `monitor/JournalDb.kt` | `events` table |

## Preferences

`access/Prefs.kt` holds the new keys and defaults:
- `keepDozeEnforced` = true
- `allowExternalBasicControl` = true
- `allowExternalPrivilegedControl` = false
- `screenOnSummary` = false

`Utils.repairPreferencesPermissions` runs off-main once per process and sets existing pref XML files and their backups to
owner-only.

## Restore ledger

Each entry is one line: `1|FEATURE|target|original|elapsed|attempts|debt|api`.
- `~` encodes null.
- `%`, `|`, LF, CR and `~` are percent-escaped.
- Legacy 7-field lines are still accepted.

Rules:
- Write path: the entry is saved with a synchronous `commit()` **before** the mutation runs. A failed commit means no
  mutation, and only committed intent is cached.
- Corruption: bad lines are kept in `restoreLedgerCorruptLines` and reported separately; an unreadable ledger is never
  overwritten. FORCE_DOZE / MOTION_SENSORS lines are cleared after a verified recovery; the rest stay until the user
  dismisses them (`clearRetainedCorruption`). `restoreLedgerAnnouncedCorruptLines` stops the debt notice repeating.
- Failure accounting: failed restores stay with `attempts` and `debt` set; see architecture.md for when debt is announced.

## Journal schema (`doze_journal.db`, version 1)

```
events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  bootId INTEGER NOT NULL, elapsedRealtime INTEGER NOT NULL, wallTime INTEGER NOT NULL, sessionId INTEGER NOT NULL,
  source TEXT NOT NULL,
  type TEXT, historyKind TEXT, deep TEXT, light TEXT, sensor TEXT,
  battery INTEGER, charging INTEGER, detail TEXT,
  historyTruncated INTEGER NOT NULL DEFAULT 0)
INDEX events_session(sessionId), events_wall(wallTime)
```

- Failures: `insert(event, onFailure)` reports a failed write on the journal thread and still fails the Future.
- Retention: each write transaction prunes rows older than 14 days and keeps at most 20,000 rows (`JournalDb.MAX_ROWS`,
  `RETENTION_MS`).
- Migrations: none, since there's no predecessor. An unsupported upgrade throws.
- Threading: all access goes through the `doze-journal` thread, which returns Futures. Monitor reads use their own
  `monitor-reads` / `monitor-journal` threads.

## Backup and sharing

- `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` (cloud and device transfer) exclude
  `doze_journal.db`, `doze_ledger.xml` and `notices.xml`. Other prefs keep ordinary backup.
- Reports: `monitor/ReportExporter.kt` writes UTF-8 text to `cache/reports/`, the only path in `res/xml/file_paths.xml`.
  It shares through `ACTION_SEND` with a temporary read grant.
