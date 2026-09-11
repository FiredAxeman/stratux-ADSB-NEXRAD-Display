package com.example.stratuxdisplay;

import com.google.gson.annotations.SerializedName;

public class TrafficTarget {
    @SerializedName("Icao_addr")
    public int icao;

    @SerializedName("Tail")
    public String tail;

    @SerializedName("Alt")
    public int altitude;

    @SerializedName("Distance")
    public double distanceMeters;

    @SerializedName("Bearing")
    public double bearing;

    public double getDistanceNM() {
        // 1 Nautical Mile = 1852 meters
        return distanceMeters / 1852.0;
    }
    @SerializedName("Track")
    public double track;

    @SerializedName("Position_valid")
    public Boolean positionValid;

    @SerializedName("Age")
    public double ageSeconds;

    public boolean hasUsablePosition() {
        return positionValid == null || positionValid;
    }

    public boolean isValid() {
        return icao != 0
                && Double.isFinite(distanceMeters)
                && distanceMeters >= 0.0
                && Double.isFinite(bearing)
                && Double.isFinite(track);
    }
}