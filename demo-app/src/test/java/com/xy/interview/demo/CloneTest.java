package com.xy.interview.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class CloneTest {

    @Test
    void basicCloneDemo() {
        BasicPerson original = new BasicPerson("Alice", 18);

        BasicPerson copy = original.clone();

        System.out.println("basic original = " + original);
        System.out.println("basic copy     = " + copy);
        System.out.println("same object?   = " + (original == copy));

        assertNotSame(original, copy);
        assertEquals(original.name, copy.name);
        assertEquals(original.age, copy.age);
    }

    @Test
    void shallowClonePitfallDemo() {
        ShallowUser original = new ShallowUser("Bob", new Address("Shanghai"));

        ShallowUser copy = original.clone();
        copy.address.city = "Chengdu";

        System.out.println("shallow original = " + original);
        System.out.println("shallow copy     = " + copy);
        System.out.println("same user?       = " + (original == copy));
        System.out.println("same address?    = " + (original.address == copy.address));

        assertNotSame(original, copy);
        assertSame(original.address, copy.address);
        assertEquals("Chengdu", original.address.city);
        assertEquals("Chengdu", copy.address.city);
    }

    @Test
    void deepCloneFixDemo() {
        DeepUser original = new DeepUser("Cindy", new Address("Shanghai"));

        DeepUser copy = original.clone();
        copy.address.city = "Chengdu";

        System.out.println("deep original = " + original);
        System.out.println("deep copy     = " + copy);
        System.out.println("same user?    = " + (original == copy));
        System.out.println("same address? = " + (original.address == copy.address));

        assertNotSame(original, copy);
        assertNotSame(original.address, copy.address);
        assertEquals("Shanghai", original.address.city);
        assertEquals("Chengdu", copy.address.city);
    }

    static class BasicPerson implements Cloneable {
        private String name;
        private int age;

        BasicPerson(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public BasicPerson clone() {
            try {
                return (BasicPerson) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String toString() {
            return "BasicPerson{" +
                    "name='" + name + '\'' +
                    ", age=" + age +
                    '}';
        }
    }

    static class Address implements Cloneable {
        private String city;

        Address(String city) {
            this.city = city;
        }

        @Override
        public Address clone() {
            try {
                return (Address) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String toString() {
            return "Address{" +
                    "city='" + city + '\'' +
                    '}';
        }
    }

    static class ShallowUser implements Cloneable {
        private String name;
        private Address address;

        ShallowUser(String name, Address address) {
            this.name = name;
            this.address = address;
        }

        @Override
        public ShallowUser clone() {
            try {
                return (ShallowUser) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String toString() {
            return "ShallowUser{" +
                    "name='" + name + '\'' +
                    ", address=" + address +
                    '}';
        }
    }

    static class DeepUser implements Cloneable {
        private String name;
        private Address address;

        DeepUser(String name, Address address) {
            this.name = name;
            this.address = address;
        }

        @Override
        public DeepUser clone() {
            try {
                DeepUser copy = (DeepUser) super.clone();
                copy.address = this.address.clone();
                return copy;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String toString() {
            return "DeepUser{" +
                    "name='" + name + '\'' +
                    ", address=" + address +
                    '}';
        }
    }
}
