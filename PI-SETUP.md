# Raspberry Pi 3B+ setup

This application targets a 480x480 display and a KY-040 encoder connected to
the Pi header.

## Hardware

Use 3.3 V only:

| Encoder | Pi physical pin | BCM GPIO |
|---|---:|---:|
| `+` / `VCC` | 1 (3.3 V) | - |
| `GND` | 6 | - |
| `CLK` / `A` | 11 | 17 |
| `DT` / `B` | 13 | 27 |
| `SW` | 15 | 22 |

The encoder push switch continues past the boot screen. Rotation changes the
radar range.

## Software prerequisites

Use 64-bit Raspberry Pi OS for the `linux-aarch64` JavaFX runtime:

```bash
sudo apt update
sudo apt install openjdk-17-jdk maven python3-pil python3-gpiozero
```

Build the Pi package from the repository root:

```bash
./mvnw -Dpi-aarch64 -DskipTests package
```

Start the FIS-B/encoder bridge and the display as separate processes:

```bash
python3 fisb_decoder.py
java -jar target/stratux-display-1.0-SNAPSHOT.jar
```

The display defaults to Stratux at `192.168.10.1`. Override it when needed:

```bash
java -Dstratux.host=192.168.10.1 -jar target/stratux-display-1.0-SNAPSHOT.jar
```
