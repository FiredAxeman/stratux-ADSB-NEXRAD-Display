package com.example.stratuxdisplay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class NavDatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:usa_nav.db";

    public NavDatabaseManager() {
        // Initialize each table in separate statements for SQLite JDBC compatibility
        try (Connection conn = DriverManager.getConnection(DB_URL);
             java.sql.Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS airports (" +
                    "id TEXT PRIMARY KEY, " +
                    "lat REAL, " +
                    "lon REAL);");

            stmt.execute("CREATE TABLE IF NOT EXISTS airspaces (" +
                    "id TEXT PRIMARY KEY, " +
                    "name TEXT, " +
                    "class_type TEXT, " +
                    "lower_alt INTEGER, " +
                    "upper_alt INTEGER);");

            stmt.execute("CREATE TABLE IF NOT EXISTS airspace_vertices (" +
                    "airspace_id TEXT, " +
                    "seq INTEGER, " +
                    "lat REAL, " +
                    "lon REAL);");

        } catch (Exception e) {
            System.err.println("Database initialization failed: " + e.getMessage());
        }
    }

    public List<NavigationWaypoint> getNearbyAirports(double minLat, double maxLat, double minLon, double maxLon) {
        List<NavigationWaypoint> waypoints = new ArrayList<>();
        // Select the new 'towered' column
        String query = "SELECT id, lat, lon, towered FROM airports WHERE lat > ? AND lat < ? AND lon > ? AND lon < ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {

            pstmt.setDouble(1, minLat);
            pstmt.setDouble(2, maxLat);
            pstmt.setDouble(3, minLon);
            pstmt.setDouble(4, maxLon);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                waypoints.add(new NavigationWaypoint(
                        rs.getString("id"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getInt("towered") == 1 // Convert SQLite integer to boolean
                ));
            }
        } catch (Exception e) {
            System.err.println("Airport query failed: " + e.getMessage());
        }
        return waypoints;
    }

    public List<AirspacePolygon> getNearbyAirspaces(double minLat, double maxLat, double minLon, double maxLon) {
        List<AirspacePolygon> airspaces = new ArrayList<>();

        String query = "SELECT DISTINCT a.id, a.class_type, v.lat, v.lon, v.seq " +
                "FROM airspaces a JOIN airspace_vertices v ON a.id = v.airspace_id " +
                "WHERE a.id IN (SELECT airspace_id FROM airspace_vertices WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?) " +
                "ORDER BY a.id, v.seq";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(query)) {

            pstmt.setDouble(1, minLat);
            pstmt.setDouble(2, maxLat);
            pstmt.setDouble(3, minLon);
            pstmt.setDouble(4, maxLon);

            ResultSet rs = pstmt.executeQuery();
            String currentId = "";
            AirspacePolygon currentAirspace = null;

            while (rs.next()) {
                String id = rs.getString("id");
                if (!id.equals(currentId)) {
                    currentId = id;
                    currentAirspace = new AirspacePolygon();
                    currentAirspace.id = id;
                    currentAirspace.classType = rs.getString("class_type");
                    airspaces.add(currentAirspace);
                }
                if (currentAirspace != null) {
                    currentAirspace.vertices.add(new double[]{rs.getDouble("lat"), rs.getDouble("lon")});
                }
            }
        } catch (Exception e) {
            System.err.println("Airspace query failed: " + e.getMessage());
        }
        return airspaces;
    }
    public List<ElevationCell> getNearbyTerrain(double minLat, double maxLat, double minLon, double maxLon) {
        List<ElevationCell> cells = new ArrayList<>();
        String query = "SELECT lat, lon, elev_ft FROM terrain_grid WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?";

        // Ensure DB_URL points to "jdbc:sqlite:usa_nav.db" as it does for your other methods
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:usa_nav.db");
             java.sql.PreparedStatement pstmt = conn.prepareStatement(query)) {

            pstmt.setDouble(1, minLat);
            pstmt.setDouble(2, maxLat);
            pstmt.setDouble(3, minLon);
            pstmt.setDouble(4, maxLon);

            java.sql.ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                cells.add(new ElevationCell(
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getInt("elev_ft")
                ));
            }
        } catch (Exception e) {
            System.err.println("Terrain lookup failed: " + e.getMessage());
        }
        return cells;
    }
}