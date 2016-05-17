package ru.spbau.mit;

import org.apache.commons.io.FileUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by ldvsoft on 04.05.16.
 */
public final class TorrentClientGUIMain {
    // CHECKSTYLE.OFF: MagicNumber
    private enum Columns {
        ID,
        NAME,
        LOCAL_PATH,
        SIZE,
        PROGRESS
    }

    private static final Map<Columns, String> COLUMNS_NAMES = new EnumMap<>(Columns.class);

    static {
        COLUMNS_NAMES.put(Columns.ID, "File ID");
        COLUMNS_NAMES.put(Columns.NAME, "File name");
        COLUMNS_NAMES.put(Columns.LOCAL_PATH, "Local path");
        COLUMNS_NAMES.put(Columns.SIZE, "File size");
        COLUMNS_NAMES.put(Columns.PROGRESS, "Progress");
    }

    private static final class TableRow {
        private int id;
        private String name;
        private String localPath;
        private String size;
        private double progress;

        private TableRow(TorrentClientState.FileState state) {
            try (LockHandler handler = LockHandler.lock(state.fileLock.readLock())) {
                id = state.entry.getId();
                name = state.entry.getName();
                localPath = state.localPath.toString();
                size = FileUtils.byteCountToDisplaySize(state.entry.getSize());
                progress = (state.parts.getCount() + 0.0) / state.entry.getPartsCount();
            }
        }
    }

    private static final class TableModel extends AbstractTableModel {
        private volatile List<TableRow> data = Collections.emptyList();

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return Columns.values().length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS_NAMES.get(Columns.values()[column]);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            switch (Columns.values()[columnIndex]) {
                case ID:
                    return data.get(rowIndex).id;
                case NAME:
                    return data.get(rowIndex).name;
                case LOCAL_PATH:
                    return data.get(rowIndex).localPath;
                case SIZE:
                    return data.get(rowIndex).size;
                case PROGRESS:
                    return data.get(rowIndex).progress;
            }
            return null;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (Columns.values()[columnIndex]) {
                case ID:
                    return Integer.TYPE;
                case NAME:
                case LOCAL_PATH:
                case SIZE:
                    return String.class;
                case PROGRESS:
                    return Double.TYPE;
            }
            return null;
        }

