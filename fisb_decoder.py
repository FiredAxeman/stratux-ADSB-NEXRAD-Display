import socket, json, os, platform
from PIL import Image

# Auto-detect OS for the shared folder
if platform.system() == "Windows":
    RAM_DISK = "C:\\Temp\\"
else:
    RAM_DISK = "/dev/shm/"

def update_radar_cache():
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
    bounds = {"north": 35.5, "south": 34.5, "east": -89.5, "west": -90.5}
    with open(os.path.join(RAM_DISK, "nexrad_bounds.json"), "w") as f:
        json.dump(bounds, f)

def start_daemon():
    # For testing without live UDP data, we'll just run the cache update once
    print(f"Generating blank NEXRAD canvas in {RAM_DISK}...")
    update_radar_cache()
    print("Canvas generated successfully. Ready for live decoding.")

    # Uncomment this block later when you are ready to listen for real Stratux UDP packets
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 4000))
    print("FIS-B Daemon listening on 4000...")
    
    while True:
        data, _ = sock.recvfrom(4096)
        if len(data) > 4 and data[0] == 0x7E and data[1] == 0x08:
            # Future live decoding logic goes here
            update_radar_cache() 
    """

if __name__ == "__main__":
    start_daemon()