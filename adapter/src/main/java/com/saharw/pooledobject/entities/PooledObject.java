package com.saharw.pooledobject.entities;

/**
 * Created by Sahar on 02/03/2017.
 */

public class PooledObject<T> {
    private final T mRealObj;
    public PooledObject(T object){
        this.mRealObj = object;
    }

    public T getObject(){
        return this.mRealObj;
    }
}
