package com.example.stratuxdisplay;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.nio.file.Files;

public class StratuxDisplayApplication extends Application implements WebSocket.Listener {

    private WebSocket stratuxWebSocket;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<Integer, TrafficTarget> trafficMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> trafficReceivedAtMs = new ConcurrentHashMap<>();
    private final StringBuilder messageBuffer = new StringBuilder();

    // Ownship Telemetry
    private volatile double currentHeading = 0.0;
    private volatile double currentAltitude = 0.0;
    private volatile double currentLatitude = 0.0;
    private volatile double currentLongitude = 0.0;

    // Range & Zoom Control (Default 10 NM, Max 100 NM)
    private volatile int currentRangeNM = 10;

    // Database and Map Data
    private final NavDatabaseManager dbManager = new NavDatabaseManager();
    private volatile List<NavigationWaypoint> activeAirports = new ArrayList<>();
    private volatile List<AirspacePolygon> activeAirspaces = new ArrayList<>();
    private volatile List<GroundFeature> activeRoads = new ArrayList<>();
    private volatile List<GroundFeature> activeWater = new ArrayList<>();
    private final List<StateBorder> stateBorders = new ArrayList<>();
    private volatile double lastFeatureQueryLat = Double.NaN;
    private volatile double lastFeatureQueryLon = Double.NaN;
    private volatile long lastFeatureQueryMs = 0;

    // Status & NEXRAD Data
    private volatile javafx.scene.image.Image currentNexradImage = null;
    private volatile long lastNexradTimeMs = 0;
    private volatile long lastTrafficTimeMs = 0;
    private volatile long lastTrafficMessageTimeMs = 0;
    private volatile long lastSituationTimeMs = 0;
    private volatile long trafficMessageCount = 0;
    private volatile boolean trafficSocketConnected = false;
    private volatile String trafficError = "";
    private volatile boolean reconnectScheduled = false;
    private volatile boolean shuttingDown = false;
    private volatile long lastLoadedNexradFileTime = 0;
    private volatile boolean nexradDataValid = false;

    private static final long NEXRAD_DATA_TIMEOUT_MS = 15 * 60 * 1000;
    private static final long TRAFFIC_DATA_TIMEOUT_MS = 15 * 1000;
    private static final double STATE_BORDER_RADIUS_NM = 100.0 / 1.15078;

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
        public boolean valid;
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
        StackPane.setAlignment(controlBar, Pos.TOP_RIGHT);
        StackPane.setMargin(controlBar, new Insets(58, 12, 0, 0));

        VBox bootScreen = createBootScreen(root);
        root.getChildren().add(bootScreen);

        // Lock the Scene to 480x480
        Scene scene = new Scene(root, 480, 480);

        // Remove standard desktop window borders for embedded hardware deployment
        primaryStage.initStyle(javafx.stage.StageStyle.UNDECORATED);

        primaryStage.setTitle("Stratux HUD");
        primaryStage.setScene(scene);

