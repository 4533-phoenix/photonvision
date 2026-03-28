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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/**
 * Whacknet Gyro Receiver. Listens for high-frequency UDP packets containing robot telemetry.
 * Performs clock translation to synchronize gyro data with camera frames.
 */
public class WhacknetReceiver {
    private static final Logger logger = new Logger(WhacknetReceiver.class, LogGroup.NetworkTables);
    private final TreeMap<Long, GyroState> history = new TreeMap<>();
    private static final int PACKET_SIZE = 64;
    private static final long STALE_TIMEOUT_MS = 100;
    private static final long HISTORY_WINDOW_MICROS = 1_000_000; // 1 second

    private static class SingletonHolder {
        private static final WhacknetReceiver INSTANCE = new WhacknetReceiver();
    }

    public static WhacknetReceiver getInstance() {
        return SingletonHolder.INSTANCE;
    }

    /** Represents the synchronized state of the robot at a specific point in time. */
    public static record GyroState(
            long rioTimestampMicros,
            long localTimestampMicros,
            double rollRadians,
            double pitchRadians,
            double yawRadians,
            double rollVelocityRadPerSec,
            double pitchVelocityRadPerSec,
            double yawVelocityRadPerSec,
            long receiveTimeMillis) {

        public boolean isStale() {
            return (System.currentTimeMillis() - receiveTimeMillis) > STALE_TIMEOUT_MS;
        }
    }

    /** Represents the interpolated gyro position at a specific point in time. */
    public static record InterpolatedGyroState(
            long localTimestampMicros, double rollRadians, double pitchRadians, double yawRadians) {}

    private final AtomicReference<GyroState> latestState = new AtomicReference<>(null);
    private Thread receiveThread;
    private DatagramSocket socket;
    private volatile boolean running = false;
    private int currentPort = -1;

    private WhacknetReceiver() {}

    /** Starts the background receiver thread if it isn't already running on the requested port. */
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

    /** Stops the receiver thread and closes the socket. */
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
                double roll = bb.getDouble();
                double pitch = bb.getDouble();
                double yaw = bb.getDouble();
                double rollVelocity = bb.getDouble();
                double pitchVelocity = bb.getDouble();
                double yawVelocity = bb.getDouble();

                // Translate the timestamp to the local clock
                long ntOffset = NetworkTablesManager.getInstance().getOffset();
                long localTranslatedTimestamp = rioTimestamp - ntOffset;

                // Create the gyro state
                var state =
                        new GyroState(
                                rioTimestamp,
                                localTranslatedTimestamp,
                                roll,
                                pitch,
                                yaw,
                                rollVelocity,
                                pitchVelocity,
                                yawVelocity,
                                System.currentTimeMillis());

