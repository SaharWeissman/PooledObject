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

    public Guitar(Guitar objToClone){
        this.mManufacturer = objToClone.mManufacturer;
        this.mYear = objToClone.mYear;
        this.mColor = objToClone.mColor;
        this.mIsPlaying = objToClone.mIsPlaying;
    }

    public Guitar(String manufacturer, String year, String color, boolean isPlaying){
        this.mManufacturer = manufacturer;
        this.mYear = year;
        this.mColor = color;
        this.mIsPlaying = isPlaying;
        Pooled_Guitar pooled_guitar = new Pooled_Guitar(this);
    }

    public boolean isValid(){
        return mIsPlaying;
    }

    public void expire(){
        this.mIsPlaying = false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
//        this.mManufacturer = manufacturer;
//        this.mYear = year;
//        this.mColor = color;
//        this.mIsPlaying = isPlaying;
        sb.append("Guitar: {manu.:" + mManufacturer + ", year:" + mYear + ", color:" + mColor + ", isPlaying:" + mIsPlaying + "}");
        return sb.toString();
    }
}
