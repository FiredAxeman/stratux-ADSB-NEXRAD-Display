package com.example.stratuxdisplay;

import com.google.gson.annotations.SerializedName;

public class OwnshipSituation {
    @SerializedName("AHRSGyroHeading")
    public double ahrsGyroHeading;

    @SerializedName("GPSAltitudeMSL")
    public double gpsAltitudeMsl;

    @SerializedName("GPSLatitude")
    public double gpsLatitude;

    @SerializedName("GPSLongitude")
    public double gpsLongitude;
    public double gpsGroundSpeed;
}