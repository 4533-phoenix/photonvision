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

import edu.wpi.first.apriltag.AprilTagFieldLayout;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.math.util.Units;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.photonvision.common.configuration.ConfigManager;
import org.photonvision.common.dataflow.CVPipelineResultConsumer;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.math.MathUtils;
import org.photonvision.vision.pipeline.AdvancedPipelineSettings;
import org.photonvision.vision.pipeline.CVPipelineSettings;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.target.TrackedTarget;

public class WhacknetPublisher implements CVPipelineResultConsumer {
    private static final Logger logger = new Logger(WhacknetPublisher.class, LogGroup.General);

    private static final double AMBIGUITY_CUTOFF = 0.05;
    private static final double SINGLE_TAG_POSE_CUTOFF_METERS = 4.0;
    
    private static final double[] SINGLE_TAG_BASE_STD_DEVS = {2.0, 2.0, 4.0};
    private static final double[] MULTI_TAG_BASE_STD_DEVS = {0.5, 0.5, 1.0};
    private static final double[] INVALID_STD_DEVS = {0.0, 0.0, 0.0};

    private DatagramChannel channel;
    private final Supplier<CVPipelineSettings> settingsSupplier;
    
    // Direct buffer for zero-copy JNI transitions
    private final ByteBuffer buf = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
    
    private String lastIpString = "";
    private InetSocketAddress resolvedAddress;
    private Transform3d cachedRobotToCameraInverted = new Transform3d();
    private AdvancedPipelineSettings lastSettings = null;

    // Fast lookup cache for AprilTag poses
    private final Map<Integer, Pose3d> tagCache = new HashMap<>();
    private AprilTagFieldLayout lastLayout = null;

    // Reuse array to prevent GC allocations
    private final double[] currentStdDevs = new double[3];

    public WhacknetPublisher(Supplier<CVPipelineSettings> settingsSupplier) {
        this.settingsSupplier = settingsSupplier;
        try {
            channel = DatagramChannel.open();
            // Send packets and move on; never block the vision thread
            channel.configureBlocking(false);
        } catch (Exception e) {
            logger.error("Failed to initialize Whacknet UDP channel", e);
        }
    }

    @Override
    public void accept(CVPipelineResult result) {
        CVPipelineSettings baseSettings = settingsSupplier.get();
        if (!(baseSettings instanceof AdvancedPipelineSettings settings)) return;
        if (!settings.whacknetSenderEnabled) return;

        // Refresh AprilTag lookup map if the field layout changed
        updateTagCache();

        double poseX = 0.0, poseY = 0.0, poseRot = 0.0;
        double[] stdDevs = INVALID_STD_DEVS;
        int usedTagCount = 0;

        if (result.hasTargets()) {
            Transform3d fieldToCamera = null;

            if (result.multiTagResult != null && result.multiTagResult.isPresent()) {
                var mtr = result.multiTagResult.get();
                fieldToCamera = mtr.estimatedPose.best;
                usedTagCount = mtr.fiducialIDsUsed.size();
            } 
            else if (!result.targets.isEmpty() && result.targets.get(0).isFiducial()) {
                var target = result.targets.get(0);
                if (isUsableSingleTag(target)) {
                    var tagPose = tagCache.get(target.getFiducialId());
                    if (tagPose != null) {
                        fieldToCamera = new Transform3d(tagPose.getTranslation(), tagPose.getRotation())
                                            .plus(target.getBestCameraToTarget3d().inverse());
                        usedTagCount = 1;
                    }
                }
            }

            if (fieldToCamera != null) {
                updateTransformCache(settings);
                Transform3d fieldToRobot = fieldToCamera.plus(cachedRobotToCameraInverted);
                
                poseX = fieldToRobot.getX();
                poseY = fieldToRobot.getY();
                poseRot = fieldToRobot.getRotation().getZ();

                calculateScaledStdDevs(fieldToRobot, result.targets, usedTagCount);
                stdDevs = currentStdDevs;
            }
        }

        buf.clear();
        buf.putDouble(poseX);
        buf.putDouble(poseY);
        buf.putDouble(poseRot);
        buf.putDouble(stdDevs[0]);
        buf.putDouble(stdDevs[1]);
        buf.putDouble(stdDevs[2]);
        
        long nowNanos = MathUtils.wpiNanoTime();
        long captureNanos = result.getImageCaptureTimestampNanos();
        long pipelineDelayMicros = (nowNanos - captureNanos) / 1000;
        buf.putLong(pipelineDelayMicros);
        
        buf.put((byte) settings.whacknetCameraId);
        buf.put((byte) usedTagCount);
        
        while(buf.hasRemaining()) buf.put((byte) 0);
        buf.flip();

        sendPacket(settings.whacknetRioPort);
    }

