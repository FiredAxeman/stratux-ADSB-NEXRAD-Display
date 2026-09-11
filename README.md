[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Java: 17](https://img.shields.io/badge/Java-17-orange.svg)]()
[![Python: 3.x](https://img.shields.io/badge/Python-3.x-yellow.svg)]()
[![Platform: Raspberry Pi](https://img.shields.io/badge/Platform-Raspberry%20Pi-cc2555.svg)]()

A lightweight, bare-metal avionics Electronic Flight Bag (EFB) appliance designed to interface with a Stratux ADS-B receiver. Engineered to run natively on a Raspberry Pi without a full desktop environment, it provides real-time FIS-B weather, traffic, and navigation data via a highly optimized hardware-accelerated JavaFX interface.

## ✈️ Features

* **Real-Time ADS-B/FIS-B:** Decodes live aviation data directly from a Stratux WebSocket stream.
* **Appliance Mode Architecture:** Boots instantly into a full-screen "Kiosk Mode" using Raspberry Pi OS Lite and Openbox. Bypasses the standard desktop for maximum performance and reliability.
* **Hardware-Accelerated Rendering:** Utilizes explicitly allocated GPU memory and Mesa 3D drivers to smoothly render complex airspace polygons and vector data.
* **Offline Navigation Database:** Integrated SQLite database (`usa_nav.db`) for comprehensive waypoint and airspace mapping.

## 🛠️ Hardware Requirements

* Raspberry Pi 3B+ (or newer)
* 480x480 Hardware Display Screen
* Stratux ADS-B Receiver
* MicroSD Card (Configured for OverlayFS / Read-Only to prevent corruption upon master switch power loss)
* Note: Snapshot v1.0 requires the usa_nav.db which is too large for github
* The `usa_nav.db` file contains the complete U.S. airspace, waypoint, and airport database. Because this file exceeds GitHub's 100MB size limit and updates on the FAA's 28-day AIRAC cycle, you must generate it locally before deploying the EFB.

### 1. Download the FAA NASR Data
1. Navigate to the [FAA Aeronautical Information Services (AIS)](https://www.faa.gov/air_traffic/flight_info/aeronav/aero_data/NASR_Subscription/) portal.
2. Download the latest **28-Day NASR Subscription** zip file for the current cycle.
3. Extract the contents (specifically the `APT.txt`, `FIX.txt`, `NAV.txt`, and `AWY.txt` files) into a folder named `faa_data` in the root of this repository.

### 2. Build the SQLite Database
Run the included Python parser script to ingest the raw FAA text files, structure the relational tables, and generate the localized SQLite database.
```bash
# Run the database generation script
python3 build_nav_db.py --input ./faa_data --output usa_nav.db

## 🚀 Installation & Deployment

This application is designed to be deployed as a dedicated appliance on **Raspberry Pi OS Lite (64-bit)**. 

### 1. Base OS Configuration
Flash Raspberry Pi OS Lite onto your MicroSD card. Ensure SSH is enabled and the user is set up. Once booted, install the required headless display and runtime dependencies:
```bash
sudo apt update && sudo apt install -y default-jre openjfx python3 python3-pil xserver-xorg x11-xserver-utils xinit openbox
```

### 2. Hardware Memory Allocation
To ensure smooth vector rendering of complex airspace, allocate dedicated VRAM to the GPU.
Edit `/boot/firmware/config.txt` and append:
```text
gpu_mem=256
```

### 3. Application Deployment
Transfer the release binaries to the Pi's home directory:
* `stratux-display-1.0-SNAPSHOT.jar`
* 'or stratux-display-x.x.jar'
* `usa_nav.db`
* `fisb_decoder.py`

### 4. Kiosk Mode Autostart
Create an `~/.xinitrc` file to define the boot sequence, disable screen blanking, and apply JavaFX memory optimizations:
```bash
#!/bin/bash
xset s off
xset -dpms
xset s noblank

openbox-session &
python3 ~/fisb_decoder.py &
java -Dprism.maxvram=256M -Dprism.targetvram=128M -jar ~/stratux-display-1.0-SNAPSHOT.jar
```

Trigger the display server on boot by appending this to `~/.bash_profile`:
```bash
if [ -z "$DISPLAY" ] && [ "$(tty)" = "/dev/tty1" ]; then
    startx
fi
```

### 5. Network Connectivity
Ensure the Pi is configured to automatically bridge to the Stratux Wi-Fi network to begin pulling the live data stream:
```bash
sudo nmcli dev wifi connect "stratux"
```

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the `LICENSE` file for details.
