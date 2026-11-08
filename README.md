# ClusterControl

Android app to drive a SLURM job chain on a university GPU cluster from a phone: tick the chain, manage the
queue (drag and drop), watch the active job, keep the cluster scripts up to date. Personal tool, built for the
[neuro_symbolic_t2g](https://github.com/GiuseppeBellamacina/neuro_symbolic_t2g) pipeline, whose
`cluster/cluster_helper.sh` and `cluster/presets.yaml` it uses.

The cluster accepts ssh only from the university network. The app reaches it through the university SSTP VPN,
implemented in userspace: the tunnel exists only while the app talks to the cluster and carries nothing else, so
the rest of the phone never goes through the VPN.

## Screenshots

<p>
  <img src="docs/screenshots/dashboard.png" width="23%" alt="Dashboard">
  <img src="docs/screenshots/queue.png" width="23%" alt="Queue">
  <img src="docs/screenshots/log.png" width="23%" alt="Log">
  <img src="docs/screenshots/automations.png" width="23%" alt="Automations">
</p>

Rendered on the JVM with sample data (Paparazzi), not taken from a device.

## Features

- **Dashboard**: active job with big completion %, live ETA, loss/reward charts, countdown to the next tick, last tick result.
- **Queue**: drag and drop reorder, swipe to delete with undo, add jobs or presets from a sheet, pause, kill.
- **Log**: raw log of the active job.
- **Automations**: tick or script sync on an interval (WorkManager, 15 min minimum), also with the app closed. Exclude the
  app from battery optimization when asked, otherwise Android may skip them. Only a real credential rejection suspends
  the automations; timeouts and network errors just wait for the next slot.
- **Credentials**: Altro > Credenziali stores the VPN user/password and the ssh user/key (typed, pasted or picked from a
  file) encrypted on the phone; only the fields you fill in change. The public release comes without any.
- **Updates**: Altro > Aggiornamenti checks the latest GitHub release, downloads the apk (checksum verified) and hands it
  to the system installer.
- **Notifications**: a failed scheduled tick or sync, each job that ends (done or failed), and when every job is finished.
- **Sync**: keeps the cluster identical to the GitHub repo for what it needs to run (the `.py` files of `src/`, the `.sh`
  scripts of `cluster/`, `experiments/configs/*.yaml`, `main.py`, `pyproject.toml`), the same set as `sync_cluster.ps1`.
  It compares git blob ids (one tree request plus one command on the cluster, nothing is downloaded), shows only what
  differs or "all up to date", and uploads only that. Results, figures and other files are never sent. Code files are not
  touched by scheduled syncs while a job is running.
- **More**: connection check (VPN, ssh, cluster), history of every operation.
- While the app is open it keeps one tunnel and one ssh session alive and refreshes every few seconds;
  in the background it opens short-lived sessions only for scheduled runs.

## Modules

| Module | What |
| --- | --- |
| `core` | JVM library: helper protocol, log parsing, config catalog, presets, script sync, ssh client (JSch) |
| `sstp` | JVM library: SSTP + PPP (MS-CHAPv2) + a minimal TCP stack, exposed as a `java.net.Socket` |
| `app` | Android app (Kotlin, Jetpack Compose, light "A" style) |

## Build (Docker only, nothing is installed on the host)

```powershell
.\make-secrets.ps1     # writes secrets.properties (gitignored) from your .env and ssh key
.\build.ps1            # debug APK -> dist\clustercontrol-debug.apk
.\test.ps1             # unit tests of core and sstp
.\integration.ps1      # core against a throwaway sshd + the real cluster scripts (fake SLURM)
```

`secrets.properties` holds the VPN credentials and the ssh key, which are baked into **your** APK in clear text:
never share that APK and never commit that file. On first launch the app stores them encrypted, so a build without them
keeps working over an install that has them.

```powershell
.\release.ps1 -Publish -Notes "..."   # public apk WITHOUT credentials, leak check, tag + GitHub release, then your own apk
```

The GitHub release carries the credential-free apk. All builds are signed with the debug key kept in the
`cc-android-home` Docker volume, so Android installs a release over your app (lose the volume and you must reinstall once).
Install your own credentialed build first, later versions come from Altro > Aggiornamenti.

`sstp-lab.ps1` and `sstp-e2e.ps1` exercise the tunnel against the real VPN server (one login per run, credentials
via `docker --env-file`).

## Limits

- One TCP flow per tunnel, no proactive SSTP echo, no reconnection inside a tunnel (the app reconnects).
- Doze makes scheduled runs approximate. UI behaviours (gestures, lifecycle) were only checked with JVM screenshots,
  not on a device farm.
