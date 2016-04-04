package ru.spbau.mit;

import java.io.IOException;

/**
 * Created by ldvsoft on 02.04.16.
 */
public abstract class TorrentClientMain {

    private static final int ARG_ACTION = 0;
    private static final int ARG_ADDRESS = 1;
    private static final int ARG_1 = 2;

    public static void main(String[] args) {
        if (args.length < ARG_ADDRESS + 1) {
            System.err.printf("Missing action and/or tracker address.\n");
            helpAndHalt();
        }

        String action = args[ARG_ACTION];
        String trackerAddress = args[ARG_ADDRESS];
        try (TorrentClient client = new TorrentClient(trackerAddress)) {
            switch (action) {
                case "list":
                    client.list().forEach(entry -> System.out.printf(
                                    "%d: %s (%d bytes).\n",
                                    entry.getId(),
                                    entry.getName(),
                                    entry.getSize()
                    ));
                    break;
                case "get":
                    if (args.length < ARG_1 + 1) {
                        System.err.printf("Missing file id.\n");
                        helpAndHalt();
                    }
                    int id = Integer.decode(args[ARG_1]);
                    if (client.get(id)) {
                        System.out.printf("New file added to download.\n");
                    } else {
                        System.out.printf("Failed: maybe file is already marked, or tracker hasn't it.");
                    }
                    break;
                case "newfile":
                    if (args.length < ARG_1 + 1) {
                        System.err.printf("Missing file path.\n");
                        helpAndHalt();
                    }
                    String pathString = args[ARG_1];
                    int newFileId = client.newFile(pathString);
                    System.out.printf("New file uploaded, id is %d.\n", newFileId);
                    break;
                case "run":
                    client.run();
                    while (true) {
                        try {
                            Thread.sleep(TorrentTrackerConnection.DELAY);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    break;
                default:
                    System.err.printf("Unknown action \"%s\".\n", action);
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void helpAndHalt() {
        System.err.printf("Available actions:\n");
        System.err.printf("\tlist <tracker-address>: get available files list from the tracker.\n");
        System.err.printf("\tget <tracker-address> <id>: mark file with given id for download.\n");
        System.err.printf("\tnewfile <tracker-address> <path>: upload new file to tracker.\n");
        System.err.printf("\trun <tracker-address>: start working until interrupted.\n");

        System.exit(1);
    }
}
