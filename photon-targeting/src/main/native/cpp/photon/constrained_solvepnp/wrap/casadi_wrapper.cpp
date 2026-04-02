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

#include "photon/constrained_solvepnp/wrap/casadi_wrapper.h"

#include <cstdio>
#include <optional>

#include <Eigen/Cholesky>
#include <Eigen/Core>
#include <Eigen/LU>
#include <fmt/core.h>
#include <frc/fmt/Eigen.h>
#include <wpi/timestamp.h>

#include "../generate/constrained_solvepnp_10_tags_fixed.h"
#include "../generate/constrained_solvepnp_10_tags_free.h"
#include "../generate/constrained_solvepnp_1_tags_fixed.h"
#include "../generate/constrained_solvepnp_1_tags_free.h"
#include "../generate/constrained_solvepnp_2_tags_fixed.h"
#include "../generate/constrained_solvepnp_2_tags_free.h"
#include "../generate/constrained_solvepnp_3_tags_fixed.h"
#include "../generate/constrained_solvepnp_3_tags_free.h"
#include "../generate/constrained_solvepnp_4_tags_fixed.h"
#include "../generate/constrained_solvepnp_4_tags_free.h"
#include "../generate/constrained_solvepnp_5_tags_fixed.h"
#include "../generate/constrained_solvepnp_5_tags_free.h"
#include "../generate/constrained_solvepnp_6_tags_fixed.h"
#include "../generate/constrained_solvepnp_6_tags_free.h"
#include "../generate/constrained_solvepnp_7_tags_fixed.h"
#include "../generate/constrained_solvepnp_7_tags_free.h"
#include "../generate/constrained_solvepnp_8_tags_fixed.h"
#include "../generate/constrained_solvepnp_8_tags_free.h"
#include "../generate/constrained_solvepnp_9_tags_fixed.h"
#include "../generate/constrained_solvepnp_9_tags_free.h"

#include "../generate/constrained_solvepnp_6dof_10_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_10_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_1_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_1_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_2_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_2_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_3_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_3_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_4_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_4_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_5_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_5_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_6_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_6_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_7_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_7_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_8_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_8_tags_free.h"
#include "../generate/constrained_solvepnp_6dof_9_tags_fixed.h"
#include "../generate/constrained_solvepnp_6dof_9_tags_free.h"

constexpr bool VERBOSE = false;

struct Problem {
  int numTags;
  bool headingFree;
  int (*calc_J)(const casadi_real** arg, casadi_real** res, casadi_int* iw,
                casadi_real* w, int mem);
  int (*calc_gradJ)(const casadi_real** arg, casadi_real** res, casadi_int* iw,
                    casadi_real* w, int mem);
  int (*calc_hessJ)(const casadi_real** arg, casadi_real** res, casadi_int* iw,
                    casadi_real* w, int mem);
};

