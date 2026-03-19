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

import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.math.util.Units;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
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
    private static final int PORT = 7001;

    private static final double AMBIGUITY_CUTOFF = 0.05;
    private static final double SINGLE_TAG_POSE_CUTOFF_METERS = 4.0;
    
    private static final double[] SINGLE_TAG_BASE_STD_DEVS = {2.0, 2.0, 4.0};
    private static final double[] MULTI_TAG_BASE_STD_DEVS = {0.5, 0.5, 1.0};
    private static final double[] INVALID_STD_DEVS = {0.0, 0.0, 0.0};

    private DatagramSocket socket;
    private final Supplier<CVPipelineSettings> settingsSupplier;
    
    private final ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket packet = new DatagramPacket(buf.array(), 64);
    
    private String lastIpString = "";
    private InetAddress resolvedAddress;
    private Transform3d cachedRobotToCameraInverted = new Transform3d();
    private AdvancedPipelineSettings lastSettings = null;

    public WhacknetPublisher(Supplier<CVPipelineSettings> settingsSupplier) {
        this.settingsSupplier = settingsSupplier;
        try {
            socket = new DatagramSocket();
        } catch (Exception e) {
            logger.error("Failed to initialize Whacknet UDP socket", e);
        }
    }

    @Override
    public void accept(CVPipelineResult result) {
        CVPipelineSettings baseSettings = settingsSupplier.get();
        if (!(baseSettings instanceof AdvancedPipelineSettings settings)) return;
        if (!settings.udpSenderEnabled) return;

        double poseX = 0.0, poseY = 0.0, poseRot = 0.0;
        double[] stdDevs = INVALID_STD_DEVS;
        int usedTagCount = 0;

        if (result.hasTargets()) {
            Pose3d fieldToCamera = null;

            if (result.multiTagResult != null && result.multiTagResult.isPresent()) {
                var mtr = result.multiTagResult.get();
                var bestTransform = mtr.estimatedPose.best;
                fieldToCamera = new Pose3d(bestTransform.getTranslation(), bestTransform.getRotation());
                usedTagCount = mtr.fiducialIDsUsed.size();
            } 
            else if (!result.targets.isEmpty() && result.targets.get(0).isFiducial()) {
                var target = result.targets.get(0);
                if (isUsableSingleTag(target)) {
                    var atfl = ConfigManager.getInstance().getConfig().getApriltagFieldLayout();
                    var tagPoseOpt = atfl.getTagPose(target.getFiducialId());
                    if (tagPoseOpt.isPresent()) {
                        fieldToCamera = tagPoseOpt.get().transformBy(target.getBestCameraToTarget3d().inverse());
                        usedTagCount = 1;
                    }
                }
            }

            if (fieldToCamera != null) {
                updateTransformCache(settings);
                Pose3d fieldToRobot = fieldToCamera.transformBy(cachedRobotToCameraInverted);
                
                poseX = fieldToRobot.getX();
                poseY = fieldToRobot.getY();
                poseRot = fieldToRobot.getRotation().getZ();

                stdDevs = calculateScaledStdDevs(fieldToRobot, result.targets, usedTagCount);
            }
        }

        buf.clear();
        buf.putDouble(poseX);
        buf.putDouble(poseY);
        buf.putDouble(poseRot);
        buf.putDouble(stdDevs[0]);
        buf.putDouble(stdDevs[1]);
        buf.putDouble(stdDevs[2]);
        
        long tsMicros = MathUtils.nanosToMicros(MathUtils.wpiNanoTime());
        buf.putLong(tsMicros);
        
        buf.put((byte) settings.udpCameraId);
        buf.put((byte) usedTagCount);
        
        while(buf.hasRemaining()) buf.put((byte) 0);

        sendPacket();
    }

    private double[] calculateScaledStdDevs(Pose3d robotPose, List<TrackedTarget> targets, int tagCount) {
        if (tagCount == 0) return INVALID_STD_DEVS;

        var atfl = ConfigManager.getInstance().getConfig().getApriltagFieldLayout();
        double totalDistance = 0;
        int validTagsForDist = 0;

        for (var target : targets) {
            var tagPose = atfl.getTagPose(target.getFiducialId());
            if (tagPose.isEmpty()) continue;
            
            double dist = tagPose.get().getTranslation().getDistance(robotPose.getTranslation());
            totalDistance += dist;
            validTagsForDist++;
        }

        double avgDist = (validTagsForDist > 0) ? (totalDistance / validTagsForDist) : 4.0;
        double[] base = (tagCount > 1) ? MULTI_TAG_BASE_STD_DEVS : SINGLE_TAG_BASE_STD_DEVS;
        double scaler = 1.0 + (Math.pow(avgDist, 2) / 30.0);
        
        return new double[] {
            base[0] * scaler,
            base[1] * scaler,
            base[2] * scaler
        };
    }

    private boolean isUsableSingleTag(TrackedTarget target) {
        double ambiguity = target.getPoseAmbiguity();
        double distance = target.getBestCameraToTarget3d().getTranslation().getNorm();

        return (ambiguity >= 0 && ambiguity < AMBIGUITY_CUTOFF) 
                && (distance < SINGLE_TAG_POSE_CUTOFF_METERS);
    }

    private void sendPacket() {
        try {
            String ip = ConfigManager.getInstance().getConfig().getNetworkConfig().ntServerAddress;
            if (ip == null || ip.isEmpty() || "0".equals(ip)) {
                ip = "127.0.0.1";
            }

            if (!ip.equals(lastIpString)) {
                resolvedAddress = InetAddress.getByName(ip);
                lastIpString = ip;
                packet.setAddress(resolvedAddress);
                packet.setPort(PORT);
            }

            if (socket != null && resolvedAddress != null) {
                socket.send(packet);
            }
        } catch (Exception e) {}
    }

    private void updateTransformCache(AdvancedPipelineSettings settings) {
        if (lastSettings == null || 
            settings.udpOffsetX != lastSettings.udpOffsetX || 
            settings.udpOffsetY != lastSettings.udpOffsetY ||
            settings.udpOffsetZ != lastSettings.udpOffsetZ ||
            settings.udpOffsetRoll != lastSettings.udpOffsetRoll ||
            settings.udpOffsetPitch != lastSettings.udpOffsetPitch ||
            settings.udpOffsetYaw != lastSettings.udpOffsetYaw) {
            
            Transform3d robotToCamera = new Transform3d(
                new Translation3d(settings.udpOffsetX, settings.udpOffsetY, settings.udpOffsetZ),
                new Rotation3d(
                    Units.degreesToRadians(settings.udpOffsetRoll),
                    Units.degreesToRadians(settings.udpOffsetPitch),
                    Units.degreesToRadians(settings.udpOffsetYaw)
                )
            );
            cachedRobotToCameraInverted = robotToCamera.inverse();
            lastSettings = settings;
        }
    }
}
