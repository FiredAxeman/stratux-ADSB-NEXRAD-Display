# Stratux Display

![Platform](https://img.shields.io/badge/platform-Raspberry%20Pi%20%7C%20Windows-informational)
![Java](https://img.shields.io/badge/Java-17-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-17-blue)
![License](https://img.shields.io/badge/license-GPLv3-blue)

**Stratux Display** is a lightweight, hardware-oriented Electronic Flight Bag
(EFB) display for a Stratux ADS-B receiver. It is designed for a 480x480
screen connected to a Raspberry Pi, but it can also run on Windows for
development and testing.

The application renders an always-on aviation display without requiring a
full desktop environment. It combines live Stratux traffic and ownship
situation data with local navigation and map data in a JavaFX interface
optimized for embedded hardware.

> **Status:** This is an actively developed experimental avionics display.
> It is not certified navigation equipment and must not be used as the sole
> source of information for flight operations.

## Highlights

- **Stratux integration** through the receiver's traffic WebSocket and
  situation API.
- **Traffic radar** with configurable range, traffic aging, ownship heading,
  track, altitude, and groundspeed.
- **FIS-B/NEXRAD display pipeline** using a shared RAM-backed cache for
  low-latency image and bounds updates.
- **Offline navigation database** backed by SQLite (`usa_nav.db`), including
  airports, airspace, roads, water features, state borders, and navigation
  metadata.
- **Nearest traffic and airport pages** for quickly reviewing nearby targets
  and airports.
- **Direct-To and OBS/CDI navigation** with course-up presentation and
  destination guidance.
- **KY-040 / EC11 rotary encoder support** for range control, page selection,
  and display interaction on Raspberry Pi.
- **Windows input simulation** using mouse clicks and the mouse wheel, making
  the display easier to exercise without GPIO hardware.
- **Embedded appliance behavior** with a fixed 480x480 undecorated window,
  Java 17 runtime checks, and a Raspberry Pi AArch64 Maven profile.
- **Atomic cache updates** for radar assets, helping prevent partially written
  display files from being consumed by the JavaFX renderer.

## Hardware

### Required

- Raspberry Pi 3B+ or newer
- 64-bit Raspberry Pi OS
- 480x480 display
- Stratux ADS-B receiver
- MicroSD card with adequate protection against unexpected power loss

### Optional controls

The display supports a KY-040 / EC11 rotary encoder through Raspberry Pi GPIO.
The current Python bridge uses:

| Encoder signal | BCM GPIO |
| --- | ---: |
| `CLK` / `A` | 5 |
| `DT` / `B` | 6 |
| `SW` | 13 |

Use 3.3 V logic only. See [`PI-SETUP.md`](PI-SETUP.md) for the complete
interaction model and setup notes.

## Software requirements

- Java Development Kit 17
- Maven, or the included Maven wrapper
- Python 3
- Python packages used by the bridge:
  - Pillow
  - `gpiozero` on Raspberry Pi when using the encoder

The Raspberry Pi build uses the JavaFX `linux-aarch64` runtime. The default
Maven build targets Windows JavaFX binaries; use the `pi-aarch64` profile for
the Pi package.

## Build

Clone the repository and build the platform-specific shaded JAR from the
repository root.

### Windows

```powershell
.\mvnw.cmd clean package
```

### Raspberry Pi

```bash
./mvnw clean -Dpi-aarch64 -DskipTests package
```

The packaged application is written to `target/stratux-display-1.0.0.jar`.
The shaded JAR contains the application dependencies; JavaFX is supplied for
the selected platform by Maven.

## Run

### Windows development mode

Run the packaged application with:

```powershell
.\run-display.cmd
```

Windows maps a mouse click to the encoder button and the mouse wheel to
encoder rotation. This makes it possible to test the display without a
connected GPIO device.

### Raspberry Pi

Install the runtime dependencies:

```bash
sudo apt update
sudo apt install openjdk-17-jdk maven python3-pil python3-gpiozero
```

Start the FIS-B/encoder bridge and the display as separate processes:

```bash
python3 fisb_decoder.py
./run-display.sh
```

The application connects to Stratux at `192.168.10.1` by default. To use a
different receiver address:

```bash
./run-display.sh -Dstratux.host=192.168.10.1
```

The same property can be supplied through the `STRATUX_HOST` environment
variable. The bridge listens for FIS-B packets on UDP port `4000` and sends
encoder events to the JavaFX application on UDP port `4010`.

## Raspberry Pi appliance deployment

For a kiosk-style installation, use Raspberry Pi OS Lite with an X server and
Openbox, then start the application from the Pi's boot session. A minimal
`.xinitrc` can contain:

```bash
#!/bin/bash
xset s off
xset -dpms
xset s noblank

openbox-session &
python3 ~/fisb_decoder.py &
~/stratux-display/run-display.sh
```

Start X automatically from the first virtual console by adding this to
`~/.bash_profile`:

```bash
if [ -z "$DISPLAY" ] && [ "$(tty)" = "/dev/tty1" ]; then
    startx
fi
```

For additional Raspberry Pi wiring, navigation behavior, and deployment
details, see [`PI-SETUP.md`](PI-SETUP.md).

## Data and configuration

| Item | Purpose |
| --- | --- |
| `usa_nav.db` | Local SQLite airports, airspace, terrain/map features, and metadata |
| `fisb_decoder.py` | FIS-B cache and GPIO-to-UDP bridge |
| `run-display.sh` | Java 17 validation and Linux launcher |
| `run-display.cmd` | Java 17 validation and Windows launcher |
| `src/main/java` | JavaFX display, Stratux client, navigation, and database code |
| `tools/import_ground_features.py` | Ground-feature database import utility |

The display reads the navigation database from the working directory. Keep
`usa_nav.db` beside the packaged JAR or launch script. Verify that the
navigation data is current before flight; the application shows the database
version and expiry metadata on its startup screen.

## Architecture

The application is split into three cooperating parts:

1. **JavaFX display** — renders the radar, traffic, navigation pages, map
   features, status indicators, and boot screen.
2. **Stratux client** — consumes `/traffic` over WebSocket and
   `/getSituation` over HTTP from the receiver.
3. **Python bridge** — receives FIS-B packets, maintains the shared NEXRAD
   cache, and forwards rotary-encoder events over localhost UDP.

This separation keeps hardware input and packet handling independent from the
rendering loop and allows the JavaFX application to run on Windows during
development.

## License

This project is licensed under the **GNU General Public License v3.0
(GPLv3)**. See the project license information for the full terms.

