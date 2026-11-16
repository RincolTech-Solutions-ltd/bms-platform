# ADR-129: Backups are encrypted to an age public key whose private key stays offline

## Status

Proposed (issue #46 item 3, owner decision D121 of 2 November 2026). Amends ADR-058 decision 4 (the weekly restore drill) and
leaves its other decisions as they are. No migration. The requirements are NFR-BAK-01 to NFR-BAK-04 (chapter 4); the design is
chapter 9 section 9.10; the runbooks are `docs/runbooks/restore-from-backup.md` (sections 2, 5 and 8) and
`docs/runbooks/monitoring-and-alerts.md`. Renumbered at merge if needed.

**Amended 16 November 2026** (issue #949, owner constraint of that day): **the operator's Mac needs no Docker, and no Docker
engine runs on it.** The monthly drill (decision 4) still decrypts on the Mac with the Keychain key, and now restores on the
host over ssh (decision 7). Decision 6 and the consequences are amended to match.

## Context

`deploy/backup.sh` encrypted each nightly dump with `openssl enc -aes-256-cbc -pbkdf2` and `BACKUP_ENCRYPTION_KEY`, a passphrase in
`/opt/bms/.env`. The key that reads every backup sat on the host next to the database it protects and next to the R2 token that
reads the bucket: whoever took the host, or a copy of its `.env`, could read every backup of the last year as well as the live
data. ADR-058 decision 5 stops the host from deleting its backups; nothing stopped a stolen host key from reading them.

The owner decided (D121): backups move to a public key. The server encrypts with the public key only. The private key is made on
the operator's Mac and kept in two places, the Mac Keychain (with an iCloud Keychain copy) and a printed copy in a sealed envelope
kept by the owner. It never sits on a server. A monthly restore drill decrypts with the private key, so a lost or wrong key is
found within a month.

That last point changes ADR-058 decision 4: the weekly drill on the host restored the newest backup, which it can no longer
decrypt.

## Decision

1. **The host encrypts to an age recipient.** `backup.sh` pipes `pg_dump` straight into `age --encrypt --recipient
   "$BACKUP_AGE_RECIPIENT"` (an X25519 recipient, `age1` and 58 more characters). The unencrypted dump is never written to disk.
   The script checks the recipient's form and that `age` is installed before it dumps anything; when either is missing it fails
   loudly, writes `state/last_backup_error` (which `monitor.sh` reports at once) and uploads nothing. It never reads
   `BACKUP_ENCRYPTION_KEY`, so there is no path back to the symmetric key, silent or otherwise. Before upload it checks that the
   file it made is an age file to one X25519 recipient, with the one function `age_header_problem` that the weekly host check
   uses too (grease stanzas allowed, no passphrase, no other recipient, read to the header MAC line). Object names do not change (`bms-<environment>-<stamp>.dump.enc`); the
   format is told by the file's first bytes, so retention, the lock rules, `monitor.sh` and the drill's choice of the newest
   backup are untouched. `age` is a Debian and Ubuntu package (`apt install age`); cloud-init installs it on new hosts.
2. **The private key is made and kept offline.** The key ceremony is in `restore-from-backup.md` section 8: `age-keygen` on the
   operator's Mac, the secret line stored in the login Keychain (item `bms-backup-age-identity`, which the drill script reads with
   `security`) and in the iCloud Keychain through the Passwords app, one paper copy (written by hand by preference, printed
   only as a fallback) checked by reading it back and sealed with the owner, and only the public key in `/opt/bms/.env`. One key pair serves staging and production. The secret is never in
   the repository, CI, a chat, an email or any server. Test keys in the repository are fabricated and say so.
3. **Old symmetric backups stay readable until they age out.** The format is detected from the header: `age-encryption.org/v1`
   is decrypted with the Keychain identity, `Salted__` (openssl) with `BACKUP_ENCRYPTION_KEY`. That key leaves the host's `.env`
   once the first age backup has been checked, and stays in the offline store and the operator's Keychain (item
   `bms-backup-symmetric-key`) until the last symmetric monthly copy is gone, 365 days after the switch (30 days for the daily
   copies); then it is destroyed and the date recorded in section 8 of the runbook.
