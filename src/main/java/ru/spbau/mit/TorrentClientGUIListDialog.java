package ru.spbau.mit;

import org.apache.commons.io.FileUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Created by ldvsoft on 04.05.16.
 */
public class TorrentClientGUIListDialog extends JDialog {
    private enum Columns {
        ID,
        NAME,
        SIZE,
    }

    private static final Map<Columns, String> COLUMNS_NAMES = new EnumMap<>(Columns.class);

    static {
        COLUMNS_NAMES.put(Columns.ID, "File ID");
        COLUMNS_NAMES.put(Columns.NAME, "File name");
        COLUMNS_NAMES.put(Columns.SIZE, "File size");
    }

    private static class TableModel extends AbstractTableModel {
        private volatile List<FileEntry> data;

        private TableModel(List<FileEntry> data) {
            this.data = data;
        }

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
                    return data.get(rowIndex).getId();
                case NAME:
                    return data.get(rowIndex).getName();
                case SIZE:
                    return FileUtils.byteCountToDisplaySize(data.get(rowIndex).getSize());
            }
            return null;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (Columns.values()[columnIndex]) {
                case ID:
                    return Integer.TYPE;
                case NAME:
                case SIZE:
                    return String.class;
            }
            return null;
        }
    }

    private JTable table;
    private Integer result = null;

    private Action selectAction = new AbstractAction() {
        {
            putValue(NAME, "Select");
            setEnabled(false);
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            result = table.getSelectedRow();
            dispose();
        }
    };

    private Action cancelAction = new AbstractAction() {
        {
            putValue(NAME, "Cancel");
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            result = null;
            dispose();
        }
    };

    public TorrentClientGUIListDialog(Frame owner, List<FileEntry> data) {
        super(owner, "Select file", DEFAULT_MODALITY_TYPE);
        super.setLocationRelativeTo(owner);

        table = new JTable(new TableModel(data));
        table.setAlignmentX(Component.LEFT_ALIGNMENT);
        table.setColumnSelectionAllowed(false);
        table.setRowSelectionAllowed(true);
        table.setCellSelectionEnabled(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(event -> {
            int viewRow = table.getSelectedRow();
            selectAction.setEnabled(viewRow >= 0);
        });

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                result = null;
            }
        });


        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.PAGE_AXIS));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        topPanel.add(new JLabel("Select file to download:"));
        topPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        topPanel.add(new JScrollPane(table));

        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        bottomPanel.add(Box.createHorizontalGlue());
        bottomPanel.add(new JButton(cancelAction));
        bottomPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        bottomPanel.add(new JButton(selectAction));

        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.PAGE_END);
        pack();
    }

    public Integer showDialog() {
        setVisible(true);
        return result;
    }
}
