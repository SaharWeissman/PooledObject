package com.saharw.pooledobject.model;

import com.saharw.pooledobject.PooledObject;

/**
 * Created by Sahar on 02/04/2017.
 */

public class Main {

    public static void main(String[] args){
        PooledObject<Guitar> guitarPool = Pooled_Guitar.pool;
        Guitar gtr1 = guitarPool.create(new Guitar("Gibson", "1969", "Metalic Blue", true));
        System.out.println("gtr1: " + gtr1);
        guitarPool.checkIn(gtr1);
        Guitar gtr2 = guitarPool.checkOut();
        System.out.println("gtr2: " + gtr2);
        guitarPool.expire(gtr1);
        System.out.println("gtr2: isValid =  " + gtr2.isValid());
    }
}
