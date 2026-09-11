import json
import sqlite3
import sys
from urllib.parse import urlencode
from urllib.request import Request, urlopen


OVERPASS_URL = "https://overpass-api.de/api/interpreter"
DATABASE = "usa_nav.db"


def usage():
    print("Usage: python tools/import_ground_features.py south west north east [grid]")
    print("Example: python tools/import_ground_features.py 38.5 -94.0 39.5 -93.0")
    print("Example: python tools/import_ground_features.py 30.98 -95.14 39.32 -84.96 4")


def parse_bounds(args):
    if len(args) not in (4, 5):
        usage()
        raise SystemExit(2)

    south, west, north, east = (float(value) for value in args[:4])
    if not (-90 <= south < north <= 90 and -180 <= west < east <= 180):
        raise ValueError("Invalid geographic bounding box")
    grid = int(args[4]) if len(args) == 5 else 1
    if grid < 1 or grid > 8:
        raise ValueError("Grid must be between 1 and 8")
    return south, west, north, east, grid


def fetch_features(south, west, north, east):
    bbox = f"{south},{west},{north},{east}"
    query = (
        '[out:json][timeout:180];('
        f'way["highway"~"motorway|trunk|primary|secondary|tertiary"]({bbox});'
        f'way["natural"="water"]({bbox});'
        ");out tags geom;"
    )
    request = Request(
        OVERPASS_URL + "?" + urlencode({"data": query}),
        headers={"User-Agent": "stratux-display-ground-import/1.0"},
    )
    with urlopen(request, timeout=240) as response:
        if response.status != 200:
            raise RuntimeError(f"Overpass returned HTTP {response.status}")
        return json.load(response)["elements"]


def create_tables(connection):
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS roads (
            id INTEGER PRIMARY KEY,
            name TEXT,
            type TEXT
        );
        CREATE TABLE IF NOT EXISTS road_vertices (
            road_id INTEGER,
            seq INTEGER,
            lat REAL,
            lon REAL
        );
        CREATE TABLE IF NOT EXISTS water_features (
            id TEXT PRIMARY KEY,
            name TEXT
        );
        CREATE TABLE IF NOT EXISTS water_vertices (
            water_id TEXT,
            seq INTEGER,
            lat REAL,
            lon REAL
        );
        """
    )


def import_features(elements):
    connection = sqlite3.connect(DATABASE)
    try:
        create_tables(connection)
        road_count = 0
        water_count = 0

        for element in elements:
            if element.get("type") != "way":
                continue
            tags = element.get("tags", {})
            geometry = element.get("geometry", [])
            if not geometry:
                continue

            osm_id = element["id"]
            name = tags.get("name", "")

            if "highway" in tags:
                connection.execute(
                    "INSERT OR REPLACE INTO roads (id, name, type) VALUES (?, ?, ?)",
                    (osm_id, name, tags["highway"]),
                )
                connection.execute(
                    "DELETE FROM road_vertices WHERE road_id = ?", (osm_id,)
                )
                connection.executemany(
                    "INSERT INTO road_vertices (road_id, seq, lat, lon) "
                    "VALUES (?, ?, ?, ?)",
                    [
                        (osm_id, index, point["lat"], point["lon"])
                        for index, point in enumerate(geometry)
                    ],
                )
                road_count += 1
            elif tags.get("natural") == "water":
                water_id = f"osm-{osm_id}"
                connection.execute(
                    "INSERT OR REPLACE INTO water_features (id, name) VALUES (?, ?)",
                    (water_id, name),
                )
                connection.execute(
                    "DELETE FROM water_vertices WHERE water_id = ?", (water_id,)
                )
                connection.executemany(
                    "INSERT INTO water_vertices (water_id, seq, lat, lon) "
                    "VALUES (?, ?, ?, ?)",
                    [
                        (water_id, index, point["lat"], point["lon"])
                        for index, point in enumerate(geometry)
                    ],
                )
                water_count += 1

        connection.commit()
        print(f"Imported {road_count} roads and {water_count} water features.")
    finally:
        connection.close()


if __name__ == "__main__":
    try:
        south, west, north, east, grid = parse_bounds(sys.argv[1:])
        lat_step = (north - south) / grid
        lon_step = (east - west) / grid
        total = 0
        for row in range(grid):
            cell_south = south + row * lat_step
            cell_north = north if row == grid - 1 else cell_south + lat_step
            for column in range(grid):
                cell_west = west + column * lon_step
                cell_east = east if column == grid - 1 else cell_west + lon_step
                print(
                    f"Importing cell {row * grid + column + 1}/{grid * grid}: "
                    f"{cell_south:.4f},{cell_west:.4f},{cell_north:.4f},{cell_east:.4f}",
                    flush=True,
                )
                try:
                    elements = fetch_features(
                        cell_south, cell_west, cell_north, cell_east
                    )
                    import_features(elements)
                    total += len(elements)
                except Exception as error:
                    print(f"Cell failed, continuing: {error}", file=sys.stderr)
        print(f"Processed {total} OSM elements across {grid * grid} cells.")
    except Exception as error:
        print(f"Ground feature import failed: {error}", file=sys.stderr)
        raise SystemExit(1)
