/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

#include <span>
#include <vector>

#include "org_photonvision_jni_ConstrainedSolvepnpJni.h"
#include "photon/constrained_solvepnp/wrap/casadi_wrapper.h"

std::vector<double> convertJDoubleArray(JNIEnv* env, jdoubleArray array) {
  jsize length = env->GetArrayLength(array);
  std::vector<double> result(length);
  env->GetDoubleArrayRegion(array, 0, length, result.data());
  return result;
}

jdoubleArray createJDoubleArray(JNIEnv* env, const std::span<double> vec) {
  jdoubleArray array = env->NewDoubleArray(vec.size());
  env->SetDoubleArrayRegion(array, 0, vec.size(), vec.data());
  return array;
}

extern "C" {

/*
 * ORIGINAL 3-DOF OPTIMIZATION (X, Y, Yaw)
 * Class:     org_photonvision_jni_ConstrainedSolvepnpJni
 * Method:    do_optimization
 * Signature: (ZI[D[D[D[D[DDD)[D
 */
JNIEXPORT jdoubleArray JNICALL
Java_org_photonvision_jni_ConstrainedSolvepnpJni_do_1optimization
  (JNIEnv* env, jclass, jboolean headingFree, jint nTags,
   jdoubleArray cameraCal, jdoubleArray robot2camera, jdoubleArray xGuess,
   jdoubleArray field2points, jdoubleArray pointObservations, jdouble gyro_θ,
   jdouble gyro_error_scale_fac)
{
  auto cameraCalVec = convertJDoubleArray(env, cameraCal);
  auto robot2cameraVec = convertJDoubleArray(env, robot2camera);
  auto xGuessVec = convertJDoubleArray(env, xGuess);
  auto field2pointsVec = convertJDoubleArray(env, field2points);
  auto pointObservationsVec = convertJDoubleArray(env, pointObservations);

  constrained_solvepnp::CameraCalibration cameraCal_{
      cameraCalVec[0],
      cameraCalVec[1],
      cameraCalVec[2],
      cameraCalVec[3],
  };
  Eigen::Map<Eigen::Matrix<double, 4, 4, Eigen::RowMajor>> robot2cameraMat(
      robot2cameraVec.data());
  Eigen::Map<Eigen::Matrix<double, 3, 1>> xGuessMat(xGuessVec.data());
  Eigen::Map<Eigen::Matrix<double, 4, Eigen::Dynamic, Eigen::RowMajor>>
      field2pointsMat(field2pointsVec.data(), 4, field2pointsVec.size() / 4);
  Eigen::Map<Eigen::Matrix<double, 2, Eigen::Dynamic, Eigen::RowMajor>>
      pointObservationsMat(pointObservationsVec.data(), 2,
                           pointObservationsVec.size() / 2);

  wpi::expected<constrained_solvepnp::RobotStateMat, slp::ExitStatus> result =
      constrained_solvepnp::do_optimization(
          headingFree, nTags, cameraCal_, robot2cameraMat, xGuessMat,
          field2pointsMat, pointObservationsMat, gyro_θ, gyro_error_scale_fac);

  if (result) {
    std::vector<double> resultVec{result->data(),
                                  result->data() + result->size()};
    return createJDoubleArray(env, resultVec);
  } else {
    return nullptr;
  }
}

/*
 * NEW 6-DOF OPTIMIZATION (X, Y, Z, Roll, Pitch, Yaw)
 * Class:     org_photonvision_jni_ConstrainedSolvepnpJni
 * Method:    do_optimization_6dof
 * Signature: (ZI[D[D[D[D[D[DD)[D
 */
JNIEXPORT jdoubleArray JNICALL
Java_org_photonvision_jni_ConstrainedSolvepnpJni_do_1optimization_16dof
  (JNIEnv* env, jclass, jboolean headingFree, jint nTags,
   jdoubleArray cameraCal, jdoubleArray robot2camera, jdoubleArray xGuess6D,
   jdoubleArray field2points, jdoubleArray pointObservations, jdoubleArray gyroMeas3D,
   jdouble gyro_error_scale_fac)
{
  auto cameraCalVec = convertJDoubleArray(env, cameraCal);
  auto robot2cameraVec = convertJDoubleArray(env, robot2camera);
  auto xGuessVec = convertJDoubleArray(env, xGuess6D);
  auto field2pointsVec = convertJDoubleArray(env, field2points);
  auto pointObservationsVec = convertJDoubleArray(env, pointObservations);
  auto gyroMeasVec = convertJDoubleArray(env, gyroMeas3D);

  constrained_solvepnp::CameraCalibration cameraCal_{
      cameraCalVec[0],
      cameraCalVec[1],
      cameraCalVec[2],
      cameraCalVec[3],
  };
  Eigen::Map<Eigen::Matrix<double, 4, 4, Eigen::RowMajor>> robot2cameraMat(
      robot2cameraVec.data());
  
  // MAP TO 6x1 MAT INSTEAD OF 3x1 (X, Y, Z, Roll, Pitch, Yaw)
  Eigen::Map<Eigen::Matrix<double, 6, 1>> xGuessMat(xGuessVec.data());
  
  // MAP GYRO TO 3x1 MAT INSTEAD OF SCALAR (Roll, Pitch, Yaw)
  Eigen::Map<Eigen::Matrix<double, 3, 1>> gyroMeasMat(gyroMeasVec.data());

  Eigen::Map<Eigen::Matrix<double, 4, Eigen::Dynamic, Eigen::RowMajor>>
      field2pointsMat(field2pointsVec.data(), 4, field2pointsVec.size() / 4);
  Eigen::Map<Eigen::Matrix<double, 2, Eigen::Dynamic, Eigen::RowMajor>>
      pointObservationsMat(pointObservationsVec.data(), 2,
                           pointObservationsVec.size() / 2);

  // You will need to implement do_optimization_6dof inside casadi_wrapper.h
  // It needs to return a 6x1 RobotState6DMat instead of a 3x1
  wpi::expected<Eigen::Matrix<double, 6, 1>, slp::ExitStatus> result =
      constrained_solvepnp::do_optimization_6dof(
          headingFree, nTags, cameraCal_, robot2cameraMat, xGuessMat,
          field2pointsMat, pointObservationsMat, gyroMeasMat, gyro_error_scale_fac);

  if (result) {
    std::vector<double> resultVec{result->data(),
                                  result->data() + result->size()};
    return createJDoubleArray(env, resultVec);
  } else {
    return nullptr;
  }
}

}  // extern "C"
