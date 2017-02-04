package com.saharw.pooledobject.model;

import com.saharw.pooledobject.Pooled;

/**
 * Created by Sahar on 01/27/2017.
 */
@Pooled
public class Guitar {

    public String mManufacturer;
    public String mYear;
    public String mColor;
    public boolean mIsPlaying;

    public Guitar(){}

    public Guitar(String manufacturer, String year, String color, boolean isPlaying){
        this.mManufacturer = manufacturer;
        this.mYear = year;
        this.mColor = color;
        this.mIsPlaying = isPlaying;
        Pooled_Guitar pooled_guitar = new Pooled_Guitar(this);
    }
}
