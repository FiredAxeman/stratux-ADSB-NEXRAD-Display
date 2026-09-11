import socket, json, os, platform
from PIL import Image

# Auto-detect OS for the shared folder
if platform.system() == "Windows":
    RAM_DISK = "C:\\Temp\\"
else:
    RAM_DISK = "/dev/shm/"

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