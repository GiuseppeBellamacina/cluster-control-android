# ClusterControl

Android app to drive a SLURM job chain on a university GPU cluster from a phone: tick the chain, manage the
queue (drag and drop), watch the active job, keep the cluster scripts up to date. Personal tool, built for the
[neuro_symbolic_t2g](https://github.com/GiuseppeBellamacina/neuro_symbolic_t2g) pipeline, whose
`cluster/cluster_helper.sh` and `cluster/presets.yaml` it uses.

The cluster accepts ssh only from the university network. The app reaches it through the university SSTP VPN,
implemented in userspace: the tunnel exists only while the app talks to the cluster and carries nothing else, so
the rest of the phone never goes through the VPN.

## Features

- **Dashboard**: active job with big completion %, live ETA, loss/reward charts, countdown to the next tick, last tick result.
- **Queue**: drag and drop reorder, swipe to delete with undo, add jobs or presets from a sheet, pause, kill.
- **Log**: raw log of the active job.
- **Automations**: tick or script sync on an interval (WorkManager, 15 min minimum).
- **More**: cluster scripts sync status, connection check (VPN, ssh, cluster), history of every operation.
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
never share the APK and never commit that file. Without it the app builds but has nothing to connect with.

`sstp-lab.ps1` and `sstp-e2e.ps1` exercise the tunnel against the real VPN server (one login per run, credentials
via `docker --env-file`).

## Limits

- One TCP flow per tunnel, no proactive SSTP echo, no reconnection inside a tunnel (the app reconnects).
- Doze makes scheduled runs approximate. UI behaviours (gestures, lifecycle) were only checked with JVM screenshots,
  not on a device farm.
