package com.example.stratuxdisplay;

public class NavigationWaypoint {
    public String identifier;
    public double latitude;
    public double longitude;
    public boolean isTowered;

    public NavigationWaypoint(String identifier, double latitude, double longitude, boolean isTowered) {
        this.identifier = identifier;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isTowered = isTowered;
    }
}