        private void setData(List<TableRow> newData) {
            data = newData;
            fireTableDataChanged();
        }
    }

    private static final class ProgressRenderer implements TableCellRenderer {
        private static final int SCALE = 1000;

        private JProgressBar bar = new JProgressBar(0, SCALE);

        private ProgressRenderer() {
            bar.setStringPainted(true);
            bar.setMinimum(0);
            bar.setMaximum(SCALE);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value,
                boolean isSelected, boolean hasFocus,
                int row, int column
        ) {
            bar.setValue((int) ((double) value * SCALE));
            return bar;
        }
    }

    private JFileChooser fc = new JFileChooser();
    private TableModel model;
    private JTextArea logArea;
    private TorrentClientState state;
    private TorrentRunningClient runningClient;
    private JFrame frame;

    private final TorrentRunningClient.RunCallbacks callbacks = new TorrentRunningClient.RunCallbacks() {
        @Override
        public void onTrackerUpdated(boolean result, Throwable e) {
            if (!result) {
                writeMessage("Failed to update tracker: %s", e.getMessage());
            }
        }

        @Override
        public void onDownloadIssue(FileEntry entry, String message, Throwable e) {
            writeMessage(
                    "File \"%s\" (#%d) download issue: %s (%s)",
                    entry.getName(),
                    entry.getId(),
                    message,
                    (e == null) ? "" : e.getMessage()
            );
        }

        @Override
        public void onDownloadStart(FileEntry entry) {
            writeMessage("File \"%s\" (#%d) download is starting...", entry.getName(), entry.getId());
        }

        @Override
        public void onDownloadPart(FileEntry entry, int partId) {
            fetchModel();
        }

        @Override
        public void onDownloadComplete(FileEntry entry) {
            writeMessage("File \"%s\" (#%d) downloaded!", entry.getName(), entry.getId());
        }

        @Override
        public void onP2PServerIssue(Throwable e) {
            writeMessage("Seeding server issue: %s", e.getMessage());
        }
    };

    private final Action newFileAction = new AbstractAction() {
        {
            putValue(NAME, "New file");
            putValue(SHORT_DESCRIPTION, "Upload new file to tracker");
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            SwingUtilities.invokeLater(() -> {
                int ret = fc.showOpenDialog(frame);
                if (ret == JFileChooser.APPROVE_OPTION) {
                    Path p = fc.getSelectedFile().toPath();
                    try {
                        new TorrentClient(state).newFile(p);
                        fetchModel();
                    } catch (IOException e) {
                        showErrorDialog("Failed to upload file: " + e.getMessage());
                    }
                }
            });
        }
    };

    private final Action getFileAction = new AbstractAction() {
        {
            putValue(NAME, "Get file");
            putValue(SHORT_DESCRIPTION, "Download new file from peers");
            putValue(MNEMONIC_KEY, KeyEvent.VK_D);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            SwingUtilities.invokeLater(() -> {
                List<FileEntry> files;
                try {
                    files = new TorrentClient(state).requestList();
                } catch (IOException e) {
                    showErrorDialog(String.format("Failed to get file list from tracker: %s\n", e.getMessage()));
                    return;
                }
                try (LockHandler handler = LockHandler.lock(state.lock.readLock())) {
                    for (TorrentClientState.FileState fileState : state.files.values()) {
                        files.remove(fileState.entry);
                    }
                }
                Integer result = new TorrentClientGUIListDialog(frame, files).showDialog();
                try {
                    if (result != null) {
                        new TorrentClient(state).get(files.get(result).getId());
                        fetchModel();
                    }
                } catch (IOException e) {
                    showErrorDialog(String.format("Failed to add file to downloads list: %s\n", e.getMessage()));
                }
            });
        }
    };

    private final Action startRunAction = new AbstractAction() {
        {
            putValue(NAME, "Start running");
            putValue(SHORT_DESCRIPTION, "Start seeding and downloading files");
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            try {
                runningClient = new TorrentRunningClient(state);
                runningClient.startRun(callbacks);

                writeMessage("Started running!");
                setEnabled(false);
                stopRunAction.setEnabled(true);
                changeTrackerAction.setEnabled(false);
                newFileAction.setEnabled(false);
                getFileAction.setEnabled(false);
            } catch (IOException e) {
                e.printStackTrace();
                showErrorDialog("Failed to run client: " + e.getMessage());
                runningClient = null;
            }
        }
    };

    private final Action stopRunAction = new AbstractAction() {
        {
            putValue(NAME, "Stop running");
            putValue(SHORT_DESCRIPTION, "Stop seeding and downloading files");
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_MASK));
            setEnabled(false);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            runningClient.shutdown();
            writeMessage("Stopped running!");
            setEnabled(false);
            startRunAction.setEnabled(true);
            changeTrackerAction.setEnabled(true);
            newFileAction.setEnabled(true);
            getFileAction.setEnabled(true);
        }
    };

    private final Action changeTrackerAction = new AbstractAction() {
        {
            putValue(NAME, "Change tracker");
            putValue(SHORT_DESCRIPTION, "Change tracker address");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            String newHost = JOptionPane.showInputDialog(
                    frame,
                    "Enter new tracker address.\n\nWarning: it will reset client state and all files!",
                    state.host,
                    JOptionPane.QUESTION_MESSAGE
            );
            if (newHost == null) {
                //Canceled
                return;
            }
            try {
                state.close();
                TorrentClientState.wipe(Paths.get(""));
                state = new TorrentClientState(newHost, Paths.get(""));
            } catch (IOException e) {
                e.printStackTrace();
                showErrorDialog("Client state corrupted: " + e.getMessage());
                TorrentClientState.wipe(Paths.get(""));
                close();
            }
        }
    };

    private final Action closeAction = new AbstractAction() {
        {
            putValue(NAME, "Close");
            putValue(SHORT_DESCRIPTION, "Exit application");
            putValue(MNEMONIC_KEY, KeyEvent.VK_Q);
            putValue(ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_MASK));
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            close();
        }
    };

    private TorrentClientGUIMain() {
        model = new TableModel();
        buildUI();
        try {
            state = new TorrentClientState(Paths.get(""));
            fetchModel();
        } catch (IOException e) {
            e.printStackTrace();
            showErrorDialog("Client state corrupted: " + e.getMessage());
            TorrentClientState.wipe(Paths.get(""));
            close();
        }
    }

    public static void main(String[] args) {
        new TorrentClientGUIMain();
    }

    private void fetchModel() {
        SwingUtilities.invokeLater(() -> {
            List<TableRow> data;
            try (LockHandler handler = LockHandler.lock(state.lock.readLock())) {
                data = state.files.values()
                        .stream()
                        .map(TableRow::new)
                        .collect(Collectors.toList());
            }
            model.setData(data);
        });
    }

    private void close() {
        frame.dispose();
        if (runningClient != null) {
            runningClient.shutdown();
        }
        if (state != null) {
            try {
                state.close();
            } catch (IOException e) {
                showErrorDialog("Failed to save state: " + e.getMessage());
            }
        }
        System.exit(0);
    }

    private void buildUI() {
        frame = new JFrame("Torrent client");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        table.setColumnSelectionAllowed(false);
        table.setCellSelectionEnabled(false);
        table.getColumn(COLUMNS_NAMES.get(Columns.ID)).setMinWidth(50);
        table.getColumn(COLUMNS_NAMES.get(Columns.ID)).setMaxWidth(75);
        table.getColumn(COLUMNS_NAMES.get(Columns.NAME)).setMinWidth(200);
        table.getColumn(COLUMNS_NAMES.get(Columns.NAME)).setMaxWidth(400);
        table.getColumn(COLUMNS_NAMES.get(Columns.LOCAL_PATH)).setMinWidth(200);
        table.getColumn(COLUMNS_NAMES.get(Columns.SIZE)).setMinWidth(50);
        table.getColumn(COLUMNS_NAMES.get(Columns.SIZE)).setMaxWidth(75);
        table.getColumn(COLUMNS_NAMES.get(Columns.PROGRESS)).setMinWidth(50);
        table.getColumn(COLUMNS_NAMES.get(Columns.PROGRESS)).setMaxWidth(150);
        table.getColumn(COLUMNS_NAMES.get(Columns.PROGRESS)).setCellRenderer(new ProgressRenderer());

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setMinimumSize(new Dimension(200, 100));

        JMenuBar menuBar = new JMenuBar();
        {
            JMenu fileMenu = new JMenu("File");
            fileMenu.add(newFileAction);
            fileMenu.add(getFileAction);
            fileMenu.addSeparator();
            fileMenu.add(changeTrackerAction);
            fileMenu.add(startRunAction);
            fileMenu.add(stopRunAction);
            fileMenu.addSeparator();
            fileMenu.add(closeAction);
            menuBar.add(fileMenu);
        }

        SwingUtilities.invokeLater(() -> {
            frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.PAGE_AXIS));
            frame.setJMenuBar(menuBar);
            frame.add(new JScrollPane(table));
            frame.add(new JScrollPane(logArea));
            frame.setMinimumSize(new Dimension(700, 600));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private void showErrorDialog(String message) {
        JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE);
        writeMessage("Error: " + message);
    }

    private void writeMessage(String format, Object... args) {
        SwingUtilities.invokeLater(() -> logArea.append(String.format(format + "\n", args)));
    }
}