static std::optional<Problem> createProblem(int numTags, bool heading_free) {
#define MAKE_P(tags, suffix)                                         \
  Problem{tags, heading_free, calc_J_##tags##_tags_heading_##suffix, \
          calc_gradJ_##tags##_tags_heading_##suffix,                 \
          calc_hessJ_##tags##_tags_heading_##suffix}
#define MAKE_CASE(n) \
  case n:            \
    return heading_free ? MAKE_P(n, free) : MAKE_P(n, fixed);
  switch (numTags) {
    MAKE_CASE(1)
    MAKE_CASE(2)
    MAKE_CASE(3)
    MAKE_CASE(4)
    MAKE_CASE(5)
    MAKE_CASE(6)
    MAKE_CASE(7)
    MAKE_CASE(8)
    MAKE_CASE(9)
    MAKE_CASE(10)
    default:
      return std::nullopt;
  }
#undef MAKE_P
#undef MAKE_CASE
}

static std::optional<Problem> createProblem6D(int numTags, bool heading_free) {
#define MAKE_P6(tags, suffix)                                               \
  Problem{tags, heading_free, calc_J_6dof_##tags##_tags_heading_##suffix,   \
          calc_grad_J_6dof_##tags##_tags_heading_##suffix,                  \
          calc_hess_J_6dof_##tags##_tags_heading_##suffix}
#define MAKE_CASE6(n) \
  case n:            \
    return heading_free ? MAKE_P6(n, free) : MAKE_P6(n, fixed);
  switch (numTags) {
    MAKE_CASE6(1)
    MAKE_CASE6(2)
    MAKE_CASE6(3)
    MAKE_CASE6(4)
    MAKE_CASE6(5)
    MAKE_CASE6(6)
    MAKE_CASE6(7)
    MAKE_CASE6(8)
    MAKE_CASE6(9)
    MAKE_CASE6(10)
    default:
      return std::nullopt;
  }
#undef MAKE_P6
#undef MAKE_CASE6
}

template <int nState>
struct ProblemState {
  using StateMat = Eigen::Matrix<casadi_real, nState, 1, Eigen::ColMajor>;
  using GradientMat = Eigen::Matrix<casadi_real, nState, 1>;
  using HessianMat =
      Eigen::Matrix<casadi_real, nState, nState, Eigen::ColMajor>;

  // Parameters held constant through optimization
  Eigen::Matrix<casadi_real, 4, 4, Eigen::ColMajor> robot2camera;
  Eigen::Matrix<casadi_real, 4, Eigen::Dynamic, Eigen::ColMajor> field2points;
  Eigen::Matrix<casadi_real, 2, Eigen::Dynamic, Eigen::ColMajor>
      point_observations;
  constrained_solvepnp::CameraCalibration cameraCal;

  // our Problem with function pointers
  Problem problemSelected;

  // Measurements from external gyro
  Eigen::Matrix<casadi_real, 3, 1> gyroMeas;
  casadi_real gyro_error_scale_fac;

  // helper to fill CasADi arguments based on state size
  inline void fillArgv(const StateMat& x, const casadi_real** argv) {
    if constexpr (nState == 3) {
      argv[0] = &x[0]; argv[1] = &x[1]; argv[2] = &x[2];
      argv[3] = robot2camera.data();
      argv[4] = field2points.data();
      argv[5] = point_observations.data();
      argv[6] = &gyroMeas[2]; // Yaw only for 3-DOF
      argv[7] = &gyro_error_scale_fac;
    } else {
      argv[0] = &x[0]; argv[1] = &x[1]; argv[2] = &x[2]; // X Y Z
      argv[3] = &x[3]; argv[4] = &x[4]; argv[5] = &x[5]; // R P Y
      argv[6] = robot2camera.data();
      argv[7] = field2points.data();
      argv[8] = point_observations.data();
      argv[9] = &gyroMeas[0]; argv[10] = &gyroMeas[1]; argv[11] = &gyroMeas[2]; // R P Y
      argv[12] = &gyro_error_scale_fac;
    }
  }

  // helpers
  inline casadi_real calculateJ(const StateMat& x) {
    const casadi_real* argv[13]; fillArgv(x, argv);
    casadi_real J;
    casadi_real* j_out[] = {&J};
    if (problemSelected.calc_J(argv, j_out, NULL, NULL, 0)) {
      throw std::runtime_error("Failure calculating J!");
    }
    return J;
  }
  inline GradientMat calculateGradJ(const StateMat& x) {
    const casadi_real* argv[13]; fillArgv(x, argv);
    GradientMat g;
    casadi_real* grad_j_out[] = {g.data()};
    if (problemSelected.calc_gradJ(argv, grad_j_out, 0, 0, 0)) {
      throw std::runtime_error("Failure calculating gradJ!");
    }
    return g;
  }
  inline HessianMat calculateHessJ(const StateMat& x) {
    const casadi_real* argv[13]; fillArgv(x, argv);
    HessianMat H;
    casadi_real* hess_j_out[] = {H.data()};
    if (problemSelected.calc_hessJ(argv, hess_j_out, 0, 0, 0)) {
      throw std::runtime_error("Failure calculating hessJ!");
    }
    return H;
  }
};

// Generalized Sleipnir Newton Optimizer Loop
template <int nState>
static wpi::expected<Eigen::Matrix<double, nState, 1>, slp::ExitStatus>
run_optimizer(ProblemState<nState>& pState, Eigen::Matrix<double, nState, 1> x_guess) {
  using StateMat = Eigen::Matrix<double, nState, 1>;
  using HessianMat = Eigen::Matrix<double, nState, nState, Eigen::ColMajor>;

  StateMat x = x_guess;

  // Sleipnir's delta_I caching algo and Newton.cpp inspiration from
  // https://github.com/SleipnirGroup/Sleipnir/blob/5af8519f268a8075e245bb7cd411a81e1598f521/src/optimization/RegularizedLDLT.hpp#L163
  // licensed under BSD 3-Clause

  /// The value of δ from the previous iteration - 1e-4 is a sane guess
  /// TUNABLE
  double δ = 1e-4 * 2.0;

  constexpr double ERROR_TOL = 1e-4;

  for (int iter = 0; iter < 100; iter++) {
    auto iter_start = wpi::Now();

    // Check for diverging iterates
    if (x.template lpNorm<Eigen::Infinity>() > 1e20 || !x.allFinite()) {
      return wpi::unexpected{slp::ExitStatus::DIVERGING_ITERATES};
    }

    StateMat g = pState.calculateGradJ(x);

    // If our previous step found an x such grad(J) is acceptable, we're done
    auto norm_g = g.template lpNorm<Eigen::Infinity>();
    if (norm_g < ERROR_TOL) {
      if constexpr (VERBOSE)
        fmt::println("{}: Exiting due to convergence (‖∇J‖={})", iter, norm_g);
      break;
    }

    HessianMat H = pState.calculateHessJ(x);

    /// Regularization. If the Hessian inertia is already OK, don't adjust

    auto H_ldlt = H.ldlt();
    if (H_ldlt.info() != Eigen::Success) {
      return wpi::unexpected{slp::ExitStatus::LOCALLY_INFEASIBLE};
    }

    // Make sure H is positive definite (all eigenvalues are > 0)
    int i_reg{0};
    if ((H_ldlt.vectorD().array() <= 0.0).any()) {
      // If the Hessian wasn't regularized in a previous iteration, start at a
      // small value of δ. Otherwise, attempt a δ half as big as the previous
      // run so δ can trend downwards over time.
      δ = δ / 2.0;

      // Arbitrary max on regularization iterations
      int MAX_REG_STEPS = 100;
      for (i_reg = 0; i_reg < MAX_REG_STEPS; i_reg++) {
        HessianMat delta_I = HessianMat::Identity() * δ;
        H = H + delta_I; // Push toward stability
        H_ldlt = H.ldlt();

        if (H_ldlt.info() != Eigen::Success) {
          return wpi::unexpected{slp::ExitStatus::LOCALLY_INFEASIBLE};
        }

        // If our eigenvalues aren't positive definite, pick a new δ for next loop
        if ((H_ldlt.vectorD().array() <= 0.0).any()) {
          δ *= 10.0;
          if (δ > 1e20) {
            return wpi::unexpected{slp::ExitStatus::LOCALLY_INFEASIBLE};
          }
        } else {
          break;
        }
      }

      if (i_reg == MAX_REG_STEPS) {
        return wpi::unexpected{slp::ExitStatus::LOCALLY_INFEASIBLE};
      }
    }

    // Solve for p_x
    StateMat p_x = H_ldlt.solve(-g);

    double old_cost = pState.calculateJ(x);
    double alpha = 1.0;

    // Iterate until our chosen trial_x decreases our cost
    int alpha_refinement{0};
    bool step_accepted = false;
    for (alpha_refinement = 0; alpha_refinement < 100; alpha_refinement++) {
      StateMat trial_x = x + alpha * p_x;

      casadi_real new_cost = pState.calculateJ(trial_x);

      if (std::isfinite(new_cost) && new_cost < old_cost) {
        x = trial_x;
        step_accepted = true;
        break;
      } else {
        alpha *= 0.5;

        // safety factor for the minimal step size
        constexpr double α_min_frac = 0.05;
        constexpr double γConstraint = 1e-5;

        if (alpha < α_min_frac * γConstraint) {
          return wpi::unexpected{slp::ExitStatus::LOCALLY_INFEASIBLE};
        }
      }
    }
    if (!step_accepted) return wpi::unexpected{slp::ExitStatus::LOCALLY_INFEASIBLE};

    auto iter_end = wpi::Now();
    if constexpr (VERBOSE) {
      fmt::println("{}: {} uS, ‖∇J‖={}, α={}", iter, iter_end - iter_start, g.norm(), alpha);
    }
  }
  return x;
}

wpi::expected<constrained_solvepnp::RobotStateMat, slp::ExitStatus>
constrained_solvepnp::do_optimization(
    bool heading_free, int nTags,
    constrained_solvepnp::CameraCalibration cameraCal,
    // Note that casadi is column major, apparently
    Eigen::Matrix<casadi_real, 4, 4, Eigen::ColMajor> robot2camera,
    constrained_solvepnp::RobotStateMat x_guess,
    Eigen::Matrix<casadi_real, 4, Eigen::Dynamic, Eigen::ColMajor> field2points,
    Eigen::Matrix<casadi_real, 2, Eigen::Dynamic, Eigen::ColMajor>
        point_observations,
    double gyroθ, double gyroErrorScaleFac) {
  
  if (field2points.cols() != (nTags * 4) || point_observations.cols() != (nTags * 4)) {
    return wpi::unexpected{slp::ExitStatus::NONFINITE_INITIAL_COST_OR_CONSTRAINTS};
  }

  // rescale observations to homogenous pixel coordinates
  for (int i = 0; i < point_observations.cols(); ++i) {
    point_observations(0, i) = (point_observations(0, i) - cameraCal.cx) / cameraCal.fx;
    point_observations(1, i) = (point_observations(1, i) - cameraCal.cy) / cameraCal.fy;
  }

  auto problemOpt = createProblem(nTags, heading_free);
  if (!problemOpt) {
    return wpi::unexpected{slp::ExitStatus::NONFINITE_INITIAL_COST_OR_CONSTRAINTS};
  }

  Eigen::Matrix<casadi_real, 3, 1> gyroVec; gyroVec << 0, 0, gyroθ;

  ProblemState<3> pState{robot2camera, field2points, point_observations,
                         cameraCal, *problemOpt, gyroVec, gyroErrorScaleFac};

  return run_optimizer<3>(pState, x_guess);
}

wpi::expected<constrained_solvepnp::RobotState6DMat, slp::ExitStatus>
constrained_solvepnp::do_optimization_6dof(
    bool heading_free, int nTags,
    constrained_solvepnp::CameraCalibration cameraCal,
    Eigen::Matrix<casadi_real, 4, 4, Eigen::ColMajor> robot2camera,
    constrained_solvepnp::RobotState6DMat x_guess,
    Eigen::Matrix<casadi_real, 4, Eigen::Dynamic, Eigen::ColMajor> field2points,
    Eigen::Matrix<casadi_real, 2, Eigen::Dynamic, Eigen::ColMajor>
        point_observations,
    Eigen::Matrix<casadi_real, 3, 1> gyroMeas3D,
    double gyroErrorScaleFac) {

  if (field2points.cols() != (nTags * 4) || point_observations.cols() != (nTags * 4)) {
    return wpi::unexpected{slp::ExitStatus::NONFINITE_INITIAL_COST_OR_CONSTRAINTS};
  }

  // rescale observations to homogenous pixel coordinates
  for (int i = 0; i < point_observations.cols(); ++i) {
    point_observations(0, i) = (point_observations(0, i) - cameraCal.cx) / cameraCal.fx;
    point_observations(1, i) = (point_observations(1, i) - cameraCal.cy) / cameraCal.fy;
  }

  auto problemOpt = createProblem6D(nTags, heading_free);
  if (!problemOpt) {
    return wpi::unexpected{slp::ExitStatus::NONFINITE_INITIAL_COST_OR_CONSTRAINTS};
  }

  ProblemState<6> pState{robot2camera, field2points, point_observations,
                         cameraCal, *problemOpt, gyroMeas3D, gyroErrorScaleFac};

  return run_optimizer<6>(pState, x_guess);
}
