# NPU King architecture

NPU King is a single Android app with two modes.

## Standalone

```
MainActivity
  └── NpuDetector
        ├── Build.HARDWARE / SOC_MANUFACTURER
        ├── known NPU sysfs and driver paths
        └── NNAPI feature level
```

No network calls, no service, no registration. The UI displays the detected
profile and a device probe button. Detection is best-effort.

## Connect

```
MainActivity
  └── WorkerService (foreground, specialUse)
        ├── BatchApi.register()
        ├── BatchApi.joinCompany()       optional
        ├── BatchApi.heartbeat()         every 30 s
        ├── BatchApi.nextTask()          every 5 s
        ├── processOnNpu()               beta XOR stub
        └── BatchApi.result()
```

The service is a foreground service with a persistent notification. It stops
when the user taps STOP or the OS kills the process (START_STICKY restarts it).

## Identity

- `device_id`: random per install, not `ANDROID_ID`.
- `device_secret`: random per install, stored only as a SHA-256 hash by the
  tracker.
- `worker_id` + `api_token`: issued by the tracker on register.
- Reinstall creates a new worker. A `409` means the device identity changed;
  the app clears the local identity and registers again.

## Settings

`Prefs` uses the legacy `batchdaddy` SharedPreferences file so an existing
0.2.x install keeps its worker identity and payout address after upgrading to
NPU King. New keys:

- `mode`: `standalone` or `connect`.
- `company_slug`: optional company pool.

## Failure handling

| Tracker response | App behavior |
|---|---|
| `403` / `404` | Clear the worker token and re-register |
| `409` | Clear device id/secret and re-register as a new install |
| Network error | Keep the foreground service, show ERROR, retry after 15 s |
| Company join failure | Show the error; the worker retries registration/join |

## Beta compute

`processOnNpu()` is a deterministic XOR of the task input and payload. It is
labelled in the UI and the README. It is not NPU inference and must not be
represented as such. The protocol, heartbeat, task claim, reward accounting,
and payout path are real.
