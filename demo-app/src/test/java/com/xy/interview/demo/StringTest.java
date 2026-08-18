package com.xy.interview.demo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * @Auther: cxy
 * @Date: 2026/6/11 - 06 - 11 - 17:58
 * @Description: com.xy.interview.demo
 * @version: 1.0
 */
public class StringTest {
    @Test
    void StringTest(){
        String s1 = new String("a") + new String("b");
        String s2 = s1.intern();
        String s3 = "ab";

        System.out.println(s1 == s2);
        System.out.println(s1 == s3);
        ArrayList<Object> objects = new ArrayList<>();
    }
}