4. **The drill is split honestly between what the host can check and what only the key holder can prove.**
   - **Weekly, on the host** (`restore-drill.sh` with no argument, the same `bms-restore-drill.timer`, Sunday 03:30). When the
     newest backup is an age file, the host cannot decrypt it, so it runs a backup check instead: the newest object exists, is at
     most 26 hours old, is a well formed age file (`age_header_problem`, as in decision 1), is not smaller than
     `BACKUP_CHECK_MIN_BYTES` (32 KiB) and not under half the size the previous check saw. It fails when
     `BACKUP_AGE_RECIPIENT` is not set or the stack's postgres cannot be reached through `docker compose`. It records
     `state/last_backup_check` and uploads the line to `<environment>/drill/last_backup_check.txt` with the SHA-256
     fingerprint of the host's `BACKUP_AGE_RECIPIENT` and the live Flyway version. A failed check goes to
     `state/last_backup_check_error` and `drill/last_backup_check_error.txt`, never over the last good check, so its
     fingerprint stays for the offline drill to compare. While the newest backup is still
     symmetric (the first night before the switch, or a host not switched yet) the full restore drill runs as before, line for
     line.
   - **Monthly, on the operator's Mac with the private key** (`deploy/restore-drill-offline.sh`). It downloads the newest
     backup (or one named with `--file`) and detects its format. For an age file it compares the fingerprint of the Keychain
     key's public half with the one in the host's last good check, and fails if they differ or there is none. It then
     streams the decryption with the Keychain key straight into `pg_restore` in a throwaway PostgreSQL container with no
     network, so the decrypted dump is never in a file (since the amendment of 16 November 2026 that container runs on the
     host, decision 7), and checks the Flyway version (not ahead of the checkout's newest
     migration), the row counts and that `bms_app` reads, under row-level security, at least one of a business's users and as
     many as the owner role counts. It records
     the result in `~/bms-restore-drills/history.log` and, for a drill of the newest daily backup only, uploads it to
     `<environment>/drill/last_offline_drill.txt`; a drill of a named file or a monthly copy stays on the Mac.
   - **`monitor.sh`** alerts when the last weekly drill or backup check failed, when neither succeeded in 9 days, when the last
     offline drill failed, and when no full restore (on the host or offline) succeeded in 35 days. It reads the offline result
     from R2 every 6 hours. Once the host checks age backups it counts an offline OK as the full restore only when it restored
     the then newest daily age backup (the backup's time at most 26 hours before the drill) with the Keychain key matched to
     the host's fingerprint (`recipient=match`), so restoring an old-format, monthly or older daily copy never stands in for
     a proof of the current key; and a host check without a recipient fingerprint is not a good check.
   An age file does not name its recipient (that is part of age's design), so the host check cannot prove that a backup was
   encrypted to the Keychain key. The monthly drill proves it by decrypting, and the fingerprint comparison catches a host
   whose `.env` recipient was changed after that.
5. **The import rehearsal refuses an age backup** (`restore-drill.sh --rehearse-import`, ADR-027 decision 9), with exit 3 and a
   message: it restores last night's backup on the host, which needs the private key there. Whether a rehearsal should restore a
   fresh `pg_dump` of the live database instead is pending an owner decision on issue #46; until then a rehearsal runs only
   while the newest backup is still symmetric.
6. **A restore that matters decrypts on the Mac and streams.** For a real restore, or the quarterly drill onto a fresh VM, the
   dump is decrypted on the operator's Mac and piped over SSH straight into `pg_restore` on the target host, so the private key
   never reaches a server and the plaintext never lands on a disk (`restore-from-backup.md` section 2). The Mac needs no
   Docker for this either.
7. **The monthly drill restores on the host, not on the Mac** (amended 16 November 2026, issue #949). No Docker engine runs
   on the operator's Mac. `restore-drill-offline.sh --remote <ssh target>`, the default in `restore-from-backup.md`
   section 8.5, runs `age --decrypt` on the Mac with the Keychain key and pipes the plaintext dump over ssh into
   `pg_restore` in a throwaway PostgreSQL that it starts through the host's docker (a configurable command prefix, by
   default `sudo -n -u bms docker`). The container has no network, a memory cap with no swap (256 MB for the PostgreSQL
   server, 96 MB for the restore that runs in the same cgroup, `pg_restore` itself and the server's
   `maintenance_work_mem` pinned at 64 MB (issue #951), plus the data: 640 MB inside `bms.slice` on staging, as the weekly host drill is kept in
   it; 1280 MB on production, which has no slice; `BMS_DRILL_MEMORY_MB`), its data directory on a tmpfs inside that cap
   (the cap less 352 MB, WAL included, kept small with `max_wal_size=64MB`; autovacuum off, so no worker takes memory
   beyond that budget), no log driver and `--oom-score-adj 1000` (on a host short of memory the kernel kills it before
   the live stack), is created by the host's own `/opt/bms/postgres/initdb` roles script, and is removed on every
   exit path: the Mac's exit trap removes it over ssh, and its label `bms.offline-drill.expires` (6 hours after the start)
   lets the host's `monitor.sh` remove it if the Mac dies mid-run. The key never crosses ssh; only the decrypted stream
   does, to a host that already holds the same live data. The decrypted dump is never written to a file on the Mac or the
   host, and a `pg_restore` error (which can quote rows) is held in the Mac's memory and shown on its screen only. The
   restored rows live only in the container's memory (the tmpfs, no swap), and a server error that quotes a row is never
   written to the engine's log file: nothing restored reaches a disk on the host. Every check of decision 4 is unchanged,
   and so is the uploaded result line, so `monitor.sh` counts it as before; where the drill restored is noted in the
   Mac's `history.log` only. Every value that reaches the host's shell or docker's options (the prefix, the initdb path,
   the image, the memory) is validated, and each word is single-quoted for a POSIX shell. A remote drill refuses a host
   that runs another drill's or a rehearsal's database, or that has less than the container's memory and 256 MB more
   available (`MemAvailable`, read through the same docker), and the weekly drill (before it downloads anything, and when
   it cannot list the containers) and a rehearsal refuse while one is there. On staging it holds the puller's
   `pull.lock` and `deploy.sh`'s `deploy.lock` for its whole run (a small container of the same image takes them with
   `flock` on the host's state folder and carries the same expiry label, so the exit trap or `monitor.sh` releases them),
   so no deploy runs beside it in the slice, and the puller, finding its lock held, says why once (issue #951). It also
   refuses unless `bms.slice` has the whole cap free below its `MemoryHigh`, asked once the locks are held and again
   just before the database starts, and unless the slice's own oom count is readable; a container the slice's limit
   killed is reported as that, never as a cap to raise. A failed restore asks the tmpfs how full it is and whether the
   server stopped by itself, so a WAL that ran out of room (a server panic the client sees only as a lost connection)
   reads as "did not fit". Production takes no lock: it has no slice, and a production deploy is not held back for hours.
   `--local` keeps the old mode for a Mac that has an engine, with
   the same tmpfs and no log.

## Consequences

- A stolen host or `.env` no longer reads any backup made after the switch. Backups made before it stay readable with
  `BACKUP_ENCRYPTION_KEY` until they age out, which is why that key leaves the host on the day of the switch.
- The weekly host result proves the backup exists, is fresh and is well formed, not that it restores. A key that is lost or
  wrong is caught by the monthly offline drill, so up to a month of backups could be unreadable before anyone knows; the owner
  accepted that window in D121. The 35 day alert keeps a missed month from going unnoticed.
- A restore needs the operator, or the owner with the sealed copy, and a Mac with age and ssh access to the target host; the
  Mac needs no Docker (amended 16 November 2026). That adds minutes to the two hour RTO, not hours; the quarterly drill
  measures it.
- The remote drill puts the restored rows, for the length of the drill, in a container's memory on the host whose live
  database holds the same rows, and its checks are answered by that host. It adds no data the host did not hold, and none
  of it on disk. The whole restore must fit in the container's tmpfs beside about 96 MB of WAL: about 192 MB of data on
  staging, about 832 MB on production by default (issue #951 counts the restore's own memory beside the server),
  raised with `BMS_DRILL_MEMORY_MB` as the data grows; a restore that does not fit fails the drill plainly, it never falls back to a disk volume. A compromised host could answer the checks
  falsely, as the last consequence below already allows for the records; the decryption, and so the proof of the key,
  stays on the Mac.
- With the default prefix, sudo writes each command line to the host's auth log, so the `docker run` line with the
  throwaway container's random PostgreSQL passwords is on the host's disk. They open only that container, which has no
  network and is gone when the drill ends; no live password or key is ever on that line.
- Losing both the Keychain copies and the envelope loses every backup made since the switch. The envelope is kept apart from the
  Mac, and the ceremony checks the paper copy by reading it back.
- Import rehearsals stop once the newest backup is an age file, until the owner decides decision 5.
- The rollout is by hand and outside this change, and its order matters: the key ceremony, `age` and `BACKUP_AGE_RECIPIENT`
  on every host **before** the release reaches any host (the staging puller deploys within minutes of the merge, and the new
  `backup.sh` fails without them; the old one ignores the recipient). Then on each host `backup.sh` and the check by hand,
  the offline drill, and only then `BACKUP_ENCRYPTION_KEY` removed from the host's `.env` (`restore-from-backup.md`
  section 8.4).
- The paper copy is written by hand by preference: a print job is a file, which CUPS keeps on disk after printing, and a
  removed file's blocks can survive on the SSD (FileVault is what protects them). When it is printed anyway, the ceremony
  uses a USB printer, purges the job and checks the spool as root (`restore-from-backup.md` section 8.2 step 4).
- **The host can forge the records that guard it.** The host's R2 token can write `drill/last_backup_check.txt` and
  `drill/last_offline_drill.txt`, and `monitor.sh` runs on the host. A compromised host could therefore write a matching
  fingerprint and a fresh offline OK and keep the mismatch and staleness alerts quiet. This decision does not defend against
  that: the offline drill itself, run by the operator, still fails on a backup the Keychain key cannot open, and the
  operator sees that on the Mac whatever the host reports. A record the host cannot write (a separate R2 token for `drill/`
  held only by the Mac, and a check run off the host, which is #46 item 1) would close it.