                synchronized (history) {
                    history.put(localTranslatedTimestamp, state);
                    long threshold = localTranslatedTimestamp - HISTORY_WINDOW_MICROS;
                    while (!history.isEmpty() && history.firstKey() < threshold) {
                        history.pollFirstEntry();
                    }
                }
                latestState.set(state);
            } catch (Exception e) {
                // Silently handle timeouts to loop back and check 'running'
                if (!(e instanceof SocketTimeoutException)) {
                    if (running) logger.error("Whacknet receive error: " + e.getMessage());
                }
            }
        }
        logger.info("Whacknet Receiver thread exiting.");
    }

    /**
     * Gets the most recent gyro data.
     *
     * @return The latest GyroState, or null if no data received or data is stale (>100ms).
     */
    public GyroState getLatestState() {
        GyroState state = latestState.get();
        if (state == null || state.isStale()) {
            return null;
        }
        return state;
    }

    private double getQuinticInterpolatedValue(
            double p0,
            double v0,
            double a0,
            double p1,
            double v1,
            double a1,
            double t,
            double deltaTime) {

        // Ensure we interpolate across the shortest path
        double diff = p1 - p0;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        p1 = p0 + diff;

        // Time powers
        double t2 = t * t;
        double t3 = t2 * t;
        double t4 = t3 * t;
        double t5 = t4 * t;

        // Quintic Hermite Basis Functions
        double h00 = 1 - 10 * t3 + 15 * t4 - 6 * t5;
        double h10 = t - 6 * t3 + 8 * t4 - 3 * t5;
        double h20 = 0.5 * t2 - 1.5 * t3 + 1.5 * t4 - 0.5 * t5;

        double h01 = 10 * t3 - 15 * t4 + 6 * t5;
        double h11 = -4 * t3 + 7 * t4 - 3 * t5;
        double h21 = 0.5 * t3 - t4 + 0.5 * t5;

        double dt2 = deltaTime * deltaTime;

        return (h00 * p0)
                + (h10 * v0 * deltaTime)
                + (h20 * a0 * dt2)
                + (h01 * p1)
                + (h11 * v1 * deltaTime)
                + (h21 * a1 * dt2);
    }

    public InterpolatedGyroState getInterpolatedState(long localMicros) {
        synchronized (history) {
            if (history.isEmpty()) return null;

            var entry0 = history.floorEntry(localMicros);
            var entry1 = history.ceilingEntry(localMicros);

            if (entry0 == null || entry1 == null || entry0.getKey().equals(entry1.getKey())) {
                return null;
            }

            long t0 = entry0.getKey();
            long t1 = entry1.getKey();
            GyroState s0 = entry0.getValue();
            GyroState s1 = entry1.getValue();

            double deltaTime = (t1 - t0) / 1_000_000.0;
            double t = (double) (localMicros - t0) / (t1 - t0);

            // Fetch outer points if they exist
            var entryMinus1 = history.lowerEntry(t0);
            var entry2 = history.higherEntry(t1);

            // Calculate Accelerations at t0
            double rollAcc0, pitchAcc0, yawAcc0;
            if (entryMinus1 != null) {
                // Central Difference
                double dtMinus1to1 = (t1 - entryMinus1.getKey()) / 1_000_000.0;
                GyroState sMinus1 = entryMinus1.getValue();
                rollAcc0 = (s1.rollVelocityRadPerSec() - sMinus1.rollVelocityRadPerSec()) / dtMinus1to1;
                pitchAcc0 = (s1.pitchVelocityRadPerSec() - sMinus1.pitchVelocityRadPerSec()) / dtMinus1to1;
                yawAcc0 = (s1.yawVelocityRadPerSec() - sMinus1.yawVelocityRadPerSec()) / dtMinus1to1;
            } else {
                // Forward Difference
                rollAcc0 = (s1.rollVelocityRadPerSec() - s0.rollVelocityRadPerSec()) / deltaTime;
                pitchAcc0 = (s1.pitchVelocityRadPerSec() - s0.pitchVelocityRadPerSec()) / deltaTime;
                yawAcc0 = (s1.yawVelocityRadPerSec() - s0.yawVelocityRadPerSec()) / deltaTime;
            }

            // Calculate Accelerations at t1
            double rollAcc1, pitchAcc1, yawAcc1;
            if (entry2 != null) {
                // Central Difference
                double dt0to2 = (entry2.getKey() - t0) / 1_000_000.0;
                GyroState s2 = entry2.getValue();
                rollAcc1 = (s2.rollVelocityRadPerSec() - s0.rollVelocityRadPerSec()) / dt0to2;
                pitchAcc1 = (s2.pitchVelocityRadPerSec() - s0.pitchVelocityRadPerSec()) / dt0to2;
                yawAcc1 = (s2.yawVelocityRadPerSec() - s0.yawVelocityRadPerSec()) / dt0to2;
            } else {
                // Backward Difference
                rollAcc1 = (s1.rollVelocityRadPerSec() - s0.rollVelocityRadPerSec()) / deltaTime;
                pitchAcc1 = (s1.pitchVelocityRadPerSec() - s0.pitchVelocityRadPerSec()) / deltaTime;
                yawAcc1 = (s1.yawVelocityRadPerSec() - s0.yawVelocityRadPerSec()) / deltaTime;
            }

            // ALWAYS use Quintic Hermite! It dynamically adapts its accuracy.
            return new InterpolatedGyroState(
                    localMicros,
                    normalizeAngle(
                            getQuinticInterpolatedValue(
                                    s0.rollRadians(),
                                    s0.rollVelocityRadPerSec(),
                                    rollAcc0,
                                    s1.rollRadians(),
                                    s1.rollVelocityRadPerSec(),
                                    rollAcc1,
                                    t,
                                    deltaTime)),
                    normalizeAngle(
                            getQuinticInterpolatedValue(
                                    s0.pitchRadians(),
                                    s0.pitchVelocityRadPerSec(),
                                    pitchAcc0,
                                    s1.pitchRadians(),
                                    s1.pitchVelocityRadPerSec(),
                                    pitchAcc1,
                                    t,
                                    deltaTime)),
                    normalizeAngle(
                            getQuinticInterpolatedValue(
                                    s0.yawRadians(),
                                    s0.yawVelocityRadPerSec(),
                                    yawAcc0,
                                    s1.yawRadians(),
                                    s1.yawVelocityRadPerSec(),
                                    yawAcc1,
                                    t,
                                    deltaTime)));
        }
    }

    /** Standard angle normalization to keep radians within [-PI, PI] */
    private double normalizeAngle(double radians) {
        while (radians > Math.PI) radians -= 2 * Math.PI;
        while (radians < -Math.PI) radians += 2 * Math.PI;
        return radians;
    }
}
