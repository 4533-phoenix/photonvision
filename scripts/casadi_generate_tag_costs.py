#!/usr/bin/env python3

import os
from pathlib import Path

import casadi as ca
from casadi import *
from numpy import *


def generate_costs(num_tags, robot_heading_free):

    # Decision variables
    robot_x = ca.SX.sym("robot_x")
    robot_y = ca.SX.sym("robot_y")
    robot_z = 0  # Fixed at 0
    robot_θ = ca.SX.sym("robot_θ")

    # External gyro measurement - potentially unused
    gyro_θ = ca.SX.sym("gyro_θ")
    gyro_error_scale_fac = ca.SX.sym("gyro_r")

    # Precompute trigonometric functions
    sinθ = ca.sin(robot_θ)
    cosθ = ca.cos(robot_θ)

    # Transformation matrices
    field2robot = ca.vertcat(
        ca.horzcat(cosθ, -sinθ, 0, robot_x),
        ca.horzcat(sinθ, cosθ, 0, robot_y),
        ca.horzcat(0, 0, 1, robot_z),
        ca.horzcat(0, 0, 0, 1),
    )

    robot2camera = ca.SX.sym("robot2camera", 4, 4)

    field2camera = field2robot @ robot2camera

    # 4 corners per tag
    NUM_LANDMARKS = 4 * num_tags

    # Points in the field (homogeneous coordinates). Rows are [x, y, z, 1]
    field2points = ca.SX.sym("field2landmark", 4, NUM_LANDMARKS)

    # Observed points in the image
    point_observations = ca.SX.sym("observations_px", 2, NUM_LANDMARKS)

    # landmarks in camera frame
    camera2field = ca.inv(field2camera)
    camera2point = camera2field @ field2points

    # Camera frame coordinates
    x = camera2point[0, :]
    y = camera2point[1, :]
    z = camera2point[2, :]

    # Observed coordinates
    # Note that instead of using camera calibration, we expect the caller to provide
    # "normalized pixel coordinates". Convert from (u, v) coordinates to normalized
    # (x'', y'') coordinates with:
    # x'' = (u - c_x) / f_x
    # y'' = (u - c_y) / f_y
    xʼʼ_observed = point_observations[0, :]
    yʼʼ_observed = point_observations[1, :]

    # Where we expected to see the landmarks at, in normalized pixel coordinates
    xʼʼ = x / z
    yʼʼ = y / z

    # Reprojection error
    xʼʼ_err = xʼʼ - xʼʼ_observed
    yʼʼ_err = yʼʼ - yʼʼ_observed

    # Frobenius norm - sqrt(sum squared of each component). Square to remove sqrt
    J = ca.norm_fro(xʼʼ_err) ** 2 + ca.norm_fro(yʼʼ_err) ** 2

    # And penalize gyro error excursion
    if not robot_heading_free:
        J += gyro_error_scale_fac * ((gyro_θ - robot_θ) ** 2)

    x_vec = ca.vertcat(robot_x, robot_y, robot_θ)

    hess_J, _ = ca.hessian(J, x_vec)
    grad_J = ca.gradient(J, x_vec)

    func_base_name = f"J_{num_tags}_tags{'_heading_free' if robot_heading_free else '_heading_fixed'}"
    func_inputs = [
        robot_x, robot_y, robot_θ,
        robot2camera, field2points, point_observations,
        gyro_θ, gyro_error_scale_fac,
    ]
    func_input_names = [
        "robot_x", "robot_y", "robot_θ",
        "robot2camera", "field2points", "point_observations",
        "gyro_θ", "gyro_error_scale_fac",
    ]

    J_func = ca.Function(f"calc_{func_base_name}", func_inputs, [J], func_input_names, ["J"])
    grad_func = ca.Function(f"calc_grad{func_base_name}", func_inputs, [grad_J], func_input_names, ["grad_J"])
    hess_func = ca.Function(f"calc_hess{func_base_name}", func_inputs, [hess_J], func_input_names, ["hess_J"])

    cg = CodeGenerator(
        f"constrained_solvepnp_{num_tags}_tags_{'free' if robot_heading_free else 'fixed'}",
        {"with_header": True, "cpp": False},
    )

    cg.add(J_func)
    cg.add(grad_func)
    cg.add(hess_func)
    output_dir = str(
        Path(__file__).parent.parent
        / "photon-targeting" / "src" / "main" / "native" / "cpp" / "photon" / "constrained_solvepnp" / "generate"
    )
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    cg.generate(output_dir + os.path.sep)

    return J, grad_J, hess_J


