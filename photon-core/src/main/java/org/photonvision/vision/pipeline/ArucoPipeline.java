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

package org.photonvision.vision.pipeline;

import edu.wpi.first.apriltag.AprilTagPoseEstimate;
import edu.wpi.first.math.geometry.CoordinateSystem;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.math.util.Units;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.Objdetect;
import org.photonvision.common.configuration.ConfigManager;
import org.photonvision.common.dataflow.structures.Packet;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.math.MathUtils;
import org.photonvision.estimation.TargetModel;
import org.photonvision.estimation.VisionEstimation;
import org.photonvision.targeting.MultiTargetPNPResult;
import org.photonvision.targeting.PnpResult;
import org.photonvision.vision.aruco.ArucoDetectionResult;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.pipe.CVPipe.CVPipeResult;
import org.photonvision.vision.pipe.impl.*;
import org.photonvision.vision.pipe.impl.ArucoPoseEstimatorPipe.ArucoPoseEstimatorPipeParams;
import org.photonvision.vision.pipe.impl.MultiTargetPNPPipe.MultiTargetPNPPipeParams;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.target.TrackedTarget;
import org.photonvision.vision.target.TrackedTarget.TargetCalculationParameters;

public class ArucoPipeline extends CVPipeline<CVPipelineResult, ArucoPipelineSettings> {
    private static final Logger logger = new Logger(ArucoPipeline.class, LogGroup.VisionModule);

    private ArucoDetectionPipe arucoDetectionPipe = new ArucoDetectionPipe();
    private ArucoPoseEstimatorPipe singleTagPoseEstimatorPipe = new ArucoPoseEstimatorPipe();
    private final MultiTargetPNPPipe multiTagPNPPipe = new MultiTargetPNPPipe();
    private final CalculateFPSPipe calculateFPSPipe = new CalculateFPSPipe();

    public ArucoPipeline() {
        super(FrameThresholdType.GREYSCALE);
        settings = new ArucoPipelineSettings();
    }

    public ArucoPipeline(ArucoPipelineSettings settings) {
        super(FrameThresholdType.GREYSCALE);
        this.settings = settings;
    }

    @Override
    protected void setPipeParamsImpl() {
        var params = new ArucoDetectionPipeParams();
        // sanitize and record settings

        // for now, hard code tag width based on enum value
        // 2023/other: best guess is 6in
        double tagWidth = Units.inchesToMeters(6);
        TargetModel tagModel = TargetModel.kAprilTag16h5;

        params.tagFamily =
                switch (settings.tagFamily) {
                    case kTag36h11 -> {
                        // 2024 tag, 6.5in
                        tagWidth = Units.inchesToMeters(6.5);
                        tagModel = TargetModel.kAprilTag36h11;
                        yield Objdetect.DICT_APRILTAG_36h11;
                    }
                    case kTag16h5 -> {
                        // 2024 tag, 6.5in
                        tagWidth = Units.inchesToMeters(6);
                        tagModel = TargetModel.kAprilTag16h5;
                        yield Objdetect.DICT_APRILTAG_16h5;
                    }
                };

        int threshMinSize = Math.max(3, settings.threshWinSizes.getFirst());
        settings.threshWinSizes.setFirst(threshMinSize);
        params.threshMinSize = threshMinSize;
        int threshStepSize = Math.max(2, settings.threshStepSize);
        settings.threshStepSize = threshStepSize;
        params.threshStepSize = threshStepSize;
        int threshMaxSize = Math.max(threshMinSize, settings.threshWinSizes.getSecond());
        settings.threshWinSizes.setSecond(threshMaxSize);
        params.threshMaxSize = threshMaxSize;
        params.threshConstant = settings.threshConstant;

        params.useCornerRefinement = settings.useCornerRefinement;
        params.refinementMaxIterations = settings.refineNumIterations;
        params.refinementMinErrorPx = settings.refineMinErrorPx;
        params.useAruco3 = settings.useAruco3;
        params.aruco3MinMarkerSideRatio = settings.aruco3MinMarkerSideRatio;
        params.aruco3MinCanonicalImgSide = settings.aruco3MinCanonicalImgSide;
        arucoDetectionPipe.setParams(params);

        if (frameStaticProperties.cameraCalibration != null) {
            var cameraMatrix = frameStaticProperties.cameraCalibration.getCameraIntrinsicsMat();
            if (cameraMatrix != null && cameraMatrix.rows() > 0) {
                var estimatorParams =
                        new ArucoPoseEstimatorPipeParams(frameStaticProperties.cameraCalibration, tagWidth);
                singleTagPoseEstimatorPipe.setParams(estimatorParams);

                // TODO global state ew
                var atfl = ConfigManager.getInstance().getConfig().getApriltagFieldLayout();
                multiTagPNPPipe.setParams(
                        new MultiTargetPNPPipeParams(frameStaticProperties.cameraCalibration, atfl, tagModel));
            }
        }
    }