        primaryStage.setOnCloseRequest(event -> {
            shuttingDown = true;
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

                            lastLoadedNexradFileTime = currentModifiedTime;

                            if (isValidNexradData(bounds, newImage)) {
                                nexradNorth = bounds.north;
                                nexradSouth = bounds.south;
                                nexradEast = bounds.east;
                                nexradWest = bounds.west;
                                currentNexradImage = newImage;
                                nexradDataValid = true;
                                lastNexradTimeMs = currentModifiedTime;
                            } else {
                                nexradDataValid = false;
                                currentNexradImage = null;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to read NEXRAD from RAM Disk: " + e.getMessage());
                }
            }
        }, 0, 2000);
    }

    private boolean isValidNexradData(NexradBounds bounds, javafx.scene.image.Image image) {
        return bounds != null
                && bounds.valid
                && Double.isFinite(bounds.north)
                && Double.isFinite(bounds.south)
                && Double.isFinite(bounds.east)
                && Double.isFinite(bounds.west)
                && bounds.north > bounds.south
                && bounds.east > bounds.west
                && image != null
                && image.getWidth() > 0
                && image.getHeight() > 0;
    }

    private boolean hasFreshValidNexradData() {
        return nexradDataValid
                && currentNexradImage != null
                && lastNexradTimeMs > 0
                && (System.currentTimeMillis() - lastNexradTimeMs) <= NEXRAD_DATA_TIMEOUT_MS;
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
        btn.setStyle("-fx-background-color: #172b3a; -fx-text-fill: #d8f4ff; "
                + "-fx-border-color: #4a8da8; -fx-border-width: 1; "
                + "-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 5 11;");
        return btn;
    }

    private void renderRadar() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        double originX = width / 2.0;
        double originY = height / 2.0;
        double radarRadius = 205;
        double pixelsPerNM = radarRadius / currentRangeNM;

        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, width, height);

        // Keep every foreground element inside the physical circular display.
        gc.save();
        gc.beginPath();
        gc.arc(originX, originY, width / 2.0, height / 2.0, 0, 360);
        gc.closePath();
        gc.clip();

        boolean validHeading = currentHeading >= 0.0 && currentHeading <= 360.0;
        double activeHeading = validHeading ? currentHeading : 0.0;

        gc.setFill(Color.BLACK);
        gc.fillOval(originX - radarRadius - 4, originY - radarRadius - 4,
                (radarRadius + 4) * 2, (radarRadius + 4) * 2);
        gc.setStroke(Color.rgb(100, 100, 100));
        gc.setLineWidth(1.5);
        gc.strokeOval(originX - radarRadius - 4, originY - radarRadius - 4,
                (radarRadius + 4) * 2, (radarRadius + 4) * 2);

        gc.save();
        gc.beginPath();
        gc.arc(originX, originY, radarRadius, radarRadius, 0, 360);
        gc.closePath();
        gc.clip();

        if (currentLatitude != 0.0 && currentLongitude != 0.0) {
            drawGroundFeatures(gc, activeWater, originX, originY, pixelsPerNM,
                    activeHeading, true);

            drawStateBorders(gc, originX, originY, pixelsPerNM, activeHeading);
        }

        drawRadarGrid(gc, originX, originY, radarRadius, activeHeading);

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
                            gc.setStroke(Color.rgb(45, 90, 180));
                            gc.setLineWidth(2.0);
                            gc.setLineDashes(null);
                            break;
                        case "C":
                            gc.setStroke(Color.rgb(180, 45, 180));
                            gc.setLineWidth(2.0);
                            gc.setLineDashes(null);
                            break;
                        case "D":
                            gc.setStroke(Color.rgb(45, 90, 180));
                            gc.setLineWidth(1.5);
                            gc.setLineDashes(8, 6);
                            break;
                        default:
                            gc.setStroke(Color.rgb(180, 45, 180));
                            gc.setLineWidth(1.5);
                            gc.setLineDashes(8, 6);
                            break;
                    }
                }
                gc.strokePolygon(xPoints, yPoints, nPoints);
            }
        }

        gc.setLineDashes(null);

        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 10));

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
                    gc.setStroke(Color.rgb(80, 160, 190));
                    gc.setFill(Color.rgb(80, 160, 190));
                } else {
                    gc.setStroke(Color.rgb(180, 75, 180));
                    gc.setFill(Color.rgb(180, 75, 180));
                }

                gc.fillOval(targetX - 3, targetY - 3, 6, 6);
                gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 9));
                gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
                gc.fillText(wp.identifier, targetX, targetY - 8);
                gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
            }
        }

        // --- Draw ADSB NEXRAD (Priority 2) ---
        if (hasFreshValidNexradData() && currentLatitude != 0.0) {
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

        expireStaleTraffic();

        List<TrafficTarget> visibleTraffic = new ArrayList<>(trafficMap.values());
        visibleTraffic.sort(Comparator.comparingDouble(TrafficTarget::getDistanceNM));
        int labelsDrawn = 0;
        int highestTrafficAlert = 0;

        for (TrafficTarget target : visibleTraffic) {
            if (!target.hasUsablePosition()) continue;
            double distNM = target.getDistanceNM();
            if (distNM > currentRangeNM || distNM <= 0.1) continue;

            double relativeBearing = target.bearing - activeHeading;
            double posAngleRad = Math.toRadians(relativeBearing - 90);
            double targetX = originX + (distNM * pixelsPerNM * Math.cos(posAngleRad));
            double targetY = originY + (distNM * pixelsPerNM * Math.sin(posAngleRad));

            double relativeAltitudeFeet = target.altitude - currentAltitude;
            String altLabel = String.format(Locale.US, "%+.1f", relativeAltitudeFeet / 1000.0);
            int trafficAlert = getTrafficAlertLevel(distNM, relativeAltitudeFeet);
            highestTrafficAlert = Math.max(highestTrafficAlert, trafficAlert);
            Color trafficColor = trafficAlert == 2
                    ? Color.RED
                    : trafficAlert == 1 ? Color.YELLOW : Color.rgb(190, 220, 220);
            gc.setStroke(trafficColor);
            gc.setFill(trafficColor);
            gc.setLineWidth(2.0);

            double relativeTrack = target.track - activeHeading;
            double trackAngleRad = Math.toRadians(relativeTrack - 90);
            double vectorLength = 20;
            gc.strokeLine(targetX, targetY,
                    targetX + vectorLength * Math.cos(trackAngleRad),
                    targetY + vectorLength * Math.sin(trackAngleRad));
            gc.strokePolygon(
                    new double[]{targetX, targetX + 6, targetX, targetX - 6},
                    new double[]{targetY - 6, targetY, targetY + 6, targetY},
                    4
            );

            double[] labelPosition = getTrafficLabelPosition(
                    targetX, targetY, trackAngleRad, originX, originY, radarRadius, altLabel);
            gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
            gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 10));
            gc.fillText(altLabel, labelPosition[0], labelPosition[1]);

            boolean closeEnoughForTail = trafficAlert > 0
                    || distNM <= Math.min(10.0, currentRangeNM * 0.45);
            if (closeEnoughForTail && labelsDrawn < 8
                    && target.tail != null && !target.tail.isEmpty()) {
                gc.setFont(Font.font("Monospaced", FontWeight.NORMAL, 9));
                gc.fillText(target.tail, labelPosition[0], labelPosition[1] + 11);
                labelsDrawn++;
            }
        }

        drawOwnshipSymbol(gc, originX, originY);
        gc.restore();

        // Bottom status strip doubles as a compact data-link diagnostic.
        gc.setFill(Color.BLACK);
        gc.fillRect(52, 424, width - 104, 21);
        gc.setStroke(Color.rgb(80, 80, 80));
        gc.strokeRect(52, 424, width - 104, 21);
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 11));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);

        long now = System.currentTimeMillis();
        boolean trafficFresh = lastTrafficMessageTimeMs > 0
                && (System.currentTimeMillis() - lastTrafficMessageTimeMs) <= TRAFFIC_DATA_TIMEOUT_MS;
        boolean situationFresh = lastSituationTimeMs > 0 && now - lastSituationTimeMs <= 2000;
        boolean gpsHealthy = situationFresh && currentLatitude != 0.0 && currentLongitude != 0.0;
        gc.setFill(gpsHealthy ? Color.LIMEGREEN : Color.RED);
        gc.fillText("GPS", width / 2.0 - 125, 439);
        Color adsbStatusColor = !trafficSocketConnected || !trafficFresh
                ? Color.RED
                : highestTrafficAlert == 2 ? Color.RED
                : highestTrafficAlert == 1 ? Color.YELLOW : Color.LIMEGREEN;
        gc.setFill(adsbStatusColor);
        gc.fillText("ADSB", width / 2.0, 439);
        gc.setFill(!hasFreshValidNexradData()
                ? Color.RED : Color.LIMEGREEN);
        gc.fillText("WX", width / 2.0 + 125, 439);
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        gc.restore();
    }

    private void drawGroundFeatures(GraphicsContext gc, List<GroundFeature> features,
                                    double originX, double originY, double pixelsPerNM,
                                    double activeHeading, boolean water) {
        int renderedFeatures = 0;
        int maxFeatures = currentRangeNM >= 50 ? 3000 : currentRangeNM >= 25 ? 8000 : 15000;

        for (GroundFeature feature : features) {
            if (renderedFeatures >= maxFeatures) {
                break;
            }
            if (feature.vertices.size() < (water ? 3 : 2)) {
                continue;
            }

            boolean majorRoad = !water && feature.type != null
                    && (feature.type.equalsIgnoreCase("motorway")
                    || feature.type.equalsIgnoreCase("trunk")
                    || feature.type.equalsIgnoreCase("primary"));
            if (!water && currentRangeNM >= 50 && !majorRoad) {
                continue;
            }

            double[] xPoints = new double[feature.vertices.size()];
            double[] yPoints = new double[feature.vertices.size()];
            int visiblePoints = 0;
            boolean hasLargeGap = false;
            for (int i = 0; i < feature.vertices.size(); i++) {
                double[] vertex = feature.vertices.get(i);
                if (vertex == null || vertex.length < 2
                        || !Double.isFinite(vertex[0]) || !Double.isFinite(vertex[1])
                        || vertex[0] < -90 || vertex[0] > 90
                        || vertex[1] < -180 || vertex[1] > 180) {
                    hasLargeGap = true;
                    continue;
                }

                double[] distanceAndBearing = NavigationMath.getDistanceAndBearing(
                        currentLatitude, currentLongitude, vertex[0], vertex[1]);
                if (!Double.isFinite(distanceAndBearing[0])
                        || !Double.isFinite(distanceAndBearing[1])
                        || distanceAndBearing[0] > currentRangeNM * 1.25) {
                    hasLargeGap = true;
                    continue;
                }

                if (visiblePoints > 0) {
                    double[] previous = feature.vertices.get(i - 1);
                    if (previous == null || previous.length < 2
                            || !Double.isFinite(previous[0]) || !Double.isFinite(previous[1])
                            || NavigationMath.getDistanceAndBearing(
                            previous[0], previous[1], vertex[0], vertex[1])[0]
                            > Math.max(5.0, currentRangeNM * 0.25)) {
                        hasLargeGap = true;
                        if (!water && visiblePoints >= 2) {
                            gc.strokePolyline(xPoints, yPoints, visiblePoints);
                        }
                        visiblePoints = 0;
                    }
                }

                double angle = Math.toRadians(
                        distanceAndBearing[1] - activeHeading - 90);
                xPoints[i] = originX + distanceAndBearing[0] * pixelsPerNM
                        * Math.cos(angle);
                yPoints[i] = originY + distanceAndBearing[0] * pixelsPerNM
                        * Math.sin(angle);
                if (Double.isFinite(xPoints[i]) && Double.isFinite(yPoints[i])) {
                    xPoints[visiblePoints] = xPoints[i];
                    yPoints[visiblePoints] = yPoints[i];
                    visiblePoints++;
                }
            }

            if (water) {
                if (!hasLargeGap && visiblePoints >= 3) {
                    gc.setFill(Color.rgb(20, 55, 95, 0.72));
                    gc.setStroke(Color.rgb(45, 95, 145, 0.85));
                    gc.setLineWidth(1.0);
                    gc.fillPolygon(xPoints, yPoints, visiblePoints);
                    gc.strokePolygon(xPoints, yPoints, visiblePoints);
                    renderedFeatures++;
                }
            } else {
                gc.setStroke(majorRoad
                        ? Color.rgb(135, 110, 70, 0.9)
                        : Color.rgb(75, 75, 75, 0.8));
                gc.setLineWidth(majorRoad ? 1.5 : 0.7);
                if (visiblePoints >= 2) {
                    gc.strokePolyline(xPoints, yPoints, visiblePoints);
                    renderedFeatures++;
                }
            }
        }
    }

    private void drawStateBorders(GraphicsContext gc, double originX, double originY,
                          double pixelsPerNM, double activeHeading) {
        gc.setStroke(Color.rgb(135, 135, 135, 0.95));
        gc.setLineWidth(1.0);
        gc.setLineDashes(null);

        // Include segments that cross the radar edge; the circular canvas clip
        // trims the off-screen portion instead of dropping the whole segment.
        // Border vertices can be widely spaced on straight state lines. They
        // remain safe because each segment is clipped by the radar circle.
        double maxSegmentDistance = 20.0;

        for (StateBorder border : stateBorders) {
            for (int i = 1; i < border.lats.size(); i++) {
            double lat1 = border.lats.get(i - 1);
            double lon1 = border.lons.get(i - 1);
            double lat2 = border.lats.get(i);
            double lon2 = border.lons.get(i);

            if (!Double.isFinite(lat1) || !Double.isFinite(lon1)
                    || !Double.isFinite(lat2) || !Double.isFinite(lon2)
                    || lat1 < -90 || lat1 > 90 || lat2 < -90 || lat2 > 90
                    || lon1 < -180 || lon1 > 180 || lon2 < -180 || lon2 > 180) {
                continue;
            }

            double segmentDistance = NavigationMath.getDistanceAndBearing(
                    lat1, lon1, lat2, lon2)[0];
            if (!Double.isFinite(segmentDistance)
                    || segmentDistance > maxSegmentDistance) {
                continue;
            }

            double[] first = NavigationMath.getDistanceAndBearing(
                    currentLatitude, currentLongitude, lat1, lon1);
            double[] second = NavigationMath.getDistanceAndBearing(
                    currentLatitude, currentLongitude, lat2, lon2);
            double midLat = (lat1 + lat2) / 2.0;
            double midLon = (lon1 + lon2) / 2.0;
            double[] midpoint = NavigationMath.getDistanceAndBearing(
                    currentLatitude, currentLongitude, midLat, midLon);
            if (!Double.isFinite(first[0]) || !Double.isFinite(second[0])
                    || !Double.isFinite(midpoint[0])
                    || (first[0] > STATE_BORDER_RADIUS_NM
                    && second[0] > STATE_BORDER_RADIUS_NM
                    && midpoint[0] > STATE_BORDER_RADIUS_NM)) {
                continue;
            }

            double firstAngle = Math.toRadians(first[1] - activeHeading - 90);
            double secondAngle = Math.toRadians(second[1] - activeHeading - 90);
            gc.strokeLine(
                    originX + first[0] * pixelsPerNM * Math.cos(firstAngle),
                    originY + first[0] * pixelsPerNM * Math.sin(firstAngle),
                    originX + second[0] * pixelsPerNM * Math.cos(secondAngle),
                    originY + second[0] * pixelsPerNM * Math.sin(secondAngle));
            }
        }

        gc.setLineDashes(null);
    }

    private double[] getTrafficLabelPosition(double targetX, double targetY,
                                             double trackAngleRad, double originX,
                                             double originY, double radarRadius,
                                             String label) {
        double perpendicularX = -Math.sin(trackAngleRad);
        double perpendicularY = Math.cos(trackAngleRad);
        double labelOffset = 14.0;
        double labelWidth = label.length() * 7.2 + 4.0;

        for (double side : new double[]{1.0, -1.0}) {
            double labelX = targetX + perpendicularX * labelOffset * side;
            double labelY = targetY + perpendicularY * labelOffset * side;
            if (isTrafficLabelInsideRadar(labelX, labelY, labelWidth,
                    originX, originY, radarRadius)) {
                return new double[]{labelX, labelY};
            }
        }

        double labelX = targetX + perpendicularX * labelOffset;
        double labelY = targetY + perpendicularY * labelOffset;
        for (int i = 0; i < 12; i++) {
            if (isTrafficLabelInsideRadar(labelX, labelY, labelWidth,
                    originX, originY, radarRadius)) {
                break;
            }
            labelX += (originX - labelX) * 0.15;
            labelY += (originY - labelY) * 0.15;
        }
        return new double[]{labelX, labelY};
    }

    private boolean isTrafficLabelInsideRadar(double labelX, double labelY,
                                              double labelWidth, double originX,
                                              double originY, double radarRadius) {
        double left = labelX - 2.0;
        double right = labelX + labelWidth;
        double top = labelY - 11.0;
        double bottom = labelY + 3.0;
        double safeRadius = radarRadius - 4.0;

        return Math.hypot(left - originX, top - originY) <= safeRadius
                && Math.hypot(right - originX, top - originY) <= safeRadius
                && Math.hypot(left - originX, bottom - originY) <= safeRadius
                && Math.hypot(right - originX, bottom - originY) <= safeRadius;
    }

    private int getTrafficAlertLevel(double distanceNM, double relativeAltitudeFeet) {
        double verticalSeparation = Math.abs(relativeAltitudeFeet);
        if (distanceNM <= 1.0 && verticalSeparation <= 500.0) {
            return 2;
        }
        if (distanceNM <= 2.5 && verticalSeparation <= 1000.0) {
            return 1;
        }
        return 0;
    }

    private void drawRadarGrid(GraphicsContext gc, double originX, double originY,
                               double radarRadius, double activeHeading) {
        gc.setStroke(Color.rgb(45, 75, 45, 0.85));
        gc.setLineWidth(1.0);
        for (int i = 1; i <= 3; i++) {
            double radius = radarRadius * i / 3.0;
            gc.strokeOval(originX - radius, originY - radius, radius * 2, radius * 2);
        }

        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 9));
        gc.setFill(Color.LIGHTGREEN);
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.fillText(String.format("%d NM", currentRangeNM / 3), originX + 5, originY - radarRadius / 3 + 12);
        gc.fillText(String.format("%d NM", currentRangeNM), originX + 5, originY - radarRadius + 12);

        for (int bearing = 0; bearing < 360; bearing += 45) {
            double angle = Math.toRadians(bearing - activeHeading - 90);
            boolean cardinal = bearing % 90 == 0;
            double inner = radarRadius - (cardinal ? 14 : 8);
            double outer = radarRadius - 1;
            gc.setStroke(cardinal ? Color.LIGHTGREEN : Color.rgb(90, 125, 90));
            gc.setLineWidth(cardinal ? 2.0 : 1.0);
            gc.strokeLine(originX + inner * Math.cos(angle), originY + inner * Math.sin(angle),
                    originX + outer * Math.cos(angle), originY + outer * Math.sin(angle));

            if (cardinal) {
                String label;
                switch (bearing) {
                    case 0:
                        label = "N";
                        break;
                    case 90:
                        label = "E";
                        break;
                    case 180:
                        label = "S";
                        break;
                    default:
                        label = "W";
                        break;
                }
                double labelRadius = radarRadius - 25;
                gc.setFill(Color.LIGHTGREEN);
                gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 13));
                gc.fillText(label,
                        originX + labelRadius * Math.cos(angle),
                        originY + labelRadius * Math.sin(angle) + 4);
            }
        }
    }

    private void drawOwnshipSymbol(GraphicsContext gc, double originX, double originY) {
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2.0);
        gc.strokeLine(originX, originY - 12, originX, originY + 12);
        gc.strokeLine(originX - 12, originY - 2, originX + 12, originY - 2);
        gc.strokeLine(originX - 5, originY + 9, originX + 5, originY + 9);
    }

    private void connectToStratux() {
        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://192.168.10.1/traffic"), this)
                .thenAccept(webSocket -> {
                    this.stratuxWebSocket = webSocket;
                    this.trafficError = "";
                })
                .exceptionally(ex -> {
                    trafficSocketConnected = false;
                    trafficError = "connect: " + ex.getMessage();
                    System.err.println("Failed to connect: " + ex.getMessage());
                    scheduleTrafficReconnect();
                    return null;
                });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("WebSocket Connection Opened.");
        trafficSocketConnected = true;
        trafficError = "";
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (messageBuffer) {
            messageBuffer.append(data);

            if (last) {
                try {
                    long receivedAt = System.currentTimeMillis();
                    JsonElement payload = JsonParser.parseString(messageBuffer.toString());
                    int parsedTargets = parseTrafficPayload(payload, receivedAt);
                    lastTrafficMessageTimeMs = receivedAt;
                    trafficMessageCount++;
                    if (parsedTargets > 0) {
                        lastTrafficTimeMs = receivedAt;
                    }
                    trafficError = "";
                    if (trafficMessageCount == 1 || trafficMessageCount % 30 == 0) {
                        System.out.println("Traffic updates received: " + trafficMessageCount
                                + ", targets in update: " + parsedTargets
                                + ", active targets: " + trafficMap.size());
                    }
                } catch (Exception e) {
                    trafficError = "parse: " + e.getMessage();
                    System.err.println("Failed to parse traffic JSON: " + e.getMessage());
                }
                messageBuffer.setLength(0);
            }
        }

        webSocket.request(1);
        return null;
    }

    private int parseTrafficPayload(JsonElement payload, long receivedAt) {
        int parsedTargets = 0;
        if (payload.isJsonArray()) {
            for (JsonElement element : payload.getAsJsonArray()) {
                parsedTargets += parseTrafficTarget(element, receivedAt);
            }
        } else if (payload.isJsonObject()) {
            if (payload.getAsJsonObject().has("Traffic")
                    && payload.getAsJsonObject().get("Traffic").isJsonArray()) {
                for (JsonElement element : payload.getAsJsonObject().getAsJsonArray("Traffic")) {
                    parsedTargets += parseTrafficTarget(element, receivedAt);
                }
            } else {
                parsedTargets = parseTrafficTarget(payload, receivedAt);
            }
        }
        return parsedTargets;
    }

    private int parseTrafficTarget(JsonElement element, long receivedAt) {
        if (!element.isJsonObject()) {
            return 0;
        }

        TrafficTarget target = gson.fromJson(element, TrafficTarget.class);
        if (target == null || !target.isValid()) {
            return 0;
        }

        trafficMap.put(target.icao, target);
        trafficReceivedAtMs.put(target.icao, receivedAt);
        return 1;
    }

    private void expireStaleTraffic() {
        long now = System.currentTimeMillis();
        trafficReceivedAtMs.forEach((icao, receivedAt) -> {
            if (now - receivedAt > TRAFFIC_DATA_TIMEOUT_MS) {
                trafficReceivedAtMs.remove(icao, receivedAt);
                trafficMap.remove(icao);
            }
        });
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        trafficSocketConnected = false;
        trafficError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        System.err.println("WebSocket Error: " + error.getMessage());
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        trafficSocketConnected = false;
        trafficError = "closed: " + statusCode + " " + reason;
        System.err.println("WebSocket closed: " + statusCode + " " + reason);
        scheduleTrafficReconnect();
        return null;
    }

    private void scheduleTrafficReconnect() {
        if (shuttingDown || reconnectScheduled) {
            return;
        }

        reconnectScheduled = true;
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                reconnectScheduled = false;
                if (!shuttingDown && !trafficSocketConnected) {
                    connectToStratux();
                }
                timer.cancel();
            }
        }, 1000);
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
                                    if (response.statusCode() != 200) {
                                        System.err.println("Situation poll returned HTTP " + response.statusCode());
                                        return;
                                    }
                                    OwnshipSituation situation = gson.fromJson(response.body(), OwnshipSituation.class);
                                    if (situation != null) {
                                        lastSituationTimeMs = System.currentTimeMillis();
                                        currentHeading = situation.ahrsGyroHeading;
                                        currentAltitude = situation.gpsAltitudeMsl;
                                        currentLatitude = situation.gpsLatitude;
                                        currentLongitude = situation.gpsLongitude;
                                        if (currentLatitude != 0.0 && currentLongitude != 0.0
                                                && shouldRefreshFeatureData(currentLatitude, currentLongitude)) {
                                            double delta = Math.max(0.3, (currentRangeNM / 60.0) * 1.5);
                                            activeAirports = dbManager.getNearbyAirports(
                                                    currentLatitude - delta, currentLatitude + delta,
                                                    currentLongitude - delta, currentLongitude + delta
                                            );
                                            activeAirspaces = dbManager.getNearbyAirspaces(
                                                    currentLatitude - delta, currentLatitude + delta,
                                                    currentLongitude - delta, currentLongitude + delta
                                            );
                                            activeRoads = new ArrayList<>();
                                            activeWater = dbManager.getNearbyWater(
                                                    currentLatitude - delta, currentLatitude + delta,
                                                    currentLongitude - delta, currentLongitude + delta
                                            );
                                            lastFeatureQueryLat = currentLatitude;
                                            lastFeatureQueryLon = currentLongitude;
                                            lastFeatureQueryMs = System.currentTimeMillis();
                                            System.out.println("Ground features loaded: roads="
                                                    + activeRoads.size() + ", water=" + activeWater.size());
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

    private boolean shouldRefreshFeatureData(double latitude, double longitude) {
        long now = System.currentTimeMillis();
        if (Double.isNaN(lastFeatureQueryLat) || Double.isNaN(lastFeatureQueryLon)
                || now - lastFeatureQueryMs > 5000) {
            return true;
        }
        return Math.abs(latitude - lastFeatureQueryLat) > 0.02
                || Math.abs(longitude - lastFeatureQueryLon) > 0.02;
    }

    private void loadStateBorders() {
        String dbUrl = "jdbc:sqlite:usa_nav.db";
        String query = "SELECT state_id, lat, lon FROM state_border_vertices ORDER BY state_id, seq";

        stateBorders.clear();
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
            System.out.println("Loaded " + stateBorders.size()
                    + " state border paths into memory.");
        } catch (Exception e) {
            System.err.println("State border load failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}