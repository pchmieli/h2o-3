"""
This module implements the base model class.  All model things inherit from this class.
"""

import h2o
from . import H2OFrame
from . import H2OConnection


class ModelBase(object):
  def __init__(self, dest_key, model_json, metrics_class):
    self._id = dest_key
    self._model_json = model_json
    self._metrics_class = metrics_class
    self._is_xvalidated=False
    self._xval_keys=None

    if dest_key is not None and model_json is not None and metrics_class is not None:
      # build Metric objects out of each metrics
      for metric in ["training_metrics", "validation_metrics", "cross_validation_metrics"]:
        if metric in model_json["output"]:
          if  model_json["output"][metric] is not None:
            if metric=="cross_validation_metrics":
              self._is_xvalidated=True
            model_json["output"][metric] = metrics_class(model_json["output"][metric],metric,model_json["algo"])

      if self._is_xvalidated: self._xval_keys= [i["name"] for i in model_json["output"]["cross_validation_models"]]

      # build a useful dict of the params
      self._params={}
      for p in self._model_json["parameters"]: self._params[p["label"]]=p

  @property
  def id(self):
    """
    :return: Retrieve this model's identifier.
    """
    return self._id

  @property
  def params(self):
    """
    Get the parameters and the actual/default values only.

    :return: A dictionary of parameters used to build this model.
    """
    params = {}
    for p in self._params:
      params[p] = {"default":self._params[p]["default_value"], "actual":self._params[p]["actual_value"]}
    return params

  @property
  def full_parameters(self):
    """
    Get the full specification of all parameters.

    :return: a dictionary of parameters used to build this model.
    """
    return self._params

  def __repr__(self):
    self.show()
    return ""

  def predict(self, test_data):
    """
    Predict on a dataset.

    :param test_data: Data to be predicted on.
    :return: A new H2OFrame filled with predictions.
    """
    if not isinstance(test_data, H2OFrame): raise ValueError("test_data must be an instance of H2OFrame")
    test_data._eager()
    j = H2OConnection.post_json("Predictions/models/" + self._id + "/frames/" + test_data._id)
    # prediction_frame_id = j["predictions_frame"] #j["model_metrics"][0]["predictions"]["frame_id"]["name"]
    return h2o.get_frame(j["predictions_frame"]["name"])

  def is_cross_validated(self):
    """
    :return:  True if the model was cross-validated.
    """
    return self._is_xvalidated

  def xval_keys(self):
    """
    :return: The model keys for the cross-validated model.
    """
    return self._xval_keys

  def get_xval_models(self,key=None):
    """
    Return a Model object.

    :param key: If None, return all cross-validated models; otherwise return the model that key points to.
    :return: A model or list of models.
    """
    return h2o.get_model(key) if key is not None else [h2o.get_model(k) for k in self._xval_keys]

  @property
  def xvals(self):
    """
    Return a list of the cross-validated models.

    :return: A list of models
    """
    return self.get_xval_models()

  def deepfeatures(self, test_data, layer):
    """
    Return hidden layer details

    :param test_data: Data to create a feature space on
    :param layer: 0 index hidden layer
    """
    if test_data is None: raise ValueError("Must specify test data")
    test_data._eager()
    j = H2OConnection.post_json("Predictions/models/" + self._id + "/frames/" + test_data._id, deep_features_hidden_layer=layer)
    return h2o.get_frame(j["predictions_frame"]["name"])

  def weights(self, matrix_id=0):
    """
    Return the frame for the respective weight matrix
    :param: matrix_id: an integer, ranging from 0 to number of layers, that specifies the weight matrix to return.
    :return: an H2OFrame which represents the weight matrix identified by matrix_id
    """
    num_weight_matrices = len(self._model_json['output']['weights'])
    if matrix_id not in range(num_weight_matrices):
      raise ValueError("Weight matrix does not exist. Model has {0} weight matrices (0-based indexing), but matrix {1} "
                       "was requested.".format(num_weight_matrices, matrix_id))
    return h2o.get_frame(self._model_json['output']['weights'][matrix_id]['URL'].split('/')[3])

  def biases(self, vector_id=0):
    """
    Return the frame for the respective bias vector
    :param: vector_id: an integer, ranging from 0 to number of layers, that specifies the bias vector to return.
    :return: an H2OFrame which represents the bias vector identified by vector_id
    """
    num_bias_vectors = len(self._model_json['output']['biases'])
    if vector_id not in range(num_bias_vectors):
      raise ValueError("Bias vector does not exist. Model has {0} bias vectors (0-based indexing), but vector {1} "
                       "was requested.".format(num_bias_vectors, vector_id))
    return h2o.get_frame(self._model_json['output']['biases'][vector_id]['URL'].split('/')[3])

  def model_performance(self, test_data=None, train=False, valid=False):
    """
    Generate model metrics for this model on test_data.

    :param test_data: Data set for which model metrics shall be computed against. Both train and valid arguments are ignored if test_data is not None.
    :param train: Report the training metrics for the model. If the test_data is the training data, the training metrics are returned.
    :param valid: Report the validation metrics for the model. If train and valid are True, then it defaults to True.
    :return: An object of class H2OModelMetrics.
    """
    if test_data is None:
      if not train and not valid: train = True  # default to train
      if train: return self._model_json["output"]["training_metrics"]
      if valid: return self._model_json["output"]["validation_metrics"]

    else:  # cases dealing with test_data not None
      if not isinstance(test_data, H2OFrame):
        raise ValueError("`test_data` must be of type H2OFrame.  Got: " + type(test_data))
      test_data._eager()
      res = H2OConnection.post_json("ModelMetrics/models/" + self._id + "/frames/" + test_data._id)

      # FIXME need to do the client-side filtering...  PUBDEV-874:   https://0xdata.atlassian.net/browse/PUBDEV-874
      raw_metrics = None
      for mm in res["model_metrics"]:
        if mm["frame"]["name"] == test_data._id:
          raw_metrics = mm
          break
      return self._metrics_class(raw_metrics,algo=self._model_json["algo"])

  def score_history(self):
    """
    Retrieve Model Score History
    :return: the score history (H2OTwoDimTable)
    """
    model = self._model_json["output"]
    if 'scoring_history' in model.keys() and model["scoring_history"] != None:
      s = model["scoring_history"]
      if h2o.can_use_pandas():
        import pandas
        pandas.options.display.max_rows = 20
        return pandas.DataFrame(s.cell_values,columns=s.col_header)
      return model["scoring_history"]
    else: print "No score history for this model"


  def summary(self):
    """
    Print a detailed summary of the model.

    :return:
    """
    model = self._model_json["output"]
    if model["model_summary"]:
      model["model_summary"].show()  # H2OTwoDimTable object


  def show(self):
    """
    Print innards of model, without regards to type

    :return: None
    """
    model = self._model_json["output"]
    print "Model Details"
    print "============="

    print self.__class__.__name__, ": ", self._model_json["algo_full_name"]
    print "Model Key: ", self._id

    self.summary()

    print
    # training metrics
    tm = model["training_metrics"]
    if tm: tm.show()
    vm = model["validation_metrics"]
    if vm: vm.show()
    xm = model["cross_validation_metrics"]
    if xm: xm.show()

    if "scoring_history" in model.keys() and model["scoring_history"]: model["scoring_history"].show()
    if "variable_importances" in model.keys() and model["variable_importances"]: model["variable_importances"].show()

  def varimp(self, return_list=False):
    """
    Pretty print the variable importances, or return them in a list
    :param return_list: if True, then return the variable importances in an list (ordered from most important to least
    important). Each entry in the list is a 4-tuple of (variable, relative_importance, scaled_importance, percentage).
    :return: None or ordered list
    """
    model = self._model_json["output"]
    if "variable_importances" in model.keys() and model["variable_importances"]:
      if not return_list: return model["variable_importances"].show()
      else: return model["variable_importances"].cell_values
    else:
      print "Warning: This model doesn't have variable importances"

  def residual_deviance(self,train=False,valid=False,xval=False):
    """
    Retreive the residual deviance if this model has the attribute, or None otherwise.

    :param train: Get the residual deviance for the training set. If both train and valid are False, then train is selected by default.
    :param valid: Get the residual deviance for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the residual deviance, or None if it is not present.
    """
    if xval: raise ValueError("Cross-validation metrics are not available.")
    if not train and not valid: train = True
    if train and valid:  train = True
    return self._model_json["output"]["training_metrics"].residual_deviance() if train else self._model_json["output"]["validation_metrics"].residual_deviance()

  def residual_degrees_of_freedom(self,train=False,valid=False,xval=False):
    """
    Retreive the residual degress of freedom if this model has the attribute, or None otherwise.

    :param train: Get the residual dof for the training set. If both train and valid are False, then train is selected by default.
    :param valid: Get the residual dof for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the residual dof, or None if it is not present.
    """
    if xval: raise ValueError("Cross-validation metrics are not available.")
    if not train and not valid: train = True
    if train and valid:         train = True
    return self._model_json["output"]["training_metrics"].residual_degrees_of_freedom() if train else self._model_json["output"]["validation_metrics"].residual_degrees_of_freedom()

  def null_deviance(self,train=False,valid=False,xval=False):
    """
    Retreive the null deviance if this model has the attribute, or None otherwise.

    :param:  train Get the null deviance for the training set. If both train and valid are False, then train is selected by default.
    :param:  valid Get the null deviance for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the null deviance, or None if it is not present.
    """
    if xval: raise ValueError("Cross-validation metrics are not available.")
    if not train and not valid: train = True
    if train and valid:         train = True
    return self._model_json["output"]["training_metrics"].null_deviance() if train else self._model_json["output"]["validation_metrics"].null_deviance()

  def null_degrees_of_freedom(self,train=False,valid=False,xval=False):
    """
    Retreive the null degress of freedom if this model has the attribute, or None otherwise.

    :param train: Get the null dof for the training set. If both train and valid are False, then train is selected by default.
    :param valid: Get the null dof for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the null dof, or None if it is not present.
    """
    if xval: raise ValueError("Cross-validation metrics are not available.")
    if not train and not valid: train = True
    if train and valid:         train = True
    return self._model_json["output"]["training_metrics"].null_degrees_of_freedom() if train else self._model_json["output"]["validation_metrics"].null_degrees_of_freedom()

  def pprint_coef(self):
    """
    Pretty print the coefficents table (includes normalized coefficients)
    :return: None
    """
    print self._model_json["output"]["coefficients_table"]  # will return None if no coefs!

  def coef(self):
    """
    :return: Return the coefficients for this model.
    """
    tbl = self._model_json["output"]["coefficients_table"]
    if tbl is None: return None
    tbl = tbl.cell_values
    return {a[0]:a[1] for a in tbl}

  def coef_norm(self):
    """
    :return: Return the normalized coefficients
    """
    tbl = self._model_json["output"]["coefficients_table"]
    if tbl is None: return None
    tbl = tbl.cell_values
    return {a[0]:a[2] for a in tbl}

  def r2(self,  train=False, valid=False, xval=False):
    """
    Return the R^2 for this regression model.

    The R^2 value is defined to be 1 - MSE/var,
    where var is computed as sigma*sigma.

    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the R^2 value for the training data.
    :param valid: If valid is True, then return the R^2 value for the validation data.
    :param xval:  If xval is True, then return the R^2 value for the cross validation data.
    :return: The R^2 for this regression model.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.r2()
    return m.values()[0] if len(m) == 1 else m

  def mse(self, train=False, valid=False, xval=False):
    """
    Get the MSE(s).
    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the MSE value for the training data.
    :param valid: If valid is True, then return the MSE value for the validation data.
    :param xval:  If xval is True, then return the MSE value for the cross validation data.
    :return: The MSE for this regression model.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.mse()
    return m.values()[0] if len(m) == 1 else m

  def logloss(self, train=False, valid=False, xval=False):
    """
    Get the Log Loss(s).
    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the Log Loss value for the training data.
    :param valid: If valid is True, then return the Log Loss value for the validation data.
    :param xval:  If xval is True, then return the Log Loss value for the cross validation data.
    :return: The Log Loss for this binomial model.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.logloss()
    return m.values()[0] if len(m) == 1 else m

  def mean_residual_deviance(self, train=False, valid=False, xval=False):
    """
    Get the Mean Residual Deviances(s).
    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the Mean Residual Deviance value for the training data.
    :param valid: If valid is True, then return the Mean Residual Deviance value for the validation data.
    :param xval:  If xval is True, then return the Mean Residual Deviance value for the cross validation data.
    :return: The Mean Residual Deviance for this regression model.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.mean_residual_deviance()
    return m.values()[0] if len(m) == 1 else m

  def auc(self, train=False, valid=False, xval=False):
    """
    Get the AUC(s).
    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the AUC value for the training data.
    :param valid: If valid is True, then return the AUC value for the validation data.
    :param xval:  If xval is True, then return the AUC value for the validation data.
    :return: The AUC.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.auc()
    return m.values()[0] if len(m) == 1 else m

  def aic(self, train=False, valid=False, xval=False):
    """
    Get the AIC(s).
    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the AIC value for the training data.
    :param valid: If valid is True, then return the AIC value for the validation data.
    :param xval:  If xval is True, then return the AIC value for the validation data.
    :return: The AIC.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.aic()
    return m.values()[0] if len(m) == 1 else m

  def giniCoef(self, train=False, valid=False, xval=False):
    """
    Get the Gini Coefficient(s).
    If all are False (default), then return the training metric value.
    If more than one options is set to True, then return a dictionary of metrics where the keys are "train", "valid",
    and "xval"

    :param train: If train is True, then return the Gini Coefficient value for the training data.
    :param valid: If valid is True, then return the Gini Coefficient value for the validation data.
    :param xval:  If xval is True, then return the Gini Coefficient value for the cross validation data.
    :return: The Gini Coefficient for this binomial model.
    """
    tm = ModelBase._get_metrics(self, train, valid, xval)
    m = {}
    for k,v in zip(tm.keys(),tm.values()): m[k] = None if v is None else v.giniCoef()
    return m.values()[0] if len(m) == 1 else m

  def download_pojo(self,path=""):
    """
    Download the POJO for this model to the directory specified by path (no trailing slash!).
    If path is "", then dump to screen.
    :param model: Retrieve this model's scoring POJO.
    :param path:  An absolute path to the directory where POJO should be saved.
    :return: None
    """
    h2o.download_pojo(self,path)  # call the "package" function

  @staticmethod
  def _get_metrics(o, train, valid, xval):
    metrics = {}
    if train: metrics["train"] = o._model_json["output"]["training_metrics"]
    if valid: metrics["valid"] = o._model_json["output"]["validation_metrics"]
    if xval : metrics["xval"]  = o._model_json["output"]["cross_validation_metrics"]
    if len(metrics) == 0: metrics["train"] = o._model_json["output"]["training_metrics"]
    return metrics

  # Delete from cluster as model goes out of scope
  # def __del__(self):
  #   h2o.remove(self._id)

  @staticmethod
  def _has(dictionary, key):
    return key in dictionary and dictionary[key] is not None

  @staticmethod
  def _check_targets(y_actual, y_predicted):
    """
    Check that y_actual and y_predicted have the same length.

    :param y_actual: An H2OFrame
    :param y_predicted: An H2OFrame
    :return: None
    """
    if len(y_actual) != len(y_predicted):
      raise ValueError("Row mismatch: [{},{}]".format(len(y_actual),len(y_predicted)))
