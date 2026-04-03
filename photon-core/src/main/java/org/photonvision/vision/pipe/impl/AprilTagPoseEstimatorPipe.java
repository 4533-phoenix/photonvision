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

package org.photonvision.vision.pipe.impl;

import edu.wpi.first.apriltag.AprilTagDetection;
import edu.wpi.first.apriltag.AprilTagPoseEstimate;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation3d;
import java.util.ArrayList;
import java.util.List;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point3;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.opencv.Releasable;
import org.photonvision.vision.pipe.CVPipe;

public class AprilTagPoseEstimatorPipe
        extends CVPipe<
                AprilTagDetection,
                AprilTagPoseEstimate,
                AprilTagPoseEstimatorPipe.AprilTagPoseEstimatorPipeParams>
        implements Releasable {

    private final MatOfPoint2f imagePoints = new MatOfPoint2f(Mat.zeros(4, 1, CvType.CV_32FC2));
    private final List<Mat> rvecs = new ArrayList<>();
    private final List<Mat> tvecs = new ArrayList<>();
    private final Mat rvec = Mat.zeros(3, 1, CvType.CV_32F);
    private final Mat tvec = Mat.zeros(3, 1, CvType.CV_32F);
    private final Mat reprojectionErrors = Mat.zeros(2, 1, CvType.CV_32F);
    private MatOfPoint3f objectPoints = new MatOfPoint3f();
    private final int kNaNRetries = 1;

    public AprilTagPoseEstimatorPipe() {
        super();
    }

    private Translation3d tvecToTranslation3d(Mat mat) {
        double[] tArr = new double[3];
        mat.get(0, 0, tArr);
        return new Translation3d(tArr[0], tArr[1], tArr[2]);
    }

    private Rotation3d rvecToRotation3d(Mat mat) {
        double[] rArr = new double[3];
        mat.get(0, 0, rArr);
        return new Rotation3d(VecBuilder.fill(rArr[0], rArr[1], rArr[2]));
    }

    @Override
    protected AprilTagPoseEstimate process(AprilTagDetection in) {
        double[] corners = in.getCorners();

        // Pass corners in standard WPILib [BL, BR, TR, TL] order
        imagePoints.put(0, 0, new float[] {(float) corners[0], (float) corners[1]}); // BL
        imagePoints.put(1, 0, new float[] {(float) corners[2], (float) corners[3]}); // BR
        imagePoints.put(2, 0, new float[] {(float) corners[4], (float) corners[5]}); // TR
        imagePoints.put(3, 0, new float[] {(float) corners[6], (float) corners[7]}); // TL

        float[] reprojErrors = new float[2];
        for (int i = 0; i < kNaNRetries + 1; i++) {
            Calib3d.solvePnPGeneric(
                    objectPoints,
                    imagePoints,
                    params.calibration().getCameraIntrinsicsMat(),
                    params.calibration().getDistCoeffsMat(),
                    rvecs,
                    tvecs,
                    false,
                    Calib3d.SOLVEPNP_IPPE_SQUARE,
                    rvec,
                    tvec,
                    reprojectionErrors);

            reprojectionErrors.get(0, 0, reprojErrors);
            if (!Double.isNaN(reprojErrors[0])) break;
            else {
                double[] br = imagePoints.get(0, 0);
                br[0] -= 0.001;
                br[1] -= 0.001;
                imagePoints.put(0, 0, br);
            }
        }

        if (tvecs.isEmpty())
            return new AprilTagPoseEstimate(new Transform3d(), new Transform3d(), 0, 0);

        return new AprilTagPoseEstimate(
                new Transform3d(tvecToTranslation3d(tvecs.get(0)), rvecToRotation3d(rvecs.get(0))),
                new Transform3d(tvecToTranslation3d(tvecs.get(1)), rvecToRotation3d(rvecs.get(1))),
                reprojErrors[0],
                reprojErrors[1]);
    }

    @Override
    public void setParams(AprilTagPoseEstimatorPipe.AprilTagPoseEstimatorPipeParams newParams) {
        if (this.params == null || this.params.tagWidth() != newParams.tagWidth()) {
            double tagSize = newParams.tagWidth();
            // Match WPILib OrthogonalIteration layout
            objectPoints.fromArray(
                    new Point3(-tagSize / 2, tagSize / 2, 0),
                    new Point3(tagSize / 2, tagSize / 2, 0),
                    new Point3(tagSize / 2, -tagSize / 2, 0),
                    new Point3(-tagSize / 2, -tagSize / 2, 0));
        }
        super.setParams(newParams);
    }

    @Override
    public void release() {
        imagePoints.release();
        for (var m : rvecs) m.release();
        rvecs.clear();
        for (var m : tvecs) m.release();
        tvecs.clear();
        rvec.release();
        tvec.release();
        reprojectionErrors.release();
        if (objectPoints != null) objectPoints.release();
    }

    public static record AprilTagPoseEstimatorPipeParams(
            double tagWidth, CameraCalibrationCoefficients calibration) {}
}
