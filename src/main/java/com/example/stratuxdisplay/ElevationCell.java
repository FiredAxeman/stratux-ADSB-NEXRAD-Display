package com.example.stratuxdisplay;

public class ElevationCell {
    public double lat;
    public double lon;
    public int elevFt;

    public ElevationCell(double lat, double lon, int elevFt) {
        this.lat = lat;
        this.lon = lon;
        this.elevFt = elevFt;
    }
}