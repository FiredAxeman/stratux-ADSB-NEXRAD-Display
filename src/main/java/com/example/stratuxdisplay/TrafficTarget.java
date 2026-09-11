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
}