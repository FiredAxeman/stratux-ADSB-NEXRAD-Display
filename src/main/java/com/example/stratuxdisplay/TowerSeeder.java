package com.example.stratuxdisplay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class TowerSeeder {
    private static final String DB_URL = "jdbc:sqlite:usa_nav.db";
    private static final String FREQ_CSV_URL = "https://davidmegginson.github.io/ourairports-data/airport-frequencies.csv";

    public static void main(String[] args) {
        System.out.println("Updating database with control tower data...");
        try (Connection conn = DriverManager.getConnection(DB_URL)) {

            // Add the towered column if it doesn't exist
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE airports ADD COLUMN towered INTEGER DEFAULT 0");
            } catch (Exception e) {
                // Ignore if column already exists
            }

            String updateSQL = "UPDATE airports SET towered = 1 WHERE id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSQL);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(URI.create(FREQ_CSV_URL).toURL().openStream()))) {

                conn.setAutoCommit(false);
                String line;
                int count = 0;
                reader.readLine(); // skip header

                while ((line = reader.readLine()) != null) {
                    String[] data = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                    if (data.length > 3) {
                        String ident = data[2].replace("\"", "");
                        String type = data[3].replace("\"", "").toUpperCase();

                        // If the airport has an active TWR frequency, flag it
                        if (type.equals("TWR")) {
                            pstmt.setString(1, ident);
                            pstmt.addBatch();
                            count++;
                            if (count % 500 == 0) {
                                pstmt.executeBatch();
                            }
                        }
                    }
                }
                pstmt.executeBatch();
                conn.commit();
                System.out.println("Successfully flagged " + count + " towered airports!");
            }
        } catch (Exception e) {
            System.err.println("Tower seeding failed: " + e.getMessage());
        }
    }
}
