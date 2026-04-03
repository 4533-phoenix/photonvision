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
import edu.wpi.first.apriltag.AprilTagDetector;
import java.util.List;
import org.photonvision.vision.apriltag.AprilTagFamily;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.opencv.Releasable;
import org.photonvision.vision.pipe.CVPipe;

public class AprilTagDetectionPipe
        extends CVPipe<
                CVMat, List<AprilTagDetection>, AprilTagDetectionPipe.AprilTagDetectionPipeParams>
        implements Releasable {
    private AprilTagDetector m_detector = new AprilTagDetector();

    public AprilTagDetectionPipe() {
        super();

        m_detector.addFamily("tag16h5");
        m_detector.addFamily("tag36h11");
    }

    @Override
    protected List<AprilTagDetection> process(CVMat in) {
        if (in.getMat().empty()) {
            return List.of();
        }

        if (m_detector == null) {
            throw new RuntimeException("Apriltag detector was released!");
        }

        var ret = m_detector.detect(in.getMat());

        if (ret == null) {
            return List.of();
        }

        return List.of(ret);
    }

    @Override
    public void setParams(AprilTagDetectionPipeParams newParams) {
        boolean changed = false;

        if (this.params == null) {
            changed = true;
        } else {
            if (this.params.family() != newParams.family()) changed = true;

            // Manual check for Detector Config
            var oldCfg = this.params.detectorParams();
            var newCfg = newParams.detectorParams();
            if (oldCfg.numThreads != newCfg.numThreads
                    || oldCfg.quadDecimate != newCfg.quadDecimate
                    || oldCfg.quadSigma != newCfg.quadSigma
                    || oldCfg.refineEdges != newCfg.refineEdges) changed = true;

            // Manual check for Quad Thresholds
            var oldQuad = this.params.quadParams();
            var newQuad = newParams.quadParams();
            if (oldQuad.minClusterPixels != newQuad.minClusterPixels
                    || oldQuad.maxNumMaxima != newQuad.maxNumMaxima
                    || oldQuad.criticalAngle != newQuad.criticalAngle
                    || oldQuad.maxLineFitMSE != newQuad.maxLineFitMSE
                    || oldQuad.minWhiteBlackDiff != newQuad.minWhiteBlackDiff
                    || oldQuad.deglitch != newQuad.deglitch) changed = true;
        }

        // Only incur JNI and C++ memory reallocation overhead if settings changed
        if (changed) {
            m_detector.setConfig(newParams.detectorParams());
            m_detector.setQuadThresholdParameters(newParams.quadParams());
            m_detector.clearFamilies();
            m_detector.addFamily(newParams.family().getNativeName());
        }
        super.setParams(newParams);
    }

    @Override
    public void release() {
        m_detector.close();
        m_detector = null;
    }

    public static record AprilTagDetectionPipeParams(
            AprilTagFamily family,
            AprilTagDetector.Config detectorParams,
            AprilTagDetector.QuadThresholdParameters quadParams) {}
}
