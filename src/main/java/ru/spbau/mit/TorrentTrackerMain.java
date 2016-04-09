package ru.spbau.mit;

import java.io.IOException;

/**
 * Created by ldvsoft on 04.04.16.
 */
public abstract class TorrentTrackerMain {
    public static void main(String[] args) {
        try {
            TorrentTracker tracker = new TorrentTracker(".");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    tracker.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
