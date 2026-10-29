# NPU King

**Android NPU compute client for phone fleets.**

NPU King detects the phone's NPU, exposes a worker UI, and can either run
standalone as a device probe or connect to a [BatchDaddy](https://github.com/tehNate/batch-daddy)
tracker as a compute worker.

> **Status:** beta `0.3.0`. The compute path is a labelled stub; real NNAPI /
> QNN / Neuron inference and result verification come after the beta. The
> package ID is still `com.batchdaddy` for upgrade continuity with the 0.2.x
> worker. The label and product are NPU King.

## Modes

### Standalone

No server, no account, no payout address. The app shows the detected SoC,
vendor, driver, NNAPI level, estimated compute units, memory budget, and
supported ops. Use it to check what the phone exposes before joining a pool.

### Connect to BatchDaddy

Register as a worker, optionally join a company pool, heartbeat, pull tasks,
run the beta compute stub, and post results. Rewards are accrued by the
tracker; the phone never holds a chain key.

Settings:

| Field | Meaning |
|---|---|
| Tracker URL | BatchDaddy base URL, default `https://batch.cocoio.cc` |
| Company pool | Optional slug; blank uses the platform BATCH pool |
| Payout address | SS58 address (prefix 42) for rewards |

The app warns when the tracker URL uses plain `http://`. Use HTTPS for real
payout addresses.

## Build

Requirements: JDK 17, Android SDK 35, Gradle 8.7 (wrapper included).

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

For a local build, create `local.properties` (ignored by git):

```properties
sdk.dir=/opt/android-sdk
```

## How it works

```
MainActivity (Compose)
  ├── Standalone: NpuDetector probe
  └── Connect: WorkerService
        ├── POST /api/worker/register
        ├── POST /api/companies/{slug}/join   (optional)
        ├── POST /api/worker/heartbeat        every 30 s
        ├── POST /api/worker/next-task        every 5 s
        ├── execute beta stub
        └── POST /api/worker/result
```

The tracker API contract is documented in the BatchDaddy repo:
[`docs/API.md`](https://github.com/tehNate/batch-daddy/blob/master/docs/API.md).

## NPU detection

The detector fingerprints the SoC and probes known NPU driver paths:

| Vendor | Detection |
|---|---|
| MediaTek / Dimensity | `/sys/class/npu`, Neuron adapter libs |
| Qualcomm / Snapdragon | Build properties, QNN libs, NNAPI |
| Samsung / Exynos | `sys/kernel/npu`, NNAPI |
| Google / Tensor | EdgeTPU client libs, NNAPI |
| HiSilicon / Kirin | `/sys/class/npu`, Da Vinci, NNAPI |
| Generic | NNAPI (Android 8.1+) |

Detection is best-effort. A positive result means the phone exposes an NPU
surface, not that a model has been executed on it.

## Security

- No treasury seeds, mnemonics, or private keys on the phone.
- The worker token is per-install; registration is bound to a device secret.
- Payout addresses must be valid SS58 (prefix 42).
- The app does not sign payouts. The tracker prepares a batch; the pool owner
  signs it.
- Use HTTPS. The beta allows cleartext for local testing and shows a warning.

See [`SECURITY.md`](SECURITY.md).

## Repository layout

```
app/                 Android app (Kotlin, Jetpack Compose)
  src/main/java/com/batchdaddy/
    net/             REST client for the BatchDaddy tracker
    npu/             NPU detection
    ui/              Compose UI
    worker/          Foreground worker service
docs/                Protocol and architecture notes
```

## Related

- [BatchDaddy](https://github.com/tehNate/batch-daddy) — tracker, marketplace,
  company coins, payout accounting.
- [companycoin.org](https://companycoin.org) — CoCo chain and wallet.

## License

GPL-3.0. See [`LICENSE`](LICENSE).
