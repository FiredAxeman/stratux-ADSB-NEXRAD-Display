package com.example.stratuxdisplay;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import com.google.gson.Gson;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.nio.file.Files;

public class StratuxDisplayApplication extends Application implements WebSocket.Listener {

    private WebSocket stratuxWebSocket;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<Integer, TrafficTarget> trafficMap = new ConcurrentHashMap<>();
    private final StringBuilder messageBuffer = new StringBuilder();

    // Ownship Telemetry
    private volatile double currentHeading = 0.0;
    private volatile double currentAltitude = 0.0;
    private volatile double currentLatitude = 0.0;
    private volatile double currentLongitude = 0.0;
    private volatile double currentGroundSpeedKnots = 0.0;

    // Range & Zoom Control (Default 10 NM, Max 100 NM)
    private volatile int currentRangeNM = 10;

    // Database and Map Data
    private final NavDatabaseManager dbManager = new NavDatabaseManager();
    private volatile List<NavigationWaypoint> activeAirports = new ArrayList<>();
    private volatile List<AirspacePolygon> activeAirspaces = new ArrayList<>();
    private volatile List<ElevationCell> activeTerrain = new ArrayList<>();
    private final List<StateBorder> stateBorders = new ArrayList<>();

    // Status & NEXRAD Data
    private volatile javafx.scene.image.Image currentNexradImage = null;
    private volatile long lastNexradTimeMs = 0;
    private volatile long lastTrafficTimeMs = 0;
    private volatile long lastLoadedNexradFileTime = 0;

    // FIS-B Bounding Box Anchors
    private volatile double nexradNorth = 0.0;
    private volatile double nexradSouth = 0.0;
    private volatile double nexradEast = 0.0;
    private volatile double nexradWest = 0.0;

    private Canvas canvas;

    // Data model for GSON to parse the RAM disk JSON
    private static class NexradBounds {
        public double north;
        public double south;
        public double east;
        public double west;
    }

    @Override
    public void start(Stage primaryStage) {
        loadStateBorders();

        // Lock to the exact Raspberry Pi LCD resolution
        canvas = new Canvas(480, 480);
        HBox controlBar = createTouchControlBar();

        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #000000;");

        root.getChildren().addAll(canvas, controlBar);
        StackPane.setAlignment(controlBar, Pos.TOP_LEFT);
        StackPane.setMargin(controlBar, new Insets(0));

        VBox bootScreen = createBootScreen(root);
        root.getChildren().add(bootScreen);

        // Lock the Scene to 480x480
        Scene scene = new Scene(root, 480, 480);

        // Remove standard desktop window borders for embedded hardware deployment
        primaryStage.initStyle(javafx.stage.StageStyle.UNDECORATED);

        primaryStage.setTitle("Stratux HUD");
        primaryStage.setScene(scene);

        primaryStage.setOnCloseRequest(event -> {
            if (stratuxWebSocket != null) {
                stratuxWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing");
            }
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();

        AnimationTimer renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                renderRadar();
            }
        };
        renderLoop.start();

        connectToStratux();
        pollSituationData();
        pollNexradData();
    }

