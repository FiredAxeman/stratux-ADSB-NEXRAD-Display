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
* Note: Snapshot v1.0 requires the usa_nav.db

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
