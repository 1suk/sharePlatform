package com.shareCart.project;

import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;


public class DataTest {

    public static void main(String[] args) {
        List<String> words = new ArrayList<>(List.of("Cat", "Elephant", "Dog", "Giraffe", "Ant"));

        words.sort((a,b) -> Integer.compare(b.length(), a.length()));

        words.forEach(System.out::println);
    }
}