    private VBox createBootScreen(StackPane root) {
        VBox bootOverlay = new VBox(25);
        bootOverlay.setAlignment(Pos.CENTER);
        bootOverlay.setStyle("-fx-background-color: #000000;");

        // Scaled fonts for 480x480 micro-display
        Label titleLabel = new Label("HarTech Awareness System");
        titleLabel.setTextFill(Color.WHITE);
        titleLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 18));

        Label dbLabel = new Label("Aviation Database Expires: 08-OCT-2026");
        dbLabel.setTextFill(Color.YELLOW);
        dbLabel.setFont(Font.font("Monospaced", FontWeight.NORMAL, 12));

        Label warningLabel = new Label("Verify data before flight.");
        warningLabel.setTextFill(Color.LIGHTGRAY);
        warningLabel.setFont(Font.font("SansSerif", 12));

        Label countdownLabel = new Label("Automatically starting in 10...");
        countdownLabel.setTextFill(Color.CYAN);
        countdownLabel.setFont(Font.font("Monospaced", 14));

        Button continueBtn = createStyledButton("CONTINUE");
        continueBtn.setPrefWidth(200);
        continueBtn.setPrefHeight(50);

        bootOverlay.getChildren().addAll(titleLabel, dbLabel, warningLabel, countdownLabel, continueBtn);

        final int[] secondsLeft = {10};
        Timeline startupTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            secondsLeft[0]--;
            countdownLabel.setText("Automatically starting in " + secondsLeft[0] + "...");
            if (secondsLeft[0] <= 0) {
                root.getChildren().remove(bootOverlay);
            }
        }));
        startupTimer.setCycleCount(10);
        startupTimer.play();

        continueBtn.setOnAction(e -> {
            startupTimer.stop();
            root.getChildren().remove(bootOverlay);
        });

        return bootOverlay;
    }

    private void pollNexradData() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    String directory = os.contains("win") ? "C:\\Temp\\" : "/dev/shm/";

                    File imgFile = new File(directory + "latest_nexrad.png");
                    File jsonFile = new File(directory + "nexrad_bounds.json");

                    if (imgFile.exists() && jsonFile.exists()) {
                        long currentModifiedTime = imgFile.lastModified();

                        if (currentModifiedTime > lastLoadedNexradFileTime) {
                            String jsonContent = Files.readString(jsonFile.toPath());
                            NexradBounds bounds = gson.fromJson(jsonContent, NexradBounds.class);

                            javafx.scene.image.Image newImage = new javafx.scene.image.Image(imgFile.toURI().toString());

                            nexradNorth = bounds.north;
                            nexradSouth = bounds.south;
                            nexradEast = bounds.east;
                            nexradWest = bounds.west;
                            currentNexradImage = newImage;

                            lastLoadedNexradFileTime = currentModifiedTime;
                            lastNexradTimeMs = System.currentTimeMillis();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to read NEXRAD from RAM Disk: " + e.getMessage());
                }
            }
        }, 0, 2000);
    }

    private HBox createTouchControlBar() {
        HBox box = new HBox(4);
        box.setAlignment(Pos.TOP_LEFT);

        Button btnZoomIn = createStyledButton("+");
        Button btnZoomOut = createStyledButton("-");

        btnZoomIn.setOnAction(e -> {
            if (currentRangeNM == 5) currentRangeNM = 10;
            else if (currentRangeNM == 10) currentRangeNM = 25;
            else if (currentRangeNM == 25) currentRangeNM = 50;
            else if (currentRangeNM == 50) currentRangeNM = 100;
        });

        btnZoomOut.setOnAction(e -> {
            if (currentRangeNM == 100) currentRangeNM = 50;
            else if (currentRangeNM == 50) currentRangeNM = 25;
            else if (currentRangeNM == 25) currentRangeNM = 10;
            else if (currentRangeNM == 10) currentRangeNM = 5;
        });

        box.getChildren().addAll(btnZoomIn, btnZoomOut);
        return box;
    }

    private Button createStyledButton(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: #222222; -fx-text-fill: white; -fx-border-color: #444444; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 12;");
        return btn;
    }

    private void renderRadar() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        double originX = width / 2.0;
        double originY = height / 2.0 + 60; // Moves ownship up, away from bottom curve

        double availableVerticalSpace = height - 80;
        double pixelsPerNM = availableVerticalSpace / (double) currentRangeNM;

        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, width, height);

        gc.setStroke(javafx.scene.paint.Color.rgb(60, 75, 60));
        gc.setLineWidth(1.5);
        gc.setLineDashes(null);

        boolean validHeading = currentHeading >= 0.0 && currentHeading <= 360.0;
        double activeHeading = validHeading ? currentHeading : 0.0;

        if (currentLatitude != 0.0 && currentLongitude != 0.0) {
            for (StateBorder border : stateBorders) {
                double[] xPoints = new double[border.lats.size()];
                double[] yPoints = new double[border.lons.size()];

                for (int i = 0; i < border.lats.size(); i++) {
                    double[] distAndBearing = NavigationMath.getDistanceAndBearing(currentLatitude, currentLongitude, border.lats.get(i), border.lons.get(i));
                    double distNM = distAndBearing[0];
                    double bearing = distAndBearing[1];

                    double relativeBearing = bearing - activeHeading;
                    double posAngleRad = Math.toRadians(relativeBearing - 90);

                    xPoints[i] = originX + (distNM * pixelsPerNM * Math.cos(posAngleRad));
                    yPoints[i] = originY + (distNM * pixelsPerNM * Math.sin(posAngleRad));
                }

                gc.strokePolygon(xPoints, yPoints, xPoints.length);
            }
        }

        if (currentLatitude != 0.0 && currentLongitude != 0.0 && currentGroundSpeedKnots > 21.7) {
            double cellWidthPixels = 1.5 * pixelsPerNM;

            for (ElevationCell cell : activeTerrain) {
                double altDifference = currentAltitude - cell.elevFt;

                if (altDifference <= 100) {
                    gc.setFill(Color.rgb(200, 0, 0, 0.7));
                } else if (altDifference > 100 && altDifference <= 1000) {
                    gc.setFill(Color.rgb(200, 200, 0, 0.7));
                } else {
                    continue;
                }

                double[] distAndBearing = NavigationMath.getDistanceAndBearing(currentLatitude, currentLongitude, cell.lat, cell.lon);
                double distNM = distAndBearing[0];
                double bearing = distAndBearing[1];

                if (distNM > currentRangeNM) continue;

                double relativeBearing = bearing - activeHeading;
                double posAngleRad = Math.toRadians(relativeBearing - 90);

                double drawX = originX + (distNM * pixelsPerNM * Math.cos(posAngleRad));
                double drawY = originY + (distNM * pixelsPerNM * Math.sin(posAngleRad));

                gc.fillRect(drawX - (cellWidthPixels / 2), drawY - (cellWidthPixels / 2), cellWidthPixels, cellWidthPixels);
            }
        }

        gc.setStroke(Color.DARKGREEN);
        gc.setLineWidth(1.5);

        double halfRange = currentRangeNM / 2.0;
        double radiusHalf = halfRange * pixelsPerNM;
        gc.strokeOval(originX - radiusHalf, originY - radiusHalf, radiusHalf * 2, radiusHalf * 2);

        double radiusFull = currentRangeNM * pixelsPerNM;
        gc.strokeOval(originX - radiusFull, originY - radiusFull, radiusFull * 2, radiusFull * 2);

        gc.setFill(Color.LIGHTGREEN);
        gc.setFont(javafx.scene.text.Font.font("SansSerif", 10));
        gc.fillText(String.format("%.1f NM", halfRange), originX + 5, originY - radiusHalf + 12);
        gc.fillText(String.format("%d NM", currentRangeNM), originX + 5, originY - radiusFull + 12);

        for (AirspacePolygon airspace : activeAirspaces) {
            if (airspace.vertices.size() < 3) continue;

            int nPoints = airspace.vertices.size();
            double[] xPoints = new double[nPoints];
            double[] yPoints = new double[nPoints];
            boolean allPointsValid = true;

            for (int i = 0; i < nPoints; i++) {
                double[] latLon = airspace.vertices.get(i);
                double[] distAndBearing = NavigationMath.getDistanceAndBearing(currentLatitude, currentLongitude, latLon[0], latLon[1]);
                double distNM = distAndBearing[0];
                double bearing = distAndBearing[1];

                if (distNM > (currentRangeNM * 1.5)) {
                    allPointsValid = false;
                    break;
                }

                double relativeBearing = bearing - activeHeading;
                double posAngleRad = Math.toRadians(relativeBearing - 90);

                xPoints[i] = originX + (distNM * pixelsPerNM * Math.cos(posAngleRad));
                yPoints[i] = originY + (distNM * pixelsPerNM * Math.sin(posAngleRad));
            }

            if (allPointsValid) {
                if (airspace.classType != null) {
                    switch (airspace.classType) {
                        case "B":
                            gc.setStroke(Color.BLUE);
                            gc.setLineWidth(2.0);
                            gc.setLineDashes(null);
                            break;
                        case "C":
                            gc.setStroke(Color.MAGENTA);
                            gc.setLineWidth(2.0);
                            gc.setLineDashes(null);
                            break;
                        case "D":
                            gc.setStroke(Color.BLUE);
                            gc.setLineWidth(1.5);
                            gc.setLineDashes(8, 6);
                            break;
                        default:
                            gc.setStroke(Color.MAGENTA);
                            gc.setLineWidth(1.5);
                            gc.setLineDashes(8, 6);
                            break;
                    }
                }
                gc.strokePolygon(xPoints, yPoints, nPoints);
            }
        }

        gc.setLineDashes(null);

        gc.setFont(javafx.scene.text.Font.font("SansSerif", 10));

        for (NavigationWaypoint wp : activeAirports) {
            double[] distAndBearing = NavigationMath.getDistanceAndBearing(currentLatitude, currentLongitude, wp.latitude, wp.longitude);
            double distNM = distAndBearing[0];
            double bearing = distAndBearing[1];

            if (distNM <= currentRangeNM && distNM > 0.1) {
                double relativeBearing = bearing - activeHeading;
                double posAngleRad = Math.toRadians(relativeBearing - 90);

                double targetX = originX + (distNM * pixelsPerNM * Math.cos(posAngleRad));
                double targetY = originY + (distNM * pixelsPerNM * Math.sin(posAngleRad));

                if (wp.isTowered) {
                    gc.setStroke(Color.CORNFLOWERBLUE);
                    gc.setFill(Color.CORNFLOWERBLUE);
                } else {
                    gc.setStroke(Color.MAGENTA);
                    gc.setFill(Color.MAGENTA);
                }

                gc.strokeOval(targetX - 3, targetY - 3, 6, 6);
                gc.fillText(wp.identifier, targetX + 5, targetY + 3);
            }
        }

        // --- Draw ADSB NEXRAD (Priority 2) ---
        if (currentNexradImage != null && currentLatitude != 0.0) {
            double[] nwDistBearing = NavigationMath.getDistanceAndBearing(currentLatitude, currentLongitude, nexradNorth, nexradWest);
            double nwDistNM = nwDistBearing[0];
            double nwBearing = nwDistBearing[1];

            double nwRelativeBearing = nwBearing - activeHeading;
            double nwAngleRad = Math.toRadians(nwRelativeBearing - 90);

            double mappedX = originX + (nwDistNM * pixelsPerNM * Math.cos(nwAngleRad));
            double mappedY = originY + (nwDistNM * pixelsPerNM * Math.sin(nwAngleRad));

            double[] seDistBearing = NavigationMath.getDistanceAndBearing(nexradNorth, nexradWest, nexradSouth, nexradEast);
            double totalBlockWidthNM = Math.abs(nexradEast - nexradWest) * 60.0;
            double totalBlockHeightNM = Math.abs(nexradNorth - nexradSouth) * 60.0;

            double mappedWidth = totalBlockWidthNM * pixelsPerNM;
            double mappedHeight = totalBlockHeightNM * pixelsPerNM;

            gc.save();
            gc.translate(mappedX, mappedY);
            gc.rotate(-activeHeading);

            gc.drawImage(currentNexradImage, 0, 0, mappedWidth, mappedHeight);

            gc.restore();
        }

        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);

        for (TrafficTarget target : trafficMap.values()) {
            double distNM = target.getDistanceNM();
            if (distNM > currentRangeNM || distNM <= 0.1) continue;

            double relativeBearing = target.bearing - activeHeading;
            double posAngleRad = Math.toRadians(relativeBearing - 90);
            double targetX = originX + (distNM * pixelsPerNM * Math.cos(posAngleRad));
            double targetY = originY + (distNM * pixelsPerNM * Math.sin(posAngleRad));

            double relativeTrack = target.track - activeHeading;
            double trackAngleRad = Math.toRadians(relativeTrack - 90);
            double vectorLength = 25;
            double vectorEndX = targetX + (vectorLength * Math.cos(trackAngleRad));
            double vectorEndY = targetY + (vectorLength * Math.sin(trackAngleRad));
            gc.strokeLine(targetX, targetY, vectorEndX, vectorEndY);

            gc.strokePolygon(
                    new double[]{targetX, targetX + 6, targetX, targetX - 6},
                    new double[]{targetY - 6, targetY, targetY + 6, targetY},
                    4
            );

            int relativeAltHundreds = (int) Math.round((target.altitude - currentAltitude) / 100.0);
            String altLabel = (relativeAltHundreds >= 0 ? "+" : "") + relativeAltHundreds;

            gc.setFill(Color.WHITE);
            gc.setFont(javafx.scene.text.Font.font("SansSerif", javafx.scene.text.FontWeight.BOLD, 12));
            gc.fillText(altLabel, targetX - 10, targetY - 10);
        }

        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        gc.strokeLine(originX, originY - 12, originX, originY + 12);
        gc.strokeLine(originX - 12, originY - 2, originX + 12, originY - 2);
        gc.strokeLine(originX - 5, originY + 9, originX + 5, originY + 9);

        // --- Draw Telemetry Displays ---
        gc.setFill(Color.WHITE);
        gc.setFont(javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 22));

        // Track at Top-Center
        String headingText = validHeading ? String.format("TRK: %03d\u00B0", (int) currentHeading) : "TRK: ---\u00B0";
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.fillText(headingText, width / 2.0, 30);

        // Altitude at Top-Right
        gc.setFont(javafx.scene.text.Font.font("Monospaced", FontWeight.BOLD, 18));
        gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
        int flightLevel = (int) (currentAltitude / 100);
        String altText = String.format("FL%03d", Math.max(0, flightLevel));
        gc.fillText(altText, width - 10, 30);

        // --- Draw Status Indicators ---
        gc.setFont(javafx.scene.text.Font.font("SansSerif", javafx.scene.text.FontWeight.BOLD, 14));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);

        if (lastTrafficTimeMs == 0 || (System.currentTimeMillis() - lastTrafficTimeMs) > 15000) {
            gc.setFill(Color.RED);
            gc.fillText("ADSB", width / 2.0 - 40, height - 20);
        } else {
            gc.setFill(Color.GREEN);
            gc.fillText("ADSB", width / 2.0 - 40, height - 20);
        }

        if (lastNexradTimeMs == 0) {
            gc.setFill(Color.RED);
            gc.fillText("NEXRAD", width / 2.0 + 40, height - 20);
        } else {
            gc.setFill(Color.GREEN);
            long minutesAgo = (System.currentTimeMillis() - lastNexradTimeMs) / 60000;
            gc.fillText(String.format("NEXRAD -%d", minutesAgo), width / 2.0 + 40, height - 20);
        }
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
    }

    private void connectToStratux() {
        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://192.168.10.1/traffic"), this)
                .thenAccept(webSocket -> this.stratuxWebSocket = webSocket)
                .exceptionally(ex -> {
                    System.err.println("Failed to connect: " + ex.getMessage());
                    return null;
                });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("WebSocket Connection Opened.");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);

        if (last) {
            try {
                TrafficTarget target = gson.fromJson(messageBuffer.toString(), TrafficTarget.class);
                if (target != null && target.icao != 0) {
                    trafficMap.put(target.icao, target);
                    lastTrafficTimeMs = System.currentTimeMillis();
                }
            } catch (Exception e) {
                System.err.println("Failed to parse JSON: " + e.getMessage());
            }
            messageBuffer.setLength(0);
        }

        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("WebSocket Error: " + error.getMessage());
    }

    private void pollSituationData() {
        HttpClient client = HttpClient.newHttpClient();
        Timer timer = new Timer(true);

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://192.168.10.1/getSituation"))
                            .GET()
                            .build();

                    client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenAccept(response -> {
                                try {
                                    OwnshipSituation situation = gson.fromJson(response.body(), OwnshipSituation.class);
                                    if (situation != null) {
                                        currentHeading = situation.ahrsGyroHeading;
                                        currentAltitude = situation.gpsAltitudeMsl;
                                        currentLatitude = situation.gpsLatitude;
                                        currentLongitude = situation.gpsLongitude;
                                        currentGroundSpeedKnots = situation.gpsGroundSpeed;

                                        if (currentLatitude != 0.0 && currentLongitude != 0.0) {
                                            double delta = Math.max(0.3, (currentRangeNM / 60.0) * 1.5);
                                            activeAirports = dbManager.getNearbyAirports(
                                                    currentLatitude - delta, currentLatitude + delta,
                                                    currentLongitude - delta, currentLongitude + delta
                                            );
                                            activeAirspaces = dbManager.getNearbyAirspaces(
                                                    currentLatitude - delta, currentLatitude + delta,
                                                    currentLongitude - delta, currentLongitude + delta
                                            );

                                            activeTerrain = dbManager.getNearbyTerrain(
                                                    currentLatitude - delta, currentLatitude + delta,
                                                    currentLongitude - delta, currentLongitude + delta
                                            );
                                        }

                                    }
                                } catch (Exception e) {
                                    // Ignore parsing errors on empty frames
                                }
                            });
                } catch (Exception e) {
                    System.err.println("Situation poll failed: " + e.getMessage());
                }
            }
        }, 0, 500);
    }

    private void loadStateBorders() {
        String dbUrl = "jdbc:sqlite:usa_nav.db";
        String query = "SELECT state_id, lat, lon FROM state_border_vertices ORDER BY state_id, seq";

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(dbUrl);
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(query)) {

            String currentStateId = "";
            List<Double> lats = new ArrayList<>();
            List<Double> lons = new ArrayList<>();

            while (rs.next()) {
                String id = rs.getString("state_id");
                if (!id.equals(currentStateId)) {
                    if (!lats.isEmpty()) {
                        stateBorders.add(new StateBorder(currentStateId, lats, lons));
                        lats = new ArrayList<>();
                        lons = new ArrayList<>();
                    }
                    currentStateId = id;
                }
                lats.add(rs.getDouble("lat"));
                lons.add(rs.getDouble("lon"));
            }
            if (!lats.isEmpty()) {
                stateBorders.add(new StateBorder(currentStateId, lats, lons));
            }
            System.out.println("Loaded " + stateBorders.size() + " state borders into memory.");
        } catch (Exception e) {
            System.err.println("Failed to load borders: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}