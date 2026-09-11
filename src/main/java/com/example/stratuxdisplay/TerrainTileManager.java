package com.example.stratuxdisplay;

import javafx.scene.image.Image;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ConcurrentHashMap;

public class TerrainTileManager {
    private static final String DB_URL = "jdbc:sqlite:regional.mbtiles";
    private final ConcurrentHashMap<String, Image> tileCache = new ConcurrentHashMap<>();

    // Converts Lat/Lon to standard Web Mercator fractional tile coordinates
    public double[] getExactTileCoords(double lat, double lon, int zoom) {
        double n = Math.pow(2.0, zoom);
        double x = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(latRad) + (1.0 / Math.cos(latRad))) / Math.PI) / 2.0 * n;
        return new double[]{x, y};
    }

    public Image getTile(int zoom, int x, int y) {
        // MBTiles standard uses TMS numbering (origin at bottom-left)
        int tmsY = (1 << zoom) - 1 - y;
        String key = zoom + "_" + x + "_" + tmsY;

        if (tileCache.containsKey(key)) {
            return tileCache.get(key);
        }

        String query = "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {

            pstmt.setInt(1, zoom);
            pstmt.setInt(2, x);
            pstmt.setInt(3, tmsY);

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                byte[] bytes = rs.getBytes("tile_data");
                if (bytes != null) {
                    Image img = new Image(new ByteArrayInputStream(bytes));
                    tileCache.put(key, img);
                    if (tileCache.size() > 100) tileCache.clear(); // Keep RAM footprint low for the Pi
                    return img;
                }
            }
        } catch (Exception e) {
            System.err.println("MBTile read error: " + e.getMessage());
        }
        return null;
    }
}