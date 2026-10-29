# NPU King — BatchDaddy worker protocol

The canonical API reference is in the BatchDaddy repo:
[`docs/API.md`](https://github.com/tehNate/batch-daddy/blob/master/docs/API.md).

This page is the short version the app implements.

## 1. Register

`POST /api/worker/register`

```json
{
  "device_id": "android-...",
  "device_secret": "...",
  "device_name": "Google Pixel 8",
  "npu": {
    "chipset": "tensor",
    "vendor": "google",
    "driver": "NNAPI",
    "api_level": "NNAPI 5",
    "compute_units": 4,
    "max_memory_mb": 2048,
    "supported_ops": ["ADD", "MUL", "CONV_2D"]
  },
  "battery_pct": 82,
  "charging": true,
  "android_version": "15",
  "payout_address": "5..."
}
```

Store `worker_id` and `api_token` locally. `payout_address` is optional at
registration but required before rewards accrue.

## 2. Join a company (optional)

`POST /api/companies/{slug}/join`

```json
{
  "worker_id": "a1b2c3d4",
  "token": "...",
  "payout_address": "5..."
}
```

The tracker verifies the worker token and the SS58 address. The membership is
idempotent. Blank slug = platform BATCH pool.

## 3. Heartbeat

`POST /api/worker/heartbeat` every 30 seconds.

```json
{
  "worker_id": "a1b2c3d4",
  "token": "...",
  "battery_pct": 82,
  "charging": true,
  "cpu_load": 0.1,
  "npu_load": 0.0,
  "memory_used_mb": 512,
  "status": "IDLE"
}
```

## 4. Pull a task

`POST /api/worker/next-task` every 5 seconds.

```json
{ "worker_id": "a1b2c3d4", "token": "...", "max_tasks": 1 }
```

Response: `{ "task": { ... } }` or `{ "task": null }`.

## 5. Post a result

`POST /api/worker/result`

```json
{
  "worker_id": "a1b2c3d4",
  "token": "...",
  "job_id": "abc123",
  "task_id": "abc123-0",
  "data_b64": "...",
  "compute_time_ms": 42.5,
  "error": ""
}
```

## Retry rules

- `403`/`404`: clear the worker token, re-register.
- `409`: clear device id/secret, re-register.
- Other errors: keep running, show ERROR, retry after 15 s.
