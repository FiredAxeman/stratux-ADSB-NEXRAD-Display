package com.example.stratuxdisplay;

public class NavigationMath {
    /**
     * Calculates Haversine distance and initial bearing between two geographic coordinates.
     * @return double array where [0] is Distance in NM, and [1] is Bearing in degrees.
     */
    public static double[] getDistanceAndBearing(double lat1, double lon1, double lat2, double lon2) {
        double R = 3440.065; // Radius of Earth in Nautical Miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(rLat1) * Math.cos(rLat2) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distanceNM = R * c;

        double y = Math.sin(dLon) * Math.cos(rLat2);
        double x = Math.cos(rLat1) * Math.sin(rLat2) -
                Math.sin(rLat1) * Math.cos(rLat2) * Math.cos(dLon);
        double bearing = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;

        return new double[]{distanceNM, bearing};
    }
}