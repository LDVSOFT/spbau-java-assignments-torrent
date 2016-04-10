package ru.spbau.mit;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Created by ldvsoft on 02.04.16.
 */
public abstract class TorrentClientMain {

    private static final int ARG_ACTION = 0;
    private static final int ARG_ADDRESS = 1;
    private static final int ARG_1 = 2;

    private static final TorrentClient.StatusCallbacks STATUS_CALLBACKS = new TorrentClient.StatusCallbacks() {
        @Override
        public void onTrackerUpdated(boolean result, Throwable e) {
            if (result) {
                System.err.printf("Tracker update successful.\n");
            } else {
                System.err.printf("Tracker update failed:\n");
                if (e != null) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onDownloadIssue(FileEntry entry, String message, Throwable e) {
            System.err.printf("%d (%s): %s\n", entry.getId(), entry.getName(), message);
            if (e != null) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDownloadStart(FileEntry entry) {
            System.err.printf("%d (%s): Starting download.\n", entry.getId(), entry.getName());
        }

        @Override
        public void onDownloadPart(FileEntry entry, int partId) {
            System.err.printf("%d (%s): Downloaded part %d.\n", entry.getId(), entry.getName(), partId);
        }

        @Override
        public void onDownloadComplete(FileEntry entry) {
            System.out.printf("%d (%s): Download completed!\n", entry.getId(), entry.getName());
        }

        @Override
        public void onP2PServerIssue(Throwable e) {
            System.err.printf("P2P server issue, connection abandoned:\n");
            e.printStackTrace();
        }
    };

    public static void main(String[] args) {
        if (args.length < ARG_ACTION + 1) {
            System.err.printf("Missing action.\n");
            helpAndHalt();
        }

        try {
            String action = args[ARG_ACTION];
            switch (action) {
                case "list":
                    doList(args);
                    break;
                case "get":
                    doGet(args);
                    break;
                case "newfile":
                    doNewFile(args);
                    break;
                case "run":
                    doRun(args);
                    break;
                default:
                    System.err.printf("Unknown action \"%s\".\n", action);
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void doList(String[] args) throws IOException {
        if (args.length < ARG_ADDRESS + 1) {
            System.err.printf("Missing tracker address.\n");
            helpAndHalt();
        }
        String trackerAddress = args[ARG_ADDRESS];
        try (TorrentClient client = new TorrentClient(trackerAddress, Paths.get(""))) {
            client.list().forEach(entry -> System.out.printf(
                    "%d: %s (%d bytes).\n",
                    entry.getId(),
                    entry.getName(),
                    entry.getSize()
            ));
        }
    }

    private static void doGet(String[] args) throws IOException {
        if (args.length < ARG_1 + 1) {
            System.err.printf("Missing file id.\n");
            helpAndHalt();
        }
        String trackerAddress = args[ARG_ADDRESS];
        int id = Integer.decode(args[ARG_1]);
        try (TorrentClient client = new TorrentClient(trackerAddress, Paths.get(""))) {
            if (client.get(id)) {
                System.out.printf("New file added to download.\n");
            } else {
                System.out.printf("Failed: maybe file is already marked, or tracker hasn't it.");
            }
        }
    }

    private static void doNewFile(String[] args) throws IOException {
        if (args.length < ARG_1 + 1) {
            System.err.printf("Missing file path.\n");
            helpAndHalt();
        }
        String trackerAddress = args[ARG_ADDRESS];
        Path path = Paths.get(args[ARG_1]);
        try (TorrentClient client = new TorrentClient(trackerAddress, Paths.get(""))) {
            client.setCallbacks(STATUS_CALLBACKS);
            int newFileId = client.newFile(path).getId();
            System.out.printf("New file uploaded, id is %d.\n", newFileId);
        }
    }

    private static void doRun(String[] args) throws IOException {
        if (args.length < ARG_ADDRESS + 1) {
            System.err.printf("Missing file path.\n");
            helpAndHalt();
        }
        String trackerAddress = args[ARG_ADDRESS];
        try {
            TorrentClient client = new TorrentClient(trackerAddress, Paths.get(""));
            client.setCallbacks(STATUS_CALLBACKS);
            client.run();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.printf("stopping....\n");
                try {
                    client.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
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