    private void updateTagCache() {
        var currentLayout = ConfigManager.getInstance().getConfig().getApriltagFieldLayout();
        if (currentLayout != lastLayout) {
            tagCache.clear();
            for (var tag : currentLayout.getTags()) {
                tagCache.put(tag.ID, tag.pose);
            }
            lastLayout = currentLayout;
            logger.debug("Whacknet AprilTag cache updated for new field layout");
        }
    }

    private void calculateScaledStdDevs(Transform3d robotPose, List<TrackedTarget> targets, int tagCount) {
        if (tagCount == 0) {
            System.arraycopy(INVALID_STD_DEVS, 0, currentStdDevs, 0, 3);
            return;
        }

        double totalDistance = 0;
        int validTagsForDist = 0;

        for (var target : targets) {
            var tagPose = tagCache.get(target.getFiducialId());
            if (tagPose == null) continue;
            
            double dist = tagPose.getTranslation().getDistance(robotPose.getTranslation());
            totalDistance += dist;
            validTagsForDist++;
        }

        double avgDist = (validTagsForDist > 0) ? (totalDistance / validTagsForDist) : 4.0;
        double[] base = (tagCount > 1) ? MULTI_TAG_BASE_STD_DEVS : SINGLE_TAG_BASE_STD_DEVS;
        double scaler = 1.0 + (Math.pow(avgDist, 2) / 30.0);
        
        currentStdDevs[0] = base[0] * scaler;
        currentStdDevs[1] = base[1] * scaler;
        currentStdDevs[2] = base[2] * scaler;
    }

    private boolean isUsableSingleTag(TrackedTarget target) {
        double ambiguity = target.getPoseAmbiguity();
        double distance = target.getBestCameraToTarget3d().getTranslation().getNorm();

        return (ambiguity >= 0 && ambiguity < AMBIGUITY_CUTOFF) 
                && (distance < SINGLE_TAG_POSE_CUTOFF_METERS);
    }

    private void sendPacket(int port) {
        try {
            String ip = ConfigManager.getInstance().getConfig().getNetworkConfig().ntServerAddress;
            if (ip == null || ip.isEmpty() || "0".equals(ip)) {
                ip = "127.0.0.1";
            }

            if (!ip.equals(lastIpString)) {
                logger.info("Whacknet target IP changed: " + lastIpString + " -> " + ip);
                resolvedAddress = new InetSocketAddress(ip, port);
                lastIpString = ip;
            }

            if (channel != null && resolvedAddress != null) {
                channel.send(buf, resolvedAddress);
            }
        } catch (Exception e) {}
    }

    private void updateTransformCache(AdvancedPipelineSettings settings) {
        if (lastSettings == null || 
            settings.whacknetOffsetX != lastSettings.whacknetOffsetX || 
            settings.whacknetOffsetY != lastSettings.whacknetOffsetY ||
            settings.whacknetOffsetZ != lastSettings.whacknetOffsetZ ||
            settings.whacknetOffsetRoll != lastSettings.whacknetOffsetRoll ||
            settings.whacknetOffsetPitch != lastSettings.whacknetOffsetPitch ||
            settings.whacknetOffsetYaw != lastSettings.whacknetOffsetYaw) {
            
            Transform3d robotToCamera = new Transform3d(
                new Translation3d(settings.whacknetOffsetX, settings.whacknetOffsetY, settings.whacknetOffsetZ),
                new Rotation3d(
                    Units.degreesToRadians(settings.whacknetOffsetRoll),
                    Units.degreesToRadians(settings.whacknetOffsetPitch),
                    Units.degreesToRadians(settings.whacknetOffsetYaw)
                )
            );
            cachedRobotToCameraInverted = robotToCamera.inverse();
            lastSettings = settings;
        }
    }
}
