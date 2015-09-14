import sys
sys.path.insert(1, "../../../")
import h2o, tests

def bigcatGBM():
  
  
  
  #Log.info("Importing bigcat_5000x2.csv data...\n")
  bigcat = h2o.import_file(path=h2o.locate("smalldata/gbm_test/bigcat_5000x2.csv"))
  bigcat["y"] = bigcat["y"].asfactor()
  #Log.info("Summary of bigcat_5000x2.csv from H2O:\n")
  #bigcat.summary()
  
  # Train H2O GBM Model:
  #Log.info("H2O GBM with parameters:\nntrees = 1, max_depth = 1, nbins = 100\n")
  model = h2o.gbm(x=bigcat[["X"]], y = bigcat["y"], distribution="bernoulli", ntrees=1, max_depth=1, nbins=100)
  model.show()
  performance = model.model_performance(bigcat)
  performance.show()
  
  # Check AUC and overall prediction error
  #test_accuracy = performance.accuracy()
  test_auc = performance.auc()

if __name__ == "__main__":
  tests.run_test(sys.argv, bigcatGBM)
