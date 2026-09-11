package com.example.stratuxdisplay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class DatabaseSeeder {

    // The connection string pointing to your local database
    private static final String DB_URL = "jdbc:sqlite:usa_nav.db";

    // Public dataset containing global airport data
    private static final String CSV_URL = "https://davidmegginson.github.io/ourairports-data/airports.csv";

    public static void main(String[] args) {
        System.out.println("Downloading and processing aviation data. This will take a few seconds...");

        try (Connection conn = DriverManager.getConnection(DB_URL)) {

            // 1. Ensure the table exists FIRST
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS airports (id TEXT PRIMARY KEY, lat REAL, lon REAL)");
            }

            // 2. NOW prepare the insert statement and process the file
            String insertSQL = "INSERT OR REPLACE INTO airports (id, lat, lon) VALUES (?, ?, ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(insertSQL);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(URI.create(CSV_URL).toURL().openStream()))) {

                // Turn off auto-commit for bulk transaction performance
                conn.setAutoCommit(false);

                String line;
                int count = 0;

                // Skip the CSV header row
                reader.readLine();

                while ((line = reader.readLine()) != null) {
                    // Regex to split the CSV by commas, but ignore commas that are inside quotation marks
                    String[] data = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

                    if (data.length > 8) {
                        String ident = data[1].replace("\"", "");
                        String type = data[2].replace("\"", "");
                        String country = data[8].replace("\"", "");

                        // Filter: Only grab active US airports (ignoring closed airports and heliports)
                        if (country.equals("US") && !type.equals("closed") && !type.equals("heliport")) {
                            double lat = Double.parseDouble(data[4].replace("\"", ""));
                            double lon = Double.parseDouble(data[5].replace("\"", ""));

                            pstmt.setString(1, ident);
                            pstmt.setDouble(2, lat);
                            pstmt.setDouble(3, lon);
                            pstmt.addBatch();
                            count++;

                            // Execute the batch every 1,000 records to manage memory efficiently
                            if (count % 1000 == 0) {
                                pstmt.executeBatch();
                            }
                        }
                    }
                }

                // Execute any remaining records in the final batch
                pstmt.executeBatch();
                conn.commit();

                System.out.println("Success! Seeded " + count + " active US airports into usa_nav.db.");
            }

        } catch (Exception e) {
            System.err.println("Database seeding failed: " + e.getMessage());
        }
    }
}