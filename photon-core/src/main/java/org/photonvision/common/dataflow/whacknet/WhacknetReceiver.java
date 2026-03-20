/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.common.dataflow.whacknet;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/**
 * Whacknet Gyro Receiver.
 * Listens for high-frequency UDP packets containing robot telemetry.
 * Performs clock translation to synchronize gyro data with camera frames.
 */
public class WhacknetReceiver {
    private static final Logger logger = new Logger(WhacknetReceiver.class, LogGroup.NetworkTables);
    private static final int PACKET_SIZE = 24;
    private static final long STALE_TIMEOUT_MS = 100;

    private static class SingletonHolder {
        private static final WhacknetReceiver INSTANCE = new WhacknetReceiver();
    }

    public static WhacknetReceiver getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * Represents the synchronized state of the robot at a specific point in time.
     */
    public static record GyroState(
            long rioTimestampMicros,
            long localTimestampMicros,
            double headingRadians,
            double velocityRadPerSec,
            long receiveTimeMillis) {
        
        public boolean isStale() {
            return (System.currentTimeMillis() - receiveTimeMillis) > STALE_TIMEOUT_MS;
        }
    }

    private final AtomicReference<GyroState> latestState = new AtomicReference<>(null);
    private Thread receiveThread;
    private DatagramSocket socket;
    private volatile boolean running = false;
    private int currentPort = -1;

    private WhacknetReceiver() {}

    /**
     * Starts the background receiver thread if it isn't already running on the requested port.
     */
    public synchronized void start(int port) {
        if (running && currentPort == port) return;
        
        stop(); // Ensure old resources are cleaned up if port changed

        try {
            socket = new DatagramSocket(port);
            socket.setSoTimeout(500);
            currentPort = port;
            running = true;

            receiveThread = new Thread(this::receiveLoop, "WhacknetReceiverThread");
            receiveThread.setPriority(Thread.MAX_PRIORITY);
            receiveThread.start();
            
            logger.info("Whacknet Receiver started on port " + port);
        } catch (SocketException e) {
            logger.error("Failed to open Whacknet UDP socket on port " + port, e);
        }
    }

    /**
     * Stops the receiver thread and closes the socket.
     */
    public synchronized void stop() {
        running = false;
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        latestState.set(null);
        currentPort = -1;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

        while (running) {
            try {
                socket.receive(packet);
                
                if (packet.getLength() < PACKET_SIZE) continue;

                bb.rewind();
                long rioTimestamp = bb.getLong();
                double heading = bb.getDouble();
                double velocity = bb.getDouble();

                // Translate the timestamp to the local clock
                long ntOffset = NetworkTablesManager.getInstance().getOffset();
                long localTranslatedTimestamp = rioTimestamp + ntOffset;

                System.out.println("Received Whacknet packet: " + rioTimestamp + ", " + localTranslatedTimestamp + ", " + heading + ", " + velocity);

                latestState.set(new GyroState(
                    rioTimestamp,
                    localTranslatedTimestamp,
                    heading,
                    velocity,
                    System.currentTimeMillis()
                ));

            } catch (Exception e) {
                // Silently handle timeouts to loop back and check 'running'
                if (!(e instanceof java.net.SocketTimeoutException)) {
                    if (running) logger.error("Whacknet receive error: " + e.getMessage());
                }
            }
        }
        logger.info("Whacknet Receiver thread exiting.");
    }

    /**
     * Gets the most recent gyro data.
     * @return The latest GyroState, or null if no data received or data is stale (>100ms).
     */
    public GyroState getLatestState() {
        GyroState state = latestState.get();
        if (state == null || state.isStale()) {
            return null;
        }
        return state;
    }
}