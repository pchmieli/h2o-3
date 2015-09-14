"""
Regression Models
"""

import math
from metrics_base import *

class H2ORegressionModel(ModelBase):
  """
  Class for Regression models.  
  """
  def __init__(self, dest_key, model_json):
    super(H2ORegressionModel, self).__init__(dest_key, model_json,H2ORegressionModelMetrics)


def _mean_var(frame, weights=None):
  """
  Compute the (weighted) mean and variance

  :param frame: Single column H2OFrame
  :param weights: optional weights column
  :return: The (weighted) mean and variance
  """
  return frame.mean(), frame.var()


def h2o_mean_absolute_error(y_actual, y_predicted, weights=None):
  """
  Mean absolute error regression loss.

  :param y_actual: H2OFrame of actual response.
  :param y_predicted: H2OFrame of predicted response.
  :param weights: (Optional) sample weights
  :return: loss (float) (best is 0.0)

  """
  ModelBase._check_targets(y_actual, y_predicted)
  return (y_predicted-y_actual).abs().mean()


def h2o_mean_squared_error(y_actual, y_predicted, weights=None):
  """
  Mean squared error regression loss

  :param y_actual: H2OFrame of actual response.
  :param y_predicted: H2OFrame of predicted response.
  :param weights: (Optional) sample weights
  :return: loss (float) (best is 0.0)
  """
  ModelBase._check_targets(y_actual, y_predicted)
  return ((y_predicted-y_actual)**2).mean()


def h2o_median_absolute_error(y_actual, y_predicted):
  """
  Median absolute error regression loss

  :param y_actual: H2OFrame of actual response.
  :param y_predicted: H2OFrame of predicted response.
  :return: loss (float) (best is 0.0)
  """
  ModelBase._check_targets(y_actual, y_predicted)
  return (y_predicted-y_actual).abs().median()


def h2o_explained_variance_score(y_actual, y_predicted, weights=None):
  """
  Explained variance regression score function

  :param y_actual: H2OFrame of actual response.
  :param y_predicted: H2OFrame of predicted response.
  :param weights: (Optional) sample weights
  :return: the explained variance score (float)
  """
  ModelBase._check_targets(y_actual, y_predicted)

  _, numerator   = _mean_var(y_actual - y_predicted, weights)
  _, denominator = _mean_var(y_actual, weights)
  if denominator == 0.0:
    return 1. if numerator == 0 else 0.  # 0/0 => 1, otherwise, 0
  return 1 - numerator / denominator


def h2o_r2_score(y_actual, y_predicted, weights=1.):
  """
  R^2 (coefficient of determination) regression score function

  :param y_actual: H2OFrame of actual response.
  :param y_predicted: H2OFrame of predicted response.
  :param weights: (Optional) sample weights
  :return: R^2 (float) (best is 1.0, lower is worse)
  """
  ModelBase._check_targets(y_actual, y_predicted)
  numerator   = (weights * (y_actual - y_predicted) ** 2).sum()
  denominator = (weights * (y_actual - y_actual.mean()) ** 2).sum()

  if denominator == 0.0:
    return 1. if numerator == 0. else 0.  # 0/0 => 1, else 0
  return 1 - numerator / denominator
