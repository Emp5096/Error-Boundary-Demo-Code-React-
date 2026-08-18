package com.xy.interview.demo;

import lombok.Data;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Auther: cxy
 * @Date: 2026/6/17 - 06 - 17 - 14:20
 * @Description: com.xy.interview.demo
 * @version: 1.0
 */
public class StreamTest {
    class User{
        private int id;
        private String name;
        private int age;
        private String city;


        public User(int id, String name, int age, String city) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.city = city;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", age=" + age +
                    ", city='" + city + '\'' +
                    '}';
        }
    }
    @Test
    public void checkIfChangeResource(){
        List<Integer> list1 = List.of(1, 2, 3, 4, 5, 6);
        User user1 = new User(1, "张三", 18, "上海");
        User user2 = new User(2, "李四", 17, "成都");
        User user3 = new User(3, "王五", 19, "上海");
        User user4 = new User(4, "赵六", 16, "北京");
        List<User> userList = List.of(user1, user2, user3, user4);
        List<String> list = userList.stream()
                .map(User::getName)
                .toList();
        List<User> adultList = userList.stream().filter(user -> user.getAge() >= 18).toList();
        Map<Integer, User> userMap2 = userList.stream()
                .collect(Collectors.toMap(
                        User::getId,
                        Function.identity()
                ));
        Map<Integer, String> userMap3 = userList.stream()
                .collect(Collectors.toMap(
                        User::getId,
                        User::getName
                ));
        Map<String, List<User>> cityMap = userList.stream()
                .collect(Collectors.groupingBy(User::getCity));
        Map<String, List<String>> cityMap2 = userList.stream()
                .collect(Collectors.groupingBy(
                        User::getCity,
                        Collectors.mapping(
                                User::getName,
                                Collectors.toList()
                        )
                ));
        boolean kid = userList.stream().allMatch(user -> user.getAge() >= 18);
//        System.out.println(kid);

        List<User> list2 = userList.stream()
                .filter(user -> "成都".equals(user.getCity()))
                .toList();
        User userExist = userList.stream()
                .filter(user -> "上海".equals(user.getCity()))
                .findFirst()
                .orElse(null);
//        System.out.println(userExist);
        List<User> list3 = userList.stream()
                .sorted(Comparator.comparing(User::getAge).reversed())
                .toList();
        for (User user : list3) {
            System.out.println(user);
        }
        Map<String, Long> citizenCountingMap = userList.stream()
                .collect(Collectors.groupingBy(
                        User::getCity,
                        Collectors.counting()));
        for (Map.Entry<String, Long> entry : citizenCountingMap.entrySet()){
            System.out.println(entry);
        }
        User oldestUser = userList.stream()
                .max(Comparator.comparing(User::getAge))
                .orElse(null);
        System.out.println(oldestUser);

//        for (User user : adultList) {
//            System.out.println(user);
//        }
//        for (String name : list){
//            System.out.println(name);
//        }
//        for (Map.Entry<String, List<User>> entry : cityMap.entrySet()){
//            System.out.println(entry);
//        }
        /*for (Map.Entry<Integer, String> entry : userMap3.entrySet()){
            System.out.println(entry);
        }*/
        Object a = new Object();

    }
}