def generate_costs_6dof(num_tags, robot_heading_free):
    # Decision variables
    robot_x = ca.SX.sym("robot_x")
    robot_y = ca.SX.sym("robot_y")
    robot_z = ca.SX.sym("robot_z")
    
    robot_roll = ca.SX.sym("robot_roll")
    robot_pitch = ca.SX.sym("robot_pitch")
    robot_yaw = ca.SX.sym("robot_yaw")

    # External gyro measurements
    gyro_roll = ca.SX.sym("gyro_roll")
    gyro_pitch = ca.SX.sym("gyro_pitch")
    gyro_yaw = ca.SX.sym("gyro_yaw")
    gyro_error_scale_fac = ca.SX.sym("gyro_r")

    # Precompute trigonometric functions for 3D rotation
    cr = ca.cos(robot_roll)
    sr = ca.sin(robot_roll)
    cp = ca.cos(robot_pitch)
    sp = ca.sin(robot_pitch)
    cy = ca.cos(robot_yaw)
    sy = ca.sin(robot_yaw)

    # 3D Rotation matrices
    Rx = ca.vertcat(
        ca.horzcat(1, 0, 0),
        ca.horzcat(0, cr, -sr),
        ca.horzcat(0, sr, cr)
    )
    Ry = ca.vertcat(
        ca.horzcat(cp, 0, sp),
        ca.horzcat(0, 1, 0),
        ca.horzcat(-sp, 0, cp)
    )
    Rz = ca.vertcat(
        ca.horzcat(cy, -sy, 0),
        ca.horzcat(sy, cy, 0),
        ca.horzcat(0, 0, 1)
    )
    
    # Combined rotation matrix
    R = Rz @ Ry @ Rx

    # Transformation matrix
    field2robot = ca.vertcat(
        ca.horzcat(R[0, 0], R[0, 1], R[0, 2], robot_x),
        ca.horzcat(R[1, 0], R[1, 1], R[1, 2], robot_y),
        ca.horzcat(R[2, 0], R[2, 1], R[2, 2], robot_z),
        ca.horzcat(0, 0, 0, 1),
    )

    robot2camera = ca.SX.sym("robot2camera", 4, 4)
    field2camera = field2robot @ robot2camera

    # 4 corners per tag
    NUM_LANDMARKS = 4 * num_tags

    field2points = ca.SX.sym("field2landmark", 4, NUM_LANDMARKS)
    point_observations = ca.SX.sym("observations_px", 2, NUM_LANDMARKS)

    camera2field = ca.inv(field2camera)
    camera2point = camera2field @ field2points

    x = camera2point[0, :]
    y = camera2point[1, :]
    z = camera2point[2, :]

    xʼʼ_observed = point_observations[0, :]
    yʼʼ_observed = point_observations[1, :]

    xʼʼ = x / z
    yʼʼ = y / z

    xʼʼ_err = xʼʼ - xʼʼ_observed
    yʼʼ_err = yʼʼ - yʼʼ_observed

    # Reprojection error base cost
    J = ca.norm_fro(xʼʼ_err) ** 2 + ca.norm_fro(yʼʼ_err) ** 2

    # Penalize gyro error excursion across all 3 rotational axes
    if not robot_heading_free:
        J += gyro_error_scale_fac * ((gyro_yaw - robot_yaw) ** 2)
        J += gyro_error_scale_fac * ((gyro_pitch - robot_pitch) ** 2)
        J += gyro_error_scale_fac * ((gyro_roll - robot_roll) ** 2)

    # State vector is now 6 elements
    x_vec = ca.vertcat(robot_x, robot_y, robot_z, robot_roll, robot_pitch, robot_yaw)

    # Hessian + gradient
    hess_J, _ = ca.hessian(J, x_vec)
    grad_J = ca.gradient(J, x_vec)

    # Name appending _6dof
    func_base_name = f"J_6dof_{num_tags}_tags{'_heading_free' if robot_heading_free else '_heading_fixed'}"
    func_inputs = [
        robot_x, robot_y, robot_z,
        robot_roll, robot_pitch, robot_yaw,
        robot2camera, field2points, point_observations,
        gyro_roll, gyro_pitch, gyro_yaw,
        gyro_error_scale_fac,
    ]
    func_input_names = [
        "robot_x", "robot_y", "robot_z",
        "robot_roll", "robot_pitch", "robot_yaw",
        "robot2camera", "field2points", "point_observations",
        "gyro_roll", "gyro_pitch", "gyro_yaw",
        "gyro_error_scale_fac",
    ]

    J_func = ca.Function(f"calc_{func_base_name}", func_inputs, [J], func_input_names, ["J"])
    grad_func = ca.Function(f"calc_grad_{func_base_name}", func_inputs, [grad_J], func_input_names, ["grad_J"])
    hess_func = ca.Function(f"calc_hess_{func_base_name}", func_inputs, [hess_J], func_input_names, ["hess_J"])

    cg = CodeGenerator(
        f"constrained_solvepnp_6dof_{num_tags}_tags_{'free' if robot_heading_free else 'fixed'}",
        {"with_header": True, "cpp": False},
    )

    cg.add(J_func)
    cg.add(grad_func)
    cg.add(hess_func)
    output_dir = str(
        Path(__file__).parent.parent
        / "photon-targeting" / "src" / "main" / "native" / "cpp" / "photon" / "constrained_solvepnp" / "generate"
    )
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    cg.generate(output_dir + os.path.sep)

    return J, grad_J, hess_J


if __name__ == "__main__":
    for i in range(1, 11):
        for j in [True, False]:
            print(f"Generating 3-DOF: {i} tags, heading_free={j}")
            generate_costs(i, j)
            print(f"Generating 6-DOF: {i} tags, heading_free={j}")
            generate_costs_6dof(i, j)