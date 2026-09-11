package com.example.stratuxdisplay;

import java.util.List;

public class StateBorder {
    public String id;
    public List<Double> lats;
    public List<Double> lons;

    public StateBorder(String id, List<Double> lats, List<Double> lons) {
        this.id = id;
        this.lats = lats;
        this.lons = lons;
    }
}