    @Override
    protected CVPipelineResult process(Frame frame, ArucoPipelineSettings settings) {
        long sumPipeNanosElapsed = 0L;

        if (frame.type != FrameThresholdType.GREYSCALE) {
            // We asked for a GREYSCALE frame, but didn't get one -- best we can do is give up
            return new CVPipelineResult(frame.sequenceID, 0, 0, List.of(), frame);
        }

        CVPipeResult<List<ArucoDetectionResult>> tagDetectionPipeResult =
                arucoDetectionPipe.run(frame.processedImage);
        sumPipeNanosElapsed += tagDetectionPipeResult.nanosElapsed;

        // If we want to debug the thresholding steps, draw the first step to the color image
        if (settings.debugThreshold) {
            drawThresholdFrame(
                    frame.processedImage.getMat(),
                    frame.colorImage.getMat(),
                    settings.threshWinSizes.getFirst(),
                    settings.threshConstant);
        }

        List<TrackedTarget> targetList = new ArrayList<>();
        for (ArucoDetectionResult detection : tagDetectionPipeResult.output) {
            // Populate target list for multitag
            // (TODO: Address circular dependencies. Multitag only requires corners and IDs, this should
            // not be necessary.)

            targetList.add(
                    new TrackedTarget(
                            detection,
                            null,
                            new TargetCalculationParameters(
                                    false, null, null, null, null, frameStaticProperties)));
        }

        // Do multi-tag pose estimation
        Optional<MultiTargetPNPResult> multiTagResult = Optional.empty();
        Optional<MultiTargetPNPResult> constrainedResult = Optional.empty();
        if (settings.solvePNPEnabled && settings.doMultiTarget) {
            var multiTagOutput = multiTagPNPPipe.run(targetList);
            sumPipeNanosElapsed += multiTagOutput.nanosElapsed;
            multiTagResult = multiTagOutput.output;
        }

        // Do single-tag pose estimation
        // Do single-tag pose estimation
        if (settings.solvePNPEnabled) {
            // DO NOT clear targetList here! Reuse targets to prevent double-undistorting points!
            var atfl = ConfigManager.getInstance().getConfig().getApriltagFieldLayout();

            for (int i = 0; i < tagDetectionPipeResult.output.size(); i++) {
                ArucoDetectionResult detection = tagDetectionPipeResult.output.get(i);
                TrackedTarget target = targetList.get(i); // Grab the existing target

                AprilTagPoseEstimate tagPoseEstimate = null;

                if (settings.doSingleTargetAlways
                        || !(multiTagResult.isPresent()
                                && multiTagResult.get().fiducialIDsUsed.contains((short) detection.getId()))) {
                    var poseResult = singleTagPoseEstimatorPipe.run(detection);
                    sumPipeNanosElapsed += poseResult.nanosElapsed;
                    tagPoseEstimate = poseResult.output;
                }

                if (tagPoseEstimate == null && multiTagResult.isPresent()) {
                    var tagPose = atfl.getTagPose(detection.getId());
                    if (tagPose.isPresent()) {
                        var camToTag =
                                new Transform3d(
                                        new Pose3d().plus(multiTagResult.get().estimatedPose.best), tagPose.get());
                        camToTag =
                                CoordinateSystem.convert(camToTag, CoordinateSystem.NWU(), CoordinateSystem.EDN());
                        tagPoseEstimate = new AprilTagPoseEstimate(camToTag, camToTag, 0, 0);
                    }
                }

                // Inject the pose into the existing target
                if (tagPoseEstimate != null) {
                    Transform3d bestPose =
                            tagPoseEstimate.error1 <= tagPoseEstimate.error2
                                    ? tagPoseEstimate.pose1
                                    : tagPoseEstimate.pose2;
                    Transform3d altPose =
                            tagPoseEstimate.error1 <= tagPoseEstimate.error2
                                    ? tagPoseEstimate.pose2
                                    : tagPoseEstimate.pose1;

                    bestPose = MathUtils.convertApriltagtoOpenCV(bestPose);
                    altPose = MathUtils.convertApriltagtoOpenCV(altPose);

                    target.setPoseAmbiguity(tagPoseEstimate.getAmbiguity());

                    // Create and set tvec/rvec directly
                    var tvec = new org.opencv.core.Mat(3, 1, org.opencv.core.CvType.CV_64FC1);
                    tvec.put(
                            0,
                            0,
                            bestPose.getTranslation().getX(),
                            bestPose.getTranslation().getY(),
                            bestPose.getTranslation().getZ());
                    target.setCameraRelativeTvec(tvec);
                    tvec.release(); // Clean up native memory immediately

                    var rvec = new org.opencv.core.Mat(3, 1, org.opencv.core.CvType.CV_64FC1);
                    MathUtils.rotationToOpencvRvec(bestPose.getRotation(), rvec);
                    target.setCameraRelativeRvec(rvec);
                    rvec.release();

                    target.setBestCameraToTarget3d(MathUtils.convertOpenCVtoPhotonTransform(bestPose));
                    target.setAltCameraToTarget3d(MathUtils.convertOpenCVtoPhotonTransform(altPose));
                }
            }
        }

        if (settings.solvePNPEnabled && settings.useGyroConstraint) {
            long constrainedStart = System.nanoTime();
            var gyroState = getGyroContext();
            if (gyroState != null) {
                TargetModel tagModel = TargetModel.kAprilTag36h11;
                if (settings.tagFamily != null && settings.tagFamily.name().equals("kTag16h5")) {
                    tagModel = TargetModel.kAprilTag16h5;
                }

                Transform3d robot2camera;
                if (this.dynamicRobotToCamera != null) {
                    robot2camera = this.dynamicRobotToCamera;
                } else {
                    robot2camera =
                            new Transform3d(
                                    new Translation3d(
                                            settings.whacknetOffsetX, settings.whacknetOffsetY, settings.whacknetOffsetZ),
                                    new Rotation3d(
                                            Units.degreesToRadians(settings.whacknetOffsetRoll),
                                            Units.degreesToRadians(settings.whacknetOffsetPitch),
                                            Units.degreesToRadians(settings.whacknetOffsetYaw)));
                }

                Pose3d robotPoseSeed = new Pose3d(0, 0, 0, new Rotation3d(0, 0, gyroState.yawRadians()));
                var atfl = ConfigManager.getInstance().getConfig().getApriltagFieldLayout();
                if (multiTagResult.isPresent()) {
                    robotPoseSeed =
                            new Pose3d()
                                    .plus(multiTagResult.get().estimatedPose.best)
                                    .transformBy(robot2camera.inverse());
                } else if (!targetList.isEmpty()) {
                    var firstTarget = targetList.get(0);
                    var tagPose = atfl.getTagPose(firstTarget.getFiducialId());
                    if (tagPose != null && tagPose.isPresent()) {
                        Pose3d camPose =
                                tagPose.get().transformBy(firstTarget.getBestCameraToTarget3d().inverse());
                        robotPoseSeed = camPose.transformBy(robot2camera.inverse());
                    }
                }

                var constrainedResultOpt =
                        VisionEstimation.estimateRobotPoseConstrainedSolvepnp(
                                frameStaticProperties.cameraCalibration.cameraIntrinsics.getAsWpilibMat(),
                                frameStaticProperties.cameraCalibration.distCoeffs.getAsWpilibMat(),
                                TrackedTarget.simpleFromTrackedTargets(targetList),
                                robot2camera,
                                robotPoseSeed,
                                atfl,
                                tagModel,
                                false,
                                new Rotation2d(gyroState.yawRadians()),
                                settings.gyroWeight);

                if (constrainedResultOpt != null && constrainedResultOpt.isPresent()) {
                    var constrainedResultVal = constrainedResultOpt.get();
                    var fieldToRobot = constrainedResultVal.best;
                    var fieldToCamera = fieldToRobot.plus(robot2camera);

                    var newPnpResult =
                            new PnpResult(
                                    fieldToCamera,
                                    fieldToCamera,
                                    0,
                                    constrainedResultVal.bestReprojErr,
                                    constrainedResultVal.bestReprojErr);

                    List<Short> idsUsed = new ArrayList<>();
                    for (var t : targetList) {
                        var tagPose = atfl.getTagPose(t.getFiducialId());
                        if (tagPose != null && tagPose.isPresent()) {
                            idsUsed.add((short) t.getFiducialId());
                        }
                    }

                    constrainedResult = Optional.of(new MultiTargetPNPResult(newPnpResult, idsUsed));
                }
            }
            sumPipeNanosElapsed += (System.nanoTime() - constrainedStart);
        }

        if (targetList.size() > Packet.MAX_ARRAY_LEN) {
            logger.error(
                    "We have " + targetList.size() + " targets! Arbitrarily dropping some on the floor");
            targetList = targetList.subList(0, Packet.MAX_ARRAY_LEN);
        }

        var fpsResult = calculateFPSPipe.run(null);
        var fps = fpsResult.output;

        return new CVPipelineResult(
                frame.sequenceID,
                sumPipeNanosElapsed,
                fps,
                targetList,
                multiTagResult,
                constrainedResult,
                frame);
    }

    private void drawThresholdFrame(Mat greyMat, Mat outputMat, int windowSize, double constant) {
        if (windowSize % 2 == 0) windowSize++;
        Imgproc.adaptiveThreshold(
                greyMat,
                outputMat,
                255,
                Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV,
                windowSize,
                constant);
    }

    @Override
    public void release() {
        arucoDetectionPipe.release();
        singleTagPoseEstimatorPipe.release();
        arucoDetectionPipe = null;
        singleTagPoseEstimatorPipe = null;
        super.release();
    }
}
