package com.saharw.pooledobject;

import com.saharw.pooledobject.entities.PooledObject;

/**
 * Created by Sahar on 01/27/2017.
 */

public interface PooledTypeAdapter<T> {
    T fromObject(Object in);

    void toPooled(T value,  PooledObject<T> dest);
}
