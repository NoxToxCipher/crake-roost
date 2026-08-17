# Crake Roost

Run a real [Tox](https://tox.chat) relay and bootstrap node on a spare Android
phone or tablet. Roost turns an unused device into a **UDP DHT node**, a **TCP
relay**, and a **bootstrap node** for the Tox network, so more of the network's
traffic has somewhere to pass through.

Part of the [Crake](https://github.com/NoxToxCipher) privacy suite.

## Why this exists

The Tox network leans on a thin pool of public relays and bootstrap nodes. A
larger, more spread-out pool makes the network faster and harder to disrupt.
Most people have an old phone in a drawer. Roost lets that phone add capacity to
a decentralized, peer-to-peer messaging network instead of sitting idle.

The node holds no user secrets. It is not a messenger and cannot read anything
that passes through it. Tox is end-to-end encrypted; a relay only forwards
ciphertext.

## What it does

- **Bootstrap node** so new clients can find the DHT
- **UDP DHT node** taking part in Tox's distributed hash table
- **TCP relay** so peers behind restrictive networks can still connect
- Runs as a foreground service with a wakelock, optionally starting on boot
- Generates and stores its own DHT keypair on the device (never cloud-backed)

## Reachability, told straight

Roost never shows a green "working" light it has not earned. The status chip has
three states:

- **Amber, "Running. No one has reached it yet"** the node is up but no peer has
  connected in
- **Green, "Reachable. A peer has connected in"** at least one peer has actually
  reached the relay from outside, proven by the relay's received-packet count
- **Amber, "Reachability not checked"** the count could not be read

Green is earned, not assumed. Behind a home NAT with no port forwarding the chip
stays amber, because that is the truth: the node runs, but the wider internet
cannot reach it yet.

## Running a relay: read this first

A relay's IP address is public by design. Anyone can see the address of a node
that is relaying traffic.

- **Do not run Roost on a home connection you care about.** It publishes that
  connection's public IP as a relay address. For a family home, that means
  publishing your household's residential IP.
- The intended home for a public relay is a pseudonymous VPS or a connection you
  are comfortable publishing.
- Reachability needs an inbound path: a forwarded port or UPnP. A connection
  behind CGNAT cannot accept inbound connections and cannot host a reachable
  relay.

## Limitations

- **Port 443 does not bind on an unrooted Android device.** Ports below 1024 are
  privileged, so Roost binds only high ports (for example 33445 and 3389). A
  phone relay adds capacity but cannot serve the 443 censorship-circumvention
  path that a VPS relay can.
- Native libraries currently build for `arm64-v8a` and `x86_64`.
- `minSdk 26`, `targetSdk 34`.

## Build

Open the project in Android Studio. It generates the Gradle wrapper and
`local.properties`, then downloads the Android Gradle Plugin and Kotlin
toolchain. Then **Build > Build APK**.

The prebuilt native libraries (`libtoxnode.so`, `libsodium.so` for both ABIs)
are checked in under `app/src/main/jniLibs/`, so you can build the APK without
running the native toolchain yourself. The C sources and JNI bridge live in
`native/`.

## How it is put together

- `native/toxnode.c` a compact node that calls the same toxcore functions the
  reference `tox-bootstrapd` daemon uses, configured by arguments rather than a
  config file
- `native/toxnode_jni.c` the JNI bridge (`com.crake.roost.ToxNode`)
- `app/` the Kotlin app: foreground `RelayService`, `MainActivity` with the
  status chip and node key, boot receiver, and battery-optimization handling

Built on the reference [`c-toxcore`](https://github.com/TokTok/c-toxcore)
library and [libsodium](https://libsodium.org).

## License

Copyright (C) 2026 NoxToxCipher

Crake Roost is free software: you can redistribute it and modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version (`GPL-3.0-or-later`). See [LICENSE](LICENSE) for the full text.

It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY,
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
PURPOSE. See the GNU General Public License for details.

This project links [`c-toxcore`](https://github.com/TokTok/c-toxcore), which is
also GPLv3, and [libsodium](https://libsodium.org), which is ISC licensed.
