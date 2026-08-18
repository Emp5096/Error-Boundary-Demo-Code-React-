package com.xy.interview.demo;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * @Auther: cxy
 * @Date: 2026/7/13 - 07 - 13 - 16:33
 * @Description: com.xy.interview.demo
 * @version: 1.0
 */
public class CollectionMapTest {
    @Test
    public void linkedHashSetTest(){
        Map<String, String> map = new HashMap<>();
        Map<String, String> dictionary = new HashMap<>();
        ArrayDeque<Object> deque = new ArrayDeque<>();
        //new PriorityQueue<>(Comparator.comparing().thenComparing());
        ArrayList<Object> list = new ArrayList<>();
        //Arrays.sort(list, Comparator());

        list.add(1);
        //Class.forName().getDeclaredConstructor().newInstance()
        map.put("支付状态","待支付");
        dictionary = Map.copyOf(map);
        System.out.println(dictionary.get("支付状态"));
        map.put("支付状态","已支付");
        System.out.println(map.get("支付状态"));
        System.out.println(dictionary.get("支付状态"));
        Map<String, String> stringStringMap = Collections.unmodifiableMap(dictionary);

        //dictionary.put("支付状态","xia");
        Map<String, LongAdder> cm = new ConcurrentHashMap<>();
        //cm.computeIfAbsent(key, k -> new LongAdder()).increment();



    }
}
