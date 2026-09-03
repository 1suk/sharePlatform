package com.shareCart.project;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DataTest {

    public static void main(String[] args) throws InterruptedException {
        List<Integer> unsafeList = new ArrayList<>();
        List<Integer> safeList = new CopyOnWriteArrayList<>();

        int threadCount = 10;
        int countPerThread = 1000;

        Runnable addUnsafe = () -> {
            for (int i = 0; i < countPerThread; i++) {
                unsafeList.add(i);
            }
        };

        Runnable addSafe = () -> {
            for (int i = 0; i < countPerThread; i++) {
                safeList.add(i);
            }
        };

        Thread[] unsafeThreads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            unsafeThreads[i] = new Thread(addUnsafe);
            unsafeThreads[i].start();
        }
        for (Thread t : unsafeThreads) t.join();

        Thread[] safeThreads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            safeThreads[i] = new Thread(addSafe);
            safeThreads[i].start();
        }
        for (Thread t : safeThreads) t.join();

        System.out.println("기대하는 데이터 개수: " + (threadCount * countPerThread));
        System.out.println("ArrayList 결과: " + unsafeList.size());
        System.out.println("CopyOnWriteArrayList 결과: " + safeList.size());
    }
}