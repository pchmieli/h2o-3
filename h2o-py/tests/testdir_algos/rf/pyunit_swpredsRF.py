import sys
sys.path.insert(1, "../../../")
import h2o, tests

def swpredsRF():
    # Training set has two predictor columns
    # X1: 10 categorical levels, 100 observations per level; X2: Unif(0,1) noise
    # Ratio of y = 1 per Level: cat01 = 1.0 (strong predictor), cat02 to cat10 = 0.5 (weak predictors)

    
    

    #Log.info("Importing swpreds_1000x3.csv data...\n")
    swpreds = h2o.import_file(path=h2o.locate("smalldata/gbm_test/swpreds_1000x3.csv"))
    swpreds["y"] = swpreds["y"].asfactor()

    #Log.info("Summary of swpreds_1000x3.csv from H2O:\n")
    #swpreds.summary()

    # Train H2O DRF without Noise Column
    #Log.info("Distributed Random Forest with only Predictor Column")
    model1 = h2o.random_forest(x=swpreds[["X1"]], y=swpreds["y"], ntrees=50, max_depth=20, nbins=500)
    model1.show()
    perf1 = model1.model_performance(swpreds)
    print(perf1.auc())

    # Train H2O DRF Model including Noise Column:
    #Log.info("Distributed Random Forest including Noise Column")
    model2 = h2o.random_forest(x=swpreds[["X1","X2"]], y=swpreds["y"], ntrees=50, max_depth=20, nbins=500)
    model2.show()
    perf2 = model2.model_performance(swpreds)
    print(perf2.auc())
  
if __name__ == "__main__":
  tests.run_test(sys.argv, swpredsRF)
