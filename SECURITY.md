# Security policy

## Supported versions

NPU King is supported on the current `master` branch and the latest `v0.3.x`
release. Older beta APKs are not supported.

## Reporting a vulnerability

Do not open a public issue for a token leak, payout bug, or authentication
bypass. Report it privately:

- Email: `security@cocoio.cc`
- Include: app version, Android version, device model, reproduction steps, and
  impact.

We will acknowledge within 72 hours and coordinate a fix and disclosure.

## Secrets

This repository must never contain:

- treasury seeds, mnemonics, private keys, or keystore files;
- tracker admin tokens or company tokens;
- worker API tokens or device secrets;
- production APK signing keys.

The app stores the worker token and device secret in app-private
SharedPreferences. It never stores a chain key.

## Security model

- The phone never signs payouts and never holds a company or platform treasury
  key.
- Registration is bound to a per-install device secret; the tracker stores only
  its SHA-256.
- Worker calls require the per-install API token.
- Payout addresses are SS58-validated (prefix 42).
- The app warns on plain HTTP. Use HTTPS for real payout addresses.
- The beta compute path is a labelled stub and is not a proof of NPU inference.

## Release integrity

Release APKs are debug-signed in the beta. Verify the SHA-256 published with the
GitHub release before sideloading. Release signing is a follow-up.
