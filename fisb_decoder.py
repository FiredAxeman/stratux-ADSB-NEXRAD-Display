import json
import os
import platform
import socket
from PIL import Image

try:
    from gpiozero import Button, RotaryEncoder
except ImportError:
    Button = None
    RotaryEncoder = None

# Auto-detect OS for the shared folder
if platform.system() == "Windows":
    RAM_DISK = "C:\\Temp\\"
else:
    RAM_DISK = "/dev/shm/"

ENCODER_HOST = "127.0.0.1"
ENCODER_PORT = 4010
ENCODER_CLK_PIN = 5
ENCODER_DT_PIN = 6
ENCODER_BUTTON_PIN = 13  # Updated to match GPIO 13 in direct-wire diagram


def start_encoder_listener():
    """Bridge the EC11 GPIO events to the JavaFX application."""
    if platform.system() == "Windows":
        print("GPIO encoder disabled on Windows.")
        return None
    if RotaryEncoder is None or Button is None:
        print("GPIO encoder disabled: install python3-gpiozero on the Raspberry Pi.")
        return None

    command_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def send_command(command, delta=0):
        payload = json.dumps({"event": command, "delta": delta}).encode("utf-8")
        command_socket.sendto(payload, (ENCODER_HOST, ENCODER_PORT))

    # gpiozero automatically uses internal pull-ups for RotaryEncoder pins
    encoder = RotaryEncoder(ENCODER_CLK_PIN, ENCODER_DT_PIN, max_steps=0)

    # pull_up=True ensures 3.3V logic; bounce_time=0.05 provides 50ms software debouncing
    button = Button(ENCODER_BUTTON_PIN, pull_up=True, bounce_time=0.05)

    encoder.when_rotated_clockwise = lambda: send_command("rotate", 1)
    encoder.when_rotated_counter_clockwise = lambda: send_command("rotate", -1)
    print(
        "EC11 encoder listening: "
        f"CLK=GPIO{ENCODER_CLK_PIN}, DT=GPIO{ENCODER_DT_PIN}, "
        f"SW=GPIO{ENCODER_BUTTON_PIN}"
    )
    return encoder, button, command_socket


def update_radar_cache(valid_data=False):
    # 1. Generate a blank, transparent radar overlay PNG
    img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))

    # Save as temp file, then rename atomically
    tmp_img = os.path.join(RAM_DISK, "temp_nexrad.png")
    final_img = os.path.join(RAM_DISK, "latest_nexrad.png")
    img.save(tmp_img)

    # Windows requires the destination file to be removed before renaming
    if platform.system() == "Windows" and os.path.exists(final_img):
        os.remove(final_img)

    os.rename(tmp_img, final_img)

    # 2. Generate the bounding box anchor JSON
    bounds = {
        "north": 35.5,
        "south": 34.5,
        "east": -89.5,
        "west": -90.5,
        "valid": valid_data,
    }
    with open(os.path.join(RAM_DISK, "nexrad_bounds.json"), "w") as f:
        json.dump(bounds, f)

def is_valid_fisb_packet(data):
    # Stratux FIS-B frames begin with the FIS-B message marker and need a payload.
    return len(data) > 4 and data[0] == 0x7E and data[1] == 0x08

def start_daemon():
    print(f"Generating blank NEXRAD canvas in {RAM_DISK}...")
    update_radar_cache(valid_data=False)
    print("Canvas generated successfully. Listening for valid FIS-B data on UDP 4000...")
    encoder_resources = start_encoder_listener()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 4000))
    print("FIS-B Daemon listening on 4000...")

    while True:
        data, _ = sock.recvfrom(4096)
        if is_valid_fisb_packet(data):
            # Future live decoding logic goes here
            update_radar_cache(valid_data=True)

if __name__ == "__main__":
    start_daemon()