package ru.spbau.mit;

import java.io.IOException;
import java.net.Socket;

import static ru.spbau.mit.TorrentTrackerConnection.TRACKER_PORT;

/**
 * Created by ldvsoft on 26.04.16.
 */
public abstract class TorrentClientBase {
    protected TorrentClientState state;

    public TorrentClientBase(TorrentClientState state) {
        this.state = state;
    }

    protected TorrentTrackerConnection connectToTracker() throws IOException {
        return new TorrentTrackerConnection(new Socket(state.host, TRACKER_PORT));
    }
